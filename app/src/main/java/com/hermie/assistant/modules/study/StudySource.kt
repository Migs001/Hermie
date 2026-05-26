package com.hermie.assistant.modules.study

/**
 * Represents a chunk of study material ready for LLM fact extraction.
 */
data class StudyChunk(
    val text: String,
    val sourceTitle: String,
    val chunkIndex: Int,
    val totalChunks: Int
)

/**
 * Represents a study source (PDF or Wikipedia article).
 */
sealed class StudySource {
    abstract val title: String

    data class Pdf(
        override val title: String,
        val filePath: String
    ) : StudySource()

    data class Wikipedia(
        override val title: String,
        val articleTitle: String
    ) : StudySource()
}

/**
 * A single atomic fact extracted by the brain LLM from study material.
 */
data class StudyFact(
    val text: String,
    val sourceTitle: String,
    val chunkIndex: Int
)

/**
 * An item queued for study during sleep mode.
 */
sealed class QueuedStudyItem {
    abstract val displayName: String

    data class Wikipedia(val title: String) : QueuedStudyItem() {
        override val displayName get() = "Wikipedia: $title"
    }

    data class Pdf(val uri: android.net.Uri, val fileName: String) : QueuedStudyItem() {
        override val displayName get() = "PDF: $fileName"
    }
}
