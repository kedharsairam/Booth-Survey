package com.kraft.voterlist.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes the app's data.
 *
 * **One file per booth.** Not one file holding everything. The guarantee that covering a second
 * booth cannot put a question mark against a day's work on the first is only worth something if
 * it is structural rather than a promise: a write that goes wrong while saving booth 109 lands
 * on booth 109's file, and the two never share a byte. An index file names them and a third
 * holds the shared vocabulary.
 *
 * Each file is written to a temporary name in the same directory, flushed to the platter, then
 * renamed over the real one. The obvious `writeText` truncates before it writes a single byte,
 * so a process killed part-way through leaves a half-written file that will not parse -- and
 * half a survey is worth less than none, because it looks recoverable and is not. Android kills
 * backgrounded processes freely, and this one is used outdoors where memory pressure is normal,
 * so the window is real rather than theoretical.
 *
 * A file that will not parse is moved aside rather than thrown away, and the app continues on
 * the rest. Letting the exception out would mean refusing to start with no way back in.
 */
/** A backup file, read. Null booths and lists mean the file was not one of ours. */
data class Backup(
    val booth: Booth,
    val lists: Lists,
    val savedAt: Long,
)

class Store(private val context: Context, private val root: File? = null) {

    /**
     * Where everything lives.
     *
     * Defaults to the app's own files directory, which is right for the app and wrong for
     * anything else. A test that builds a ViewModel from the *app's* context therefore gets
     * the surveyor's real store -- and a drag test whose setup clears the store to start
     * from a known state will clear a real booth off a real phone, which is exactly what
     * happened. Passing a root lets a test point the whole store somewhere disposable, so
     * the failure is a directory rather than somebody's day's work.
     */
    private val base: File get() = root ?: context.filesDir

    /**
     * Serialises every write.
     *
     * Two saves can be in flight at once -- typing a phone number fires one, tapping Mark
     * visited fires another -- and they were writing to the *same* temporary filename. The
     * first to finish renamed it away, the second's rename failed, and its fallback then threw
     * NoSuchFileException on a file that no longer existed. Crashing the app mid-edit is bad
     * enough; losing the save underneath it is worse.
     *
     * The writes are a few hundred kilobytes and already on the IO dispatcher, so queueing
     * them costs nothing a person would ever notice.
     */
    private val writeLock = Mutex()

    private val boothsDir: File get() = File(base, "booths")
    private val indexFile: File get() = File(base, "booths.json")
    private val listsFile: File get() = File(base, "lists.json")

    /** The booth index: enough to draw the selector without reading every voter's file. */
    data class Index(
        val activeId: String? = null,
        val booths: List<Entry> = emptyList(),
    ) {
        data class Entry(
            val id: String,
            val boothNo: String,
            val mandal: String,
            val townWard: String,
            val label: String,
            val size: Int,
            val done: Int,
            val notInFile: Int,
            val updatedAt: Long,
        )
    }

    suspend fun loadIndex(): Index = withContext(Dispatchers.IO) { readIndex() }

    suspend fun loadLists(): Lists = withContext(Dispatchers.IO) {
        if (!listsFile.exists()) return@withContext Lists()
        readJson(listsFile)?.let { parseLists(it) } ?: Lists()
    }

    suspend fun saveLists(lists: Lists) = withContext(Dispatchers.IO) {
        writeAtomically(listsFile) { out ->
            out.write(
                JSONObject().apply {
                    put("castes", JSONArray(lists.castes))
                    put("addresses", JSONArray(lists.addresses))
                    put("parties", JSONArray(lists.parties))
                }.toString().toByteArray(Charsets.UTF_8)
            )
        }
    }

    /**
     * A booth that could not be read, and where the copy that was kept goes.
     *
     * Set instead of returning an empty booth. Returning one made a corrupt file look exactly
     * like a phone that had never been used -- the "No voters list yet" screen -- and the next
     * import then merged against that empty booth and wrote over the file. A day's work
     * survived only as a timestamped file in the data folder that nothing ever mentions.
     */
    var unreadableBooth: Pair<String, String>? = null

    /** Reads one booth's voters and families, or an empty booth if its file is unreadable. */
    suspend fun loadBooth(id: String): Booth = withContext(Dispatchers.IO) {
        val f = File(boothsDir, "$id.json")
        if (!f.exists()) return@withContext Booth(id = id, boothNo = "")
        val root = readJson(f)
        if (root == null) {
            val kept = File(boothsDir, "$id-unreadable.json")
            unreadableBooth = id to (if (kept.exists()) kept.name else "another file")
            return@withContext Booth(id = id, boothNo = "")
        }
        parseBooth(root)
    }

