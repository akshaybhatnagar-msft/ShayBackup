package com.shay.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * Builds compact JPEG previews from local content URIs.
 * - Images: BitmapFactory with inSampleSize to keep peak memory low, then scale to MAX_DIM,
 *   then apply EXIF rotation so the thumb is right-side-up.
 * - Videos: MediaMetadataRetriever pulls the first sync frame.
 *
 * Output target: ≤ 800 px on the longest edge, JPEG quality 80. Typical size 30–80 KB.
 */
object ThumbnailGenerator {

    private const val MAX_DIM = 800
    private const val JPEG_QUALITY = 80

    /** Returns JPEG bytes, or null on any failure. Never throws. */
    fun generate(context: Context, uri: Uri, isVideo: Boolean): ByteArray? = try {
        val bmp = if (isVideo) loadVideoFrame(context, uri) else loadImage(context, uri)
        bmp?.let { encode(it) }
    } catch (_: Throwable) { null }

    private fun loadImage(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        // Pass 1: read bounds only.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // Pass 2: decode at a sample size that keeps us near 2× the target.
        val sample = calcInSampleSize(srcW, srcH, MAX_DIM)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // Pass 3: scale down to MAX_DIM, then orient by EXIF.
        val scaled = scaleToMaxDim(decoded, MAX_DIM)
        if (scaled !== decoded) decoded.recycle()
        return applyExifRotation(resolver, uri, scaled)
    }

    private fun loadVideoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(100_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            frame?.let { scaleToMaxDim(it, MAX_DIM).also { s -> if (s !== it) it.recycle() } }
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun encode(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun calcInSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        val longest = maxOf(w, h)
        while ((longest / sample) > maxDim * 2) sample *= 2
        return sample
    }

    private fun scaleToMaxDim(bmp: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val ratio = maxDim.toFloat() / longest.toFloat()
        val newW = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val newH = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, newW, newH, true)
    }

    private fun applyExifRotation(
        resolver: android.content.ContentResolver,
        uri: Uri,
        bmp: Bitmap
    ): Bitmap {
        val degrees = try {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Throwable) { 0f }

        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }
}
