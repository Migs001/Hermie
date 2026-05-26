package com.hermie.assistant.modules.study

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream

/**
 * Extracts plain text from PDF files using Android's PdfRenderer (basic)
 * or falls back to a simple text-layer extraction.
 *
 * For full-featured extraction, pdfbox-android is used when available.
 */
object PdfTextExtractor {

    private const val TAG = "PdfTextExtractor"

    /**
     * Extract all text from a PDF file via content URI.
     * Uses PdfBox-Android for reliable text extraction.
     *
     * @return The full text content, or null on failure.
     */
    fun extractText(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                extractFromStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract PDF text from URI", e)
            null
        }
    }

    /**
     * Extract text from a PDF file path.
     */
    fun extractText(filePath: String): String? {
        return try {
            java.io.File(filePath).inputStream().use { stream ->
                extractFromStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract PDF text from path: $filePath", e)
            null
        }
    }

    /**
     * Core extraction using PdfBox-Android.
     */
    private fun extractFromStream(stream: InputStream): String? {
        return try {
            val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(stream)
            try {
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                val text = stripper.getText(document)
                if (text.isNullOrBlank()) null else text.trim()
            } finally {
                document.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "PdfBox extraction failed", e)
            null
        }
    }
}