    /**
     * Which tab he was on and what he had filtered to.
     *
     * Opening the app dropped him on Home every time. Mid-afternoon he is on Voters,
     * filtered to one street, and has to re-tap and re-filter before he can carry on --
     * with a roll of over a thousand, and a queue of people at the door.
     *
     * Kept as its own small file rather than in the index, because it is about the session
     * rather than about the booth, and it changes far more often than either.
     */
    suspend fun saveSession(json: String) = withContext(Dispatchers.IO) {
        runCatching { writeAtomically(sessionFile) { it.write(json.toByteArray(Charsets.UTF_8)) } }
    }

    /**
     * The one action that can be taken back, written down so it outlives the process.
     *
     * Undo used to live only in memory, which meant it lasted exactly as long as the app
     * being in the foreground. Android kills background apps on a phone that has been out
     * all day, and the moment it did the safety net was gone -- so the net was least
     * reliable exactly when a tired person was most likely to need it.
     *
     * Only written when something undoable happens, which is rare: the bulk actions, the
     * deletes, the family moves. Typing a phone number does not touch this.
     */
    suspend fun saveUndo(json: String) = withContext(Dispatchers.IO) {
        runCatching { writeAtomically(undoFile) { it.write(json.toByteArray(Charsets.UTF_8)) } }
    }

    suspend fun loadUndo(): String? = withContext(Dispatchers.IO) {
        if (!undoFile.exists()) null
        else runCatching { undoFile.readText() }.getOrNull()
    }

    suspend fun clearUndo() = withContext(Dispatchers.IO) {
        runCatching { undoFile.delete() }
        Unit
    }

    private val undoFile: File get() = File(base, "undo.json")

