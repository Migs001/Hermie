package com.hermie.assistant.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Shared image decoding utilities for vision model input.
 * Handles both content:// URIs and file paths.
 */
object ImageUtils {

    private const val MAX_DIM = 768

    /**
     * Decode an image to raw RGB bytes for vision models.
     * Scales down large images to max 768px on longest side.
     *
     * @param path File path or content:// URI string
     * @param context Android context (needed for content:// URIs)
     * @return Triple of (RGB bytes, width, height)
     */
    fun decodeImageToRgb(path: String, context: Context): Triple<ByteArray, Int, Int> {
        val isContentUri = path.startsWith("content://")

        // Step 1: Get dimensions
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (isContentUri) {
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
        } else {
            BitmapFactory.decodeFile(path, boundsOpts)
        }

        // Step 2: Calculate sample size to stay under MAX_DIM
        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > MAX_DIM || boundsOpts.outHeight / sampleSize > MAX_DIM) {
            sampleSize *= 2
        }

        // Step 3: Decode actual bitmap
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = if (isContentUri) {
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } else {
            BitmapFactory.decodeFile(path, decodeOpts)
        } ?: throw IllegalStateException("Failed to decode image: $path")

        val w = bitmap.width
        val h = bitmap.height

        // Step 4: Convert ARGB to RGB byte array
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        val rgb = ByteArray(w * h * 3)
        for (i in pixels.indices) {
            val px = pixels[i]
            rgb[i * 3]     = ((px shr 16) and 0xFF).toByte() // R
            rgb[i * 3 + 1] = ((px shr 8)  and 0xFF).toByte() // G
            rgb[i * 3 + 2] = (px and 0xFF).toByte()           // B
        }

        return Triple(rgb, w, h)
    }

    /**
     * Save a content:// URI image to a file, downscaled to 768px with 80% JPEG quality.
     *
     * @return Absolute path to the saved file
     */
    fun saveImageToFile(uri: String, targetFile: File, context: Context): String {
        val (_, _, _) = decodeImageToRgb(uri, context) // validate decodability

        // Re-decode as bitmap for saving
        val isContentUri = uri.startsWith("content://")
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (isContentUri) {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
        } else {
            BitmapFactory.decodeFile(uri, boundsOpts)
        }

        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > MAX_DIM || boundsOpts.outHeight / sampleSize > MAX_DIM) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = if (isContentUri) {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } else {
            BitmapFactory.decodeFile(uri, decodeOpts)
        } ?: throw IllegalStateException("Failed to decode image for saving: $uri")

        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        bitmap.recycle()

        return targetFile.absolutePath
    }
}
