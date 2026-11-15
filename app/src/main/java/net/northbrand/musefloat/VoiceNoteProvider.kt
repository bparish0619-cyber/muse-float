package net.northbrand.musefloat

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves recorded voice notes to the share chooser's chosen app.
 *
 * This is the smallest possible stand-in for androidx FileProvider (the
 * app deliberately has no libraries): it is not exported, and the only
 * way in is the temporary per-URI read grant that FloatingService puts
 * on the ACTION_SEND intent. It serves single files out of the app's own
 * cacheDir, read-only, and nothing else.
 */
class VoiceNoteProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val requested = uri.lastPathSegment ?: throw FileNotFoundException("No file name")
        // Strip any path parts: only bare file names from cacheDir.
        val file = File(ctx.cacheDir, File(requested).name)
        if (!file.isFile) throw FileNotFoundException(requested)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "audio/mp4"

    // No queryable data: files are reached only through openFile above.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        // Must match the authorities value in AndroidManifest.xml.
        const val AUTHORITY = "net.northbrand.musefloat.voicenotes"

        fun uriFor(file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(file.name)
            .build()
    }
}
