package io.rulesassistant.bgra.domain

import java.time.Instant
import java.util.UUID

/**
 * Lifecycle of a rulebook upload. Ingestion is asynchronous, so the frontend
 * polls the game until it reaches [READY] or [FAILED].
 */
enum class GameStatus {
    /** Images stored, waiting for a worker slot. */
    PENDING,

    /** Vision model is reading the pages one by one. */
    TRANSCRIBING,

    /** All pages read, the text model is writing the Hungarian summary. */
    SUMMARIZING,

    /** Summary available; the game can be played back and asked about. */
    READY,

    FAILED,
    ;

    val isTerminal: Boolean get() = this == READY || this == FAILED
}

data class Game(
    val id: UUID,
    val title: String,
    val status: GameStatus,
    val statusDetail: String? = null,
    val errorMessage: String? = null,
    val pageCount: Int = 0,
    val pagesProcessed: Int = 0,
    val summary: String? = null,
    val hasCover: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class GamePage(
    val id: UUID,
    val gameId: UUID,
    val pageNumber: Int,
    val imageMime: String,
    val transcript: String?,
    val printedPageNumber: Int? = null,
)

/** A page together with its image bytes. Only loaded when the bytes are needed. */
data class GamePageImage(
    val pageNumber: Int,
    val imageMime: String,
    val imageData: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = pageNumber
}

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: UUID,
    val gameId: UUID,
    val role: ChatRole,
    val content: String,
    val createdAt: Instant,
)

data class StoredAudio(
    val summaryHash: String,
    val mimeType: String,
    val audioData: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = summaryHash.hashCode()
}