    suspend fun loadSession(): String? = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) null
        else runCatching { sessionFile.readText() }.getOrNull()
    }

    private val sessionFile: File get() = File(base, "session.json")

    suspend fun saveIndex(index: Index) = withContext(Dispatchers.IO) {
        writeAtomically(indexFile) { out ->
            out.write(writeIndex(index).toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun saveBooth(booth: Booth) = withContext(Dispatchers.IO) {
        if (!boothsDir.exists()) boothsDir.mkdirs()
        writeAtomically(File(boothsDir, "${booth.id}.json")) { out ->
            out.write(writeBooth(booth).toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun deleteBooth(id: String) = withContext(Dispatchers.IO) {
        File(boothsDir, "$id.json").delete()
    }

    private fun readIndex(): Index {
        if (!indexFile.exists()) return Index()
        val root = readJson(indexFile) ?: return Index()
        val arr = root.optJSONArray("booths") ?: return Index()
        val entries = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Index.Entry(
                id = o.optString("id"),
                boothNo = o.optString("boothNo"),
                mandal = o.optString("mandal"),
                townWard = o.optString("townWard"),
                label = o.optString("label"),
                size = o.optInt("size"),
                done = o.optInt("done"),
                notInFile = o.optInt("notInFile"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
        val active = root.stringOrNull("activeId")?.takeIf { id -> entries.any { it.id == id } }
        return Index(active, entries)
    }

    private fun writeIndex(index: Index): String = JSONObject().apply {
        put("version", VERSION)
        put("activeId", index.activeId ?: JSONObject.NULL)
        put("booths", JSONArray().apply {
            for (e in index.booths) {
                put(JSONObject().apply {
                    put("id", e.id)
                    put("boothNo", e.boothNo)
                    put("mandal", e.mandal)
                    put("townWard", e.townWard)
                    put("label", e.label)
                    put("size", e.size)
                    put("done", e.done)
                    put("notInFile", e.notInFile)
                    put("updatedAt", e.updatedAt)
                })
            }
        })
    }.toString()

    private fun parseBooth(root: JSONObject): Booth {
        val id = root.optString("id").ifBlank { Voter.newId() }
        val arr = root.optJSONArray("voters") ?: JSONArray()
        val voters = ArrayList<Voter>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            voters += Voter(
                // A record with no id is either hand-edited or from an older build. Minting one
                // here keeps such a file usable instead of needing a migration: ids are only
                // ever compared for equality.
                id = o.optString("id").ifBlank { Voter.newId() },
                serial = o.optInt("serial"),
                voterId = o.optString("voterId"),
                name = o.optString("name"),
                house = o.optString("house"),
                phone = o.optString("phone"),
                address = o.optString("address"),
                caste = o.optString("caste"),
                subCaste = o.optString("subCaste"),
                party = o.optString("party"),
                visited = o.optBoolean("visited"),
                skipped = o.optBoolean("skipped"),
                familyId = o.stringOrNull("familyId"),
                familyOrder = o.optInt("familyOrder"),
                notInLatestFile = o.optBoolean("notInLatestFile"),
            )
        }
        val families = root.optJSONArray("families")?.let { a ->
            (0 until a.length()).map { i ->
                // Only the id and the position. A label may be present in a file written
                // before family names were derived, and it is deliberately ignored: a stored
                // name has to be kept in step with the arrangement by hand and would
                // eventually be wrong with nothing to say so.
                //
                // Files written before positions existed carry no order: they take their
                // place in the stored list, which is creation order. That keeps the old
                // arrangement as the starting point rather than scrambling it, and the first
                // move assigns the clean run from there.
                val o = a.getJSONObject(i)
                val stored = if (o.has("order")) o.optInt("order") else i
                Family(o.optString("id").ifBlank { Voter.newId() }, stored)
            }
        } ?: emptyList()
        return Booth(
            id = id,
            boothNo = root.optString("boothNo"),
            mandal = root.optString("mandal"),
            townWard = root.optString("townWard"),
            label = root.optString("label"),
            voters = voters,
            families = families,
            importedAt = root.optLong("importedAt"),
        )
    }

    // --- backup ------------------------------------------------------------

    // --- keeping and noticing ----------------------------------------------

    /**
     * Writes a copy into the app's own folder and returns whether it worked.
     *
     * Used for the "before" copy a restore makes of whatever it is about to replace. It is
     * not somewhere he will find by himself, which is the trade: it is insurance against
     * the app doing something wrong, not a backup he is meant to manage.
     */
    suspend fun keepCopy(name: String, json: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { writeAtomically(File(base, name)) { it.write(json.toByteArray(Charsets.UTF_8)) } }
            .isSuccess
    }

    /**
     * When the last automatic backup was taken, and what was in it.
     *
     * Stored beside the session rather than inside it: this is bookkeeping about backups,
     * not about where he was, and the two are written for different reasons.
     */
    fun lastAutoAt(): Long = prefs.getLong("lastAutoAt", 0L)

    fun lastAutoFingerprint(): String = prefs.getString("lastAutoFingerprint", "") ?: ""

    suspend fun noteAutoBackup(at: Long, fingerprint: String) {
        prefs.edit().putLong("lastAutoAt", at).putString("lastAutoFingerprint", fingerprint)
            .apply()
    }

    /**
     * Bookkeeping, not survey data.
     *
     * Deliberately not in the booths folder and not in the index: these two values say
     * when the app last wrote a copy of itself, which is a fact about the phone and not
     * about anybody's roll.
     */
    private val prefs by lazy {
        context.getSharedPreferences("booth-survey-state", Context.MODE_PRIVATE)
    }

    /**
     * A whole booth, and the shared lists, as one file.
     *
     * Everything that would be lost if the phone were lost: the people, who has been
     * visited, who has been skipped, every family and its order, and the caste and address
     * words typed along the way.
     *
     * Not a new format -- the booth goes in exactly as writeBooth writes it, so a backup
     * restored years later goes through one parser, not two that have drifted apart.
     */
    fun backupJson(booth: Booth, lists: Lists): String = JSONObject().apply {
        put("kind", "booth-survey-backup")
        put("version", VERSION)
        put("savedAt", System.currentTimeMillis())
        put("booth", JSONObject(boothJson(booth)))
        put("lists", JSONObject().apply {
            put("castes", JSONArray(lists.castes))
            put("subCastes", JSONArray(lists.subCastes))
            put("addresses", JSONArray(lists.addresses))
            put("parties", JSONArray(lists.parties))
        })
    }.toString()

    /** A backup read back, or null if it is not one. */
    fun readBackup(text: String): Backup? = runCatching {
        val o = JSONObject(text)
        if (o.optString("kind") != "booth-survey-backup") return null
        val booth = boothFromJson(o.getString("booth")) ?: return null
        val l = o.optJSONObject("lists") ?: JSONObject()
        fun arr(k: String): List<String> {
            val a = l.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        }
        Backup(
            booth = booth,
            lists = Lists(
                castes = arr("castes").ifEmpty { Lists.DEFAULT_CASTES },
                subCastes = arr("subCastes"),
                addresses = arr("addresses"),
                parties = arr("parties"),
            ),
            savedAt = o.optLong("savedAt"),
        )
    }.getOrNull()

    /**
     * A booth as text, and back again.
     *
     * Exactly the form the app keeps on disk -- the same writer the file itself uses -- so
     * a backup is a copy of the real thing rather than a second format that can drift from
     * it. Both the undo snapshot and the backup file go through here.
     */
    fun boothJson(booth: Booth): String = writeBooth(booth)

    fun boothFromJson(text: String): Booth? =
        runCatching { parseBooth(JSONObject(text)) }.getOrNull()

    private fun writeBooth(booth: Booth): String = JSONObject().apply {
        put("version", VERSION)
        put("id", booth.id)
        put("boothNo", booth.boothNo)
        put("mandal", booth.mandal)
        put("townWard", booth.townWard)
        put("label", booth.label)
        put("importedAt", booth.importedAt)
        put("voters", JSONArray().apply {
            for (v in booth.voters) {
                put(JSONObject().apply {
                    put("id", v.id)
                    put("serial", v.serial)
                    put("voterId", v.voterId)
                    put("name", v.name)
                    put("house", v.house)
                    put("phone", v.phone)
                    put("address", v.address)
                    put("caste", v.caste)
                    put("subCaste", v.subCaste)
                    put("party", v.party)
                    put("visited", v.visited)
                    put("skipped", v.skipped)
                    put("familyId", v.familyId ?: JSONObject.NULL)
                    put("familyOrder", v.familyOrder)
                    put("notInLatestFile", v.notInLatestFile)
                })
            }
        })
        put("families", JSONArray().apply {
            for (f in booth.families) put(JSONObject().apply {
                put("id", f.id)
                put("order", f.order)
            })
        })
    }.toString()

    private fun parseLists(o: JSONObject): Lists {
        fun arr(key: String, fallback: List<String>): List<String> =
            o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: fallback
        return Lists(
            castes = arr("castes", Lists.DEFAULT_CASTES),
            addresses = arr("addresses", emptyList()),
            parties = arr("parties", emptyList()),
        )
    }

    /**
     * Reads a JSON file, moving it aside if it will not parse.
     *
     * Returns null rather than throwing, so one damaged file costs you that file and not the
     * app. The damaged copy is kept under a timestamped name: it is the only thing left if
     * something went wrong, and deleting the evidence of a bug helps nobody.
     */
    private fun readJson(f: File): JSONObject? {
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return runCatching { JSONObject(text) }.getOrElse { e ->
            val kept = File(f.parentFile, "${f.nameWithoutExtension}-unreadable.json")
            runCatching { f.renameTo(kept) }
            Log.e(TAG, "${f.name} unreadable (${e.message}); kept as ${kept.name}", e)
            null
        }
    }

    private suspend fun writeAtomically(target: File, body: (OutputStream) -> Unit) =
        writeLock.withLock {
            target.parentFile?.mkdirs()
            // A unique temporary name as well as the lock. The lock is what makes this correct;
            // the unique name means a crash mid-write cannot leave a stray .tmp that a later
            // run trips over.
            val tmp = File(target.parentFile, "${target.name}.${java.util.UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    body(out)
                    out.flush()
                    out.fd.sync() // the rename is only atomic if the bytes are actually down
                }
                if (!tmp.renameTo(target)) {
                    // Should not happen in the same directory once writes are serialised, but a
                    // silent failure here would look exactly like a successful save right up
                    // until the app was closed.
                    tmp.copyTo(target, overwrite = true)
                    Log.w(TAG, "rename failed; fell back to copying over ${target.name}")
                }
            } finally {
                // Never leave a temporary file behind, whatever happened above. A failed write
                // that throws must not also leave litter for the next one to trip over.
                runCatching { tmp.delete() }
            }
        }

    companion object {
        private const val TAG = "VoterSurvey"
        private const val VERSION = 3

        /** Opens an asset stream. Only used by the tests, which have no assets. */
        fun openStream(context: Context, name: String): InputStream = context.assets.open(name)
    }
}

/**
 * A string field, treating an absent key, a JSON null and an empty string all the same.
 *
 * `JSONObject.optString` does not do this. For a key whose value is JSON null it returns the
 * four-letter string "null", because that is what `JSONObject.NULL.toString()` produces. So a
 * plain `optString("familyId").ifBlank { null }` reads a JSON null as the family id "null",
 * which put all 1129 seeded voters into a family called "null" that does not exist -- and
 * since the export prints families from the family list, every one of them disappeared from
 * the sheet while the app still showed 1129 on screen.
 */
internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }
