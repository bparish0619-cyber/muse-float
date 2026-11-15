package net.northbrand.musefloat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * The user's bubble faces: images the user picks on the settings screen,
 * stored as PNGs in the app's private files directory.
 *
 * There are two slots, day and night, matching the bubble's clock-based
 * face swap (day from 07:00, night after 19:00). If only one slot is set,
 * that face is used around the clock. If neither is set, callers fall
 * back to the bundled neutral default (R.drawable.face_default), so the
 * bubble always has something to show.
 *
 * Picked images are copied in at pick time (decoded, center-cropped to a
 * square, scaled down), so the app never needs ongoing access to the
 * user's photo library and nothing ever leaves the device.
 */
object Faces {

    // CHANGE-ME: stored face resolution. 512px is plenty for a bubble
    // that tops out at 120dp, and keeps the files tiny.
    private const val SIZE_PX = 512

    private const val DIR = "faces"
    private const val DAY_FILE = "day.png"
    private const val NIGHT_FILE = "night.png"

    private fun file(context: Context, night: Boolean): File =
        File(File(context.filesDir, DIR), if (night) NIGHT_FILE else DAY_FILE)

    /** True when the given slot has a user-picked face. */
    fun has(context: Context, night: Boolean): Boolean = file(context, night).isFile

    /**
     * The bitmap for a slot, following the one-face fallback (night asks
     * fall back to the day face and the other way around). Null when the
     * user has not picked any face at all.
     */
    fun bitmap(context: Context, night: Boolean): Bitmap? {
        val primary = file(context, night)
        val fallback = file(context, !night)
        val chosen = when {
            primary.isFile -> primary
            fallback.isFile -> fallback
            else -> return null
        }
        return try {
            BitmapFactory.decodeFile(chosen.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /** The slot's bitmap, or the bundled default face when none is set. */
    fun bitmapOrDefault(context: Context, night: Boolean): Bitmap =
        bitmap(context, night)
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.face_default)

    /**
     * A change stamp for a slot: which file backs it and when that file
     * last changed. The floating service compares stamps to know when to
     * reload the bubble image (slot flip at 07:00 / 19:00, a new pick, or
     * a reset), without decoding the bitmap on every check.
     */
    fun stamp(context: Context, night: Boolean): String {
        val primary = file(context, night)
        val fallback = file(context, !night)
        val chosen = when {
            primary.isFile -> primary
            fallback.isFile -> fallback
            else -> return if (night) "night:default" else "day:default"
        }
        return "${chosen.name}:${chosen.lastModified()}"
    }

    /**
     * Copies a picked image into a slot: decode it from the content URI,
     * center-crop to a square, scale to SIZE_PX, and save as PNG.
     * Returns false when the image cannot be read.
     */
    fun save(context: Context, night: Boolean, uri: Uri): Boolean {
        return try {
            val source = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return false
            val side = min(source.width, source.height)
            val cropped = Bitmap.createBitmap(
                source,
                (source.width - side) / 2,
                (source.height - side) / 2,
                side,
                side
            )
            val scaled = Bitmap.createScaledBitmap(cropped, SIZE_PX, SIZE_PX, true)
            val target = file(context, night)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Removes one slot's picked face (the default takes over again). */
    fun clear(context: Context, night: Boolean) {
        try {
            file(context, night).delete()
        } catch (_: Exception) {
            // Already gone; nothing to do.
        }
    }

    /** Removes both picked faces. */
    fun clearAll(context: Context) {
        clear(context, night = false)
        clear(context, night = true)
    }
}
