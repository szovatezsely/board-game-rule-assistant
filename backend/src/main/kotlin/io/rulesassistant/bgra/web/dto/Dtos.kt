package io.rulesassistant.bgra.web.dto

import io.rulesassistant.bgra.domain.ChatMessage
import io.rulesassistant.bgra.domain.Game
import io.rulesassistant.bgra.domain.GameStatus
import java.time.Instant
import java.util.UUID

/** Library card view: everything the grid needs, and nothing heavy. */
data class GameSummaryResponse(
    val id: UUID,
    val title: String,
    val status: GameStatus,
    val statusDetail: String?,
    val errorMessage: String?,
    val pageCount: Int,
    val pagesProcessed: Int,
    val progressPercent: Int,
    val hasSummary: Boolean,
    val hasCover: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(game: Game) = GameSummaryResponse(
            id = game.id,
            title = game.title,
            status = game.status,
            statusDetail = game.statusDetail,
            errorMessage = game.errorMessage,
            pageCount = game.pageCount,
            pagesProcessed = game.pagesProcessed,
            progressPercent = progressOf(game),
            hasSummary = !game.summary.isNullOrBlank(),
            hasCover = game.hasCover,
            createdAt = game.createdAt,
        )

        /**
         * Transcription is the long pole, so it owns 90% of the bar and the
         * summary write-up the last 10%. Without this the bar would sit at 100%
         * for the whole summarisation step.
         */
        fun progressOf(game: Game): Int = when (game.status) {
            GameStatus.PENDING -> 0
            GameStatus.TRANSCRIBING ->
                if (game.pageCount == 0) 0
                else (game.pagesProcessed * 90 / game.pageCount).coerceIn(0, 90)
            GameStatus.SUMMARIZING -> 92
            GameStatus.READY -> 100
            GameStatus.FAILED -> 0
        }
    }
}

/** Detail view, adding the summary text and the per-page transcription state. */
data class GameDetailResponse(
    val id: UUID,
    val title: String,
    val status: GameStatus,
    val statusDetail: String?,
    val errorMessage: String?,
    val pageCount: Int,
    val pagesProcessed: Int,
    val progressPercent: Int,
    val summary: String?,
    val hasCover: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pages: List<PageResponse>,
) {
    companion object {
        fun from(game: Game, pages: List<PageResponse>) = GameDetailResponse(
            id = game.id,
            title = game.title,
            status = game.status,
            statusDetail = game.statusDetail,
            errorMessage = game.errorMessage,
            pageCount = game.pageCount,
            pagesProcessed = game.pagesProcessed,
            progressPercent = GameSummaryResponse.progressOf(game),
            summary = game.summary,
            hasCover = game.hasCover,
            createdAt = game.createdAt,
            updatedAt = game.updatedAt,
            pages = pages,
        )
    }
}

data class PageResponse(
    val pageNumber: Int,
    val transcribed: Boolean,
    val characterCount: Int,
    /** Page number printed in the book, when the vision model could read one. */
    val printedPageNumber: Int? = null,
)

data class ChatMessageResponse(
    val id: UUID,
    val role: String,
    val content: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(message: ChatMessage) = ChatMessageResponse(
            id = message.id,
            role = message.role.name.lowercase(),
            content = message.content,
            createdAt = message.createdAt,
        )
    }
}

data class AskRequest(val question: String = "")

data class ErrorResponse(val message: String, val code: String)

/**
 * Surfaced on the home page so a missing API key or an unusable model is
 * obvious before the user spends time photographing and uploading a rulebook.
 */
data class ServiceStatusResponse(
    val groqConfigured: Boolean,
    val visionModel: String,
    val textModel: String,
    /** False when the configured models were checked and found unusable. */
    val modelsHealthy: Boolean,
    /** Ready-to-display Hungarian description of the misconfiguration, if any. */
    val modelProblem: String?,
    /** Model ids on this account that accept image input. */
    val imageCapableModels: List<String>,
)
