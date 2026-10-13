package com.kraft.voterlist.io

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/**
 * Where the automatic backup lands, with no dialog and no permission prompt.
 *
 * The manual backup goes wherever he says, through the system save dialog. An automatic
 * one cannot ask -- he is not there when it runs -- so it needs a place it can reach on
 * its own: Downloads/Booth Survey/, which he can find with the same file manager he
 * already uses to get to the booklet.
 *
 * The oldest automatic backups in that folder are deleted once there are too many. Not
 * because the folder is precious, but because a folder that fills itself with copies is
 * one he stops trusting and one he eventually deletes without reading.
 */
object BackupFiles {
    private const val TAG = "VoterSurvey"
    private const val FOLDER = "Booth Survey"
    private const val KEEP = 20

    /** Writes the file. Returns whether it got there. */
    fun write(json: String, name: String): Boolean {
        val resolver = appContext?.contentResolver ?: return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                }
            }
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: return false
            prune(appContext ?: return true)
            true
        } catch (e: Exception) {
            // A backup that cannot be written is not a failure the app should shout about.
            // He did not ask for it, and the manual one is still there.
            Log.w(TAG, "automatic backup failed: ${e.message}")
            false
        }
    }

    /** How many the app has written and kept. */
    fun count(ctx: Context): Int = list(ctx).size

    /** The newest automatic backup, as "13 Oct, 16:30", or null if there is none. */
    fun lastSaved(ctx: Context): String? =
        list(ctx).firstOrNull()?.let {
            val fmt = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
            fmt.format(java.util.Date(it))
        }

    /**
     * Backup timestamps, newest first, in milliseconds.
     *
     * MediaStore reports DATE_MODIFIED in *seconds* since the epoch. Handing those straight
     * to java.util.Date, which wants milliseconds, dates every backup to January 1970 --
     * which is what it did until this was noticed on the phone.
     */
    private fun list(ctx: Context): List<Long> = try {
        val projection = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$FOLDER%")
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, where, args,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            buildList { while (c.moveToNext()) add(c.getLong(idx) * 1000L) }
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "could not list backups: ${e.message}")
        emptyList()
    }

    private fun prune(ctx: Context) {
        val ctx2 = ctx
        val old = list(ctx2)
        if (old.size <= KEEP) return
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            ctx2.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%$FOLDER%"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                var i = 0
                while (c.moveToNext()) {
                    i++
                    if (i <= KEEP) continue
                    ctx2.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns._ID} = ?",
                        arrayOf(c.getLong(idIdx).toString()),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not prune backups: ${e.message}")
        }
    }

    /**
     * The app's own context, set once at startup.
     *
     * A static helper has no context of its own and the backup runs from a background
     * coroutine with no activity to borrow one from.
     */
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }
}
