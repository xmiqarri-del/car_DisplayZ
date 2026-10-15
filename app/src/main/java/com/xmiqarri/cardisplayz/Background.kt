package com.xmiqarri.cardisplayz

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * The picture the user chose as the app background. A copy is kept in the app's own storage,
 * so the picture keeps working even if the original file is moved or deleted.
 */
object AppBackground {

    private const val FILE_NAME = "background.jpg"

    /** Longest side of the stored copy, in pixels. Larger pictures are scaled down to save memory. */
    private const val MAX_SIDE = 1920

    /** Dark layer over the picture so the text and cards stay readable. */
    private const val SCRIM = 0xA6000000.toInt()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun isSet(context: Context): Boolean = file(context).exists()

    /** Copies the picture at [uri] into the app's storage. Blocking: run it off the main thread. */
    fun save(context: Context, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return false

            FileOutputStream(file(context)).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun remove(context: Context) {
        file(context).delete()
    }

    /** The picture with a dark layer over it, or null when no picture is set. */
    fun drawable(context: Context): Drawable? {
        val f = file(context)
        if (!f.exists()) return null
        val bitmap = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        return LayerDrawable(arrayOf(BitmapDrawable(context.resources, bitmap), ColorDrawable(SCRIM)))
    }
}
