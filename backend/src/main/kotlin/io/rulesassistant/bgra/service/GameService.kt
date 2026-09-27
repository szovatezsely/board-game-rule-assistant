package io.rulesassistant.bgra.service

import io.rulesassistant.bgra.config.IngestionProperties
import io.rulesassistant.bgra.domain.Game
import io.rulesassistant.bgra.domain.GameStatus
import io.rulesassistant.bgra.repository.AudioRepository
import io.rulesassistant.bgra.repository.ChatRepository
import io.rulesassistant.bgra.repository.GameRepository
import io.rulesassistant.bgra.web.dto.PageResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

class GameNotFoundException(id: UUID) :
    RuntimeException("Nem található társasjáték ezzel az azonosítóval: $id")

class InvalidUploadException(message: String) : RuntimeException(message)

/** An uploaded page, already normalised, ready to be stored. */
private data class PreparedPage(val bytes: ByteArray, val mime: String)

@Service
class GameService(
    private val games: GameRepository,
    private val chats: ChatRepository,
    private val audio: AudioRepository,
    private val images: ImagePreprocessor,
    private val ingestion: RulebookIngestionService,
    private val properties: IngestionProperties,
    private val transactions: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Stands in until the pipeline reads the real name off the pages. */
        const val PLACEHOLDER_TITLE = "Feldolgozás alatt…"
    }

    fun listGames(): List<Game> = games.findAll()

    fun getGame(id: UUID): Game = games.findById(id) ?: throw GameNotFoundException(id)

    fun findCover(id: UUID): Pair<ByteArray, String>? = games.findCover(id)

    /**
     * Per-page transcription state for the detail view. Only the transcript
     * length is exposed — the full text is model context, not UI content.
     */
    fun pageStates(id: UUID): List<PageResponse> {
        getGame(id)
        return games.findPages(id).map { page ->
            PageResponse(
                pageNumber = page.pageNumber,
                transcribed = !page.transcript.isNullOrBlank(),
                characterCount = page.transcript?.length ?: 0,
                printedPageNumber = page.printedPageNumber,
            )
        }
    }

    /**
     * Stores the uploaded pages and hands the rulebook to the background pipeline.
     *
     * Validation and image normalisation happen synchronously so the user learns
     * about an unreadable file immediately; only the Groq work is deferred.
     *
     * The write runs inside an explicit [TransactionTemplate] rather than an
     * `@Transactional` annotation because the background worker must not be
     * dispatched until the commit is visible to its own connection — an `@Async`
     * call made from inside the transaction could otherwise start first and find
     * no pages.
     */
    fun createGame(title: String?, files: List<MultipartFile>): Game {
        val stored = transactions.execute { storeUpload(title, files) }
            ?: error("Upload transaction produced no result")

        ingestion.ingestAsync(stored.gameId, inferTitle = stored.titleWasInferred)
        return getGame(stored.gameId)
    }

    private data class StoredUpload(val gameId: UUID, val titleWasInferred: Boolean)

    private fun storeUpload(title: String?, files: List<MultipartFile>): StoredUpload {
        val usable = files.filter { !it.isEmpty }
        if (usable.isEmpty()) {
            throw InvalidUploadException("Legalább egy képet fel kell tölteni a szabálykönyvből.")
        }
        if (usable.size > properties.maxPagesPerGame) {
            throw InvalidUploadException(
                "Egyszerre legfeljebb ${properties.maxPagesPerGame} oldal tölthető fel, " +
                    "most ${usable.size} kép érkezett.",
            )
        }

        // Kept in the order they were submitted. The upload form sorts by name
        // naturally (2.jpg before 10.jpg) and lets the user reorder, so the
        // submission order is the user's decision; a plain filename sort here
        // used to undo that by putting 10.jpg before 2.jpg. Pages photographed
        // out of order are still put right later, by their printed numbers.
        val ordered = usable

        val prepared = ordered.map { file ->
            val bytes = file.bytes
            images.validateDecodable(bytes, file.originalFilename ?: "kép")
            PreparedPage(images.prepareForVision(bytes), "image/jpeg")
        }

        val providedTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val gameId = UUID.randomUUID()
        val game = games.insert(
            id = gameId,
            title = providedTitle ?: PLACEHOLDER_TITLE,
            pageCount = prepared.size,
        )

        prepared.forEachIndexed { index, page ->
            games.insertPage(gameId, index + 1, page.bytes, page.mime)
        }
        games.updateCover(gameId, images.createThumbnail(ordered.first().bytes), "image/jpeg")

        log.info("Stored {} pages for new game {} ('{}')", prepared.size, gameId, game.title)
        return StoredUpload(gameId, titleWasInferred = providedTitle == null)
    }

    /** Re-runs ingestion for a failed game, reusing every transcript already paid for. */
    fun retryIngestion(id: UUID): Game {
        val game = getGame(id)
        if (!game.status.isTerminal) {
            throw InvalidUploadException("A feldolgozás már folyamatban van.")
        }
        games.updateStatus(id, GameStatus.PENDING, "Újrafeldolgozásra vár", errorMessage = null)
        // If the first attempt died before naming the game, the placeholder is
        // still showing, so the retry has to try again — otherwise a game that
        // failed once keeps "Feldolgozás alatt…" as its title forever.
        ingestion.ingestAsync(id, inferTitle = game.title == PLACEHOLDER_TITLE)
        return getGame(id)
    }

    /**
     * Re-reads every page from its stored image and rebuilds the summary.
     *
     * Unlike [retryIngestion], this deliberately throws the existing transcripts
     * away. Transcription quality depends on the prompt, and when that improves
     * — reading order on multi-column spreads, for instance — the only way to
     * benefit is to read the pages again. Costs one Groq call per page, so it is
     * an explicit user action rather than something automatic.
     */
    fun rescan(id: UUID): Game {
        val game = getGame(id)
        if (!game.status.isTerminal) {
            throw InvalidUploadException("A feldolgozás már folyamatban van.")
        }
        val cleared = games.clearTranscripts(id)
        audio.delete(id)
        games.updateProgress(id, pagesProcessed = 0, detail = "Újraolvasásra vár")
        games.updateStatus(id, GameStatus.PENDING, "Újraolvasásra vár", errorMessage = null)
        log.info("Cleared {} transcripts for game {} - full re-scan queued", cleared, id)
        ingestion.ingestAsync(id, inferTitle = game.title == PLACEHOLDER_TITLE)
        return getGame(id)
    }

    /**
     * Rewrites the summary from the stored transcripts. No pages are re-read, so
     * this costs one text call and invalidates the cached narration.
     */
    fun regenerateSummary(id: UUID): Game {
        val game = getGame(id)
        if (game.status != GameStatus.READY && game.status != GameStatus.FAILED) {
            throw InvalidUploadException("A feldolgozás még folyamatban van.")
        }
        audio.delete(id)
        games.updateStatus(id, GameStatus.PENDING, "Összefoglaló újragenerálása", errorMessage = null)
        ingestion.ingestAsync(id, inferTitle = game.title == PLACEHOLDER_TITLE)
        return getGame(id)
    }

    // A single DELETE cascades to pages, chat and audio, so it is already atomic.
    fun deleteGame(id: UUID) {
        // chat_message, game_page and game_audio all cascade from game.
        if (!games.delete(id)) throw GameNotFoundException(id)
        log.info("Deleted game {}", id)
    }

    fun clearChat(id: UUID) {
        getGame(id)
        val removed = chats.clearHistory(id)
        log.info("Cleared {} chat messages for game {}", removed, id)
    }
}
