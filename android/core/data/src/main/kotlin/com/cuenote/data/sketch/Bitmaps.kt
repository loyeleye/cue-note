package com.cuenote.data.sketch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.cuenote.sketch.Raster
import java.io.File
import kotlin.math.max

/** Moving photos between JPEG files, Android bitmaps and the plain [Raster] the clean-up works on. */
object Bitmaps {

    /**
     * Reads a JPEG upright (cameras save sideways and note the turn in EXIF),
     * shrunk by halves until its long side is at most [maxSide].
     */
    fun loadUpright(file: File, maxSide: Int = 3200): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("could not read ${file.name}")
        val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
            .also { if (it !== bmp) bmp.recycle() }
    }

    fun toRaster(b: Bitmap): Raster {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Raster(b.width, b.height, px)
    }

    fun fromRaster(r: Raster): Bitmap = Bitmap.createBitmap(r.pixels, r.width, r.height, Bitmap.Config.ARGB_8888)

    fun saveJpeg(b: Bitmap, file: File, quality: Int = 88) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
