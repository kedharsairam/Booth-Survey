package com.kraft.voterlist.io

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Where the automatic backup lands, with no dialog and no permission prompt.
 *
 * The manual backup goes wherever he says, through the system save dialog. An automatic one
 * cannot ask -- he is not there when it runs -- so it needs a place it can reach on its own.
 *
 * On Android 10 and later that is Downloads/Booth Survey/, which he can find with the same
 * file manager he already uses to get to the booklet. Below 10 there is no MediaStore
 * Downloads collection to write to, so the copy goes to the app's own external folder
 * instead: still automatic, still no permission, and nothing crashes on an older phone.
 *
 * The oldest automatic backups are deleted once there are too many. Not because the folder
 * is precious, but because a folder that fills itself with copies is one he stops trusting
 * and one he eventually deletes without reading.
 */
object BackupFiles {
    private const val TAG = "VoterSurvey"
    private const val FOLDER = "Booth Survey"
    private const val KEEP = 20

    /**
     * MediaStore gained a Downloads collection in Android 10.
     *
     * A function rather than a property on purpose: lint follows a function call but not a
     * stored boolean, so with a property every use of MediaStore.Downloads below was flagged
     * as reachable on API 26 and the build failed -- correctly, since it cannot prove the
     * check is there.
     */
    private fun hasDownloads() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Writes the file. Returns whether it got there. */
    fun write(json: String, name: String): Boolean {
        val context = appContext ?: return false
        return try {
            if (hasDownloads()) writeViaMediaStore(context, json, name)
            else writeViaOwnFolder(context, json, name)
            prune(context)
            true
        } catch (e: Exception) {
            // A backup that cannot be written is not a failure the app should shout about.
            // He did not ask for it, and the manual one is still there.
            Log.w(TAG, "automatic backup failed: ${e.message}")
            false
        }
    }

    /** How many the app has written and kept. */
    fun count(ctx: Context): Int = newestFirst(ctx).size

    /** The newest automatic backup, as "13 Oct, 16:30", or null if there is none. */
    fun lastSaved(ctx: Context): String? =
        newestFirst(ctx).firstOrNull()?.let {
            val fmt = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
            fmt.format(it)
        }

    // --- where the files actually are ---------------------------------------

        @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(context: Context, json: String, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER",
            )
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: return false
        return true
    }

    private fun ownFolder(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER)
            .also { it.mkdirs() }

    private fun writeViaOwnFolder(context: Context, json: String, name: String): Boolean {
        val target = File(ownFolder(context), name)
        target.writeText(json)
        return true
    }

    /**
     * Every automatic backup, newest first, as timestamps.
     *
     * MediaStore reports DATE_MODIFIED in *seconds*; java.util.Date wants milliseconds.
     * Handing seconds straight to it dated every backup to January 1970, which is what it
     * did until this was noticed on the phone.
     */
    private fun newestFirst(ctx: Context): List<Long> = try {
        if (hasDownloads()) viaMediaStore(ctx) else viaOwnFolder(ctx)
    } catch (e: Exception) {
        Log.w(TAG, "could not list backups: ${e.message}")
        emptyList()
    }

        @RequiresApi(Build.VERSION_CODES.Q)
    private fun viaMediaStore(ctx: Context): List<Long> {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED)
        val out = mutableListOf<Pair<Long, Uri>>()
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%$FOLDER%"),
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                out += c.getLong(dateIdx) * 1000L to
                    Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idIdx).toString())
            }
        }
        out.sortByDescending { it.first }
        return out.map { it.first }
    }

    private fun viaOwnFolder(ctx: Context): List<Long> =
        ownFolder(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.lastModified() }
            ?: emptyList()

    private fun prune(ctx: Context) {
        try {
            if (hasDownloads()) pruneMediaStore(ctx) else pruneOwnFolder(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "could not prune backups: ${e.message}")
        }
    }

        @RequiresApi(Build.VERSION_CODES.Q)
    private fun pruneMediaStore(ctx: Context) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED)
        var kept = 0
        val doomed = mutableListOf<String>()
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%$FOLDER%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            while (c.moveToNext()) {
                kept++
                if (kept > KEEP) doomed += c.getLong(idIdx).toString()
            }
        }
        for (id in doomed) {
            ctx.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns._ID} = ?",
                arrayOf(id),
            )
        }
    }

    private fun pruneOwnFolder(ctx: Context) {
        val files = ownFolder(ctx).listFiles()?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(KEEP).forEach { it.delete() }
    }

    /**
     * The app's own context, set once at startup.
     *
     * A static helper has no context of its own, and the backup runs from a background
     * coroutine with no activity to borrow one from.
     */
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }
}