package io.rulesassistant.bgra.service

import io.rulesassistant.bgra.config.IngestionProperties
import io.rulesassistant.bgra.domain.GameStatus
import io.rulesassistant.bgra.gemini.GeminiClient
import io.rulesassistant.bgra.groq.GroqClient
import io.rulesassistant.bgra.groq.GroqRequestTooLargeException
import io.rulesassistant.bgra.repository.GameRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a pile of uploaded page photos into a searchable, summarised rulebook.
 *
 * Runs on the single-threaded `ingestionExecutor`, so rulebooks are processed
 * one at a time: Groq's free tier meters each model per organisation, and two
 * rulebooks transcribing at once would only share the same allowance.
 *
 * With a Gemini key, the summary is written by Gemini in one call once all
 * pages are read. Without one (or when Gemini is unavailable), the Groq path is
 * used: for a long rulebook, notes for the first pages are written while later
 * pages are still being read. By default the Groq summary model is the same
 * Qwen model that reads the pages, so the two share one per-minute allowance;
 * the overlap then saves the wait at the end rather than total tokens.
 *
 * The pipeline is resumable. Every page transcript is committed as soon as it
 * arrives, and [transcribePages] only asks for pages that are still missing, so
 * a retry after a failure never re-pays for transcription already done.
 */
@Service
class RulebookIngestionService(
    private val games: GameRepository,
    private val groq: GroqClient,
    private val gemini: GeminiClient,
    private val properties: IngestionProperties,
    @Qualifier("summaryExecutor") private val summaryExecutor: Executor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("ingestionExecutor")
    fun ingestAsync(gameId: UUID, inferTitle: Boolean) {
        try {
            ingest(gameId, inferTitle)
        } catch (e: Exception) {
            // Async means nobody is waiting to catch this, so the failure has to
            // land in the database or the UI would spin forever.
            val cause = (e as? CompletionException)?.cause ?: e
            log.error("Ingestion failed for game {}", gameId, cause)
            games.updateStatus(
                id = gameId,
                status = GameStatus.FAILED,
                detail = null,
                errorMessage = cause.message ?: "Ismeretlen hiba a feldolgozás során.",
            )
        }
    }

    private fun ingest(gameId: UUID, inferTitle: Boolean) {
        val game = games.findById(gameId) ?: run {
            log.warn("Game {} vanished before ingestion started", gameId)
            return
        }
        log.info("Starting ingestion of '{}' ({} pages)", game.title, game.pageCount)

        val notes = NotesPipeline()
        try {
            transcribePages(gameId, game.pageCount, notes)

            val pages = games.loadRulebookPages(gameId, properties.maxContextChars)
            if (pages.isEmpty()) {
                throw IllegalStateException(
                    "A feltöltött képekből nem sikerült szöveget kiolvasni. " +
                        "Ellenőrizd, hogy a képek élesek és jól megvilágítottak-e.",
                )
            }
            logOrder(game.title, pages)

            if (inferTitle) {
                resolveTitle(gameId, RulebookText.join(pages))
            }

            games.updateStatus(gameId, GameStatus.SUMMARIZING, "Összefoglaló készítése")
            val title = games.findById(gameId)?.title ?: game.title
            val summary = SummaryFormatting.normaliseParagraphs(buildSummary(gameId, title, pages, notes))
            games.updateSummary(gameId, summary)
            games.updateStatus(gameId, GameStatus.READY, null)
            log.info("Ingestion of '{}' complete ({} characters of summary)", title, summary.length)
        } finally {
            notes.abandon()
        }
    }

    private fun logOrder(title: String, pages: List<RulebookPage>) {
        val reordered = pages.map { it.uploadNumber } != pages.map { it.uploadNumber }.sorted()
        if (reordered) {
            log.info(
                "Pages of '{}' reordered by printed page number: {}",
                title, pages.joinToString { "${it.uploadNumber}->${it.label}" },
            )
        }
    }

    /**
     * Writes the summary.
     *
     * A rulebook that fits in one request is summarised in one call. Anything
     * longer goes through the tagged notes the [NotesPipeline] has been writing
     * during transcription, and the sections are then written from those — each
     * section drawing on every slice that mentioned it, so the order the photos
     * arrived in does not decide the order of the explanation.
     */
    private fun buildSummary(
        gameId: UUID,
        title: String,
        pages: List<RulebookPage>,
        notes: NotesPipeline,
    ): String {
        val rulebook = RulebookText.join(pages)
        val targetWords = SummaryLength.targetWords(rulebook.length)

        if (gemini.isConfigured) {
            try {
                log.info("Summarising '{}' with Gemini {} (~{} words)", title, gemini.models, targetWords)
                games.updateStatus(gameId, GameStatus.SUMMARIZING, "Összefoglaló készítése (Gemini)")
                return gemini.summarise(title, rulebook.take(gemini.contextChars), targetWords)
            } catch (e: Exception) {
                // Gemini's free tier is regularly overloaded; the Groq path below
                // still produces a usable summary, so this is not a failure.
                log.warn("Gemini summary of '{}' failed, falling back to Groq: {}", title, e.message)
                games.updateStatus(gameId, GameStatus.SUMMARIZING, "Összefoglaló készítése (tartalék modell)")
            }
        }

        if (!notes.isSliced && rulebook.length <= groq.summaryContextChars(targetWords)) {
            try {
                log.info("Summarising '{}' in one call (~{} words)", title, targetWords)
                return groq.summarise(title, rulebook, targetWords)
            } catch (e: GroqRequestTooLargeException) {
                log.info("Single-call summary of '{}' did not fit ({}); slicing it", title, e.message)
            }
        }

        games.updateStatus(gameId, GameStatus.SUMMARIZING, "Összefoglaló készítése: jegyzetek")
        val merged = SummaryNotes.merge(notes.finish(pages))
        val jobs = SummaryPlanner.plan(merged, targetWords, groq::sectionContextChars)
        if (jobs.isEmpty()) {
            throw IllegalStateException("A szabálykönyvből nem sikerült összefoglalót készíteni.")
        }
        log.info(
            "Writing the summary of '{}' (~{} words) in {} call(s) from {} characters of notes",
            title, targetWords, jobs.size, merged.values.sumOf { it.length },
        )

        val wholeInOne = jobs.size == 1
        return jobs.mapIndexed { index, job ->
            games.updateStatus(
                gameId, GameStatus.SUMMARIZING,
                "Összefoglaló írása (${index + 1}/${jobs.size} rész)",
            )
            groq.writeSections(
                gameTitle = title,
                sectionNames = job.sections.map { it.heading },
                notes = job.notes,
                targetWords = job.targetWords,
                continuation = job.continuation,
                wholeSummary = wholeInOne,
            ).trim()
        }.joinToString("\n\n")
    }

    /**
     * Collects transcribed pages and, once the rulebook is known to be too long
     * for a single summary call, writes notes for full slices in the background.
     *
     * Slices are formed in upload order, because that is the order pages become
     * available; each slice is put in book order internally, and the finished
     * notes are sorted by where their pages sit in the book.
     */
    private inner class NotesPipeline {
        private val pending = mutableListOf<RulebookPage>()
        private val slices = mutableListOf<Pair<List<RulebookPage>, CompletableFuture<String>>>()
        private val abandoned = AtomicBoolean(false)
        private var totalChars = 0

        /** True once slicing has begun, i.e. the single-call path is ruled out. */
        var isSliced = false
            private set

        private val sliceLimit = groq.summaryNotesContextChars()

        fun offer(page: RulebookPage) {
            pending += page
            totalChars += page.formattedLength
            // With Gemini configured the whole book goes in one call, so notes
            // written now would be wasted; the Groq fallback slices afterwards.
            if (gemini.isConfigured) return
            if (!isSliced && totalChars > groq.summaryContextChars(SummaryLength.targetWords(totalChars))) {
                isSliced = true
                log.info("Rulebook exceeds the single-call summary budget; writing notes during transcription")
            }
            if (!isSliced) return
            while (pending.sumOf { it.formattedLength } > sliceLimit) {
                // Take whole pages up to the limit (always at least one; the
                // chunker splits a single oversized page into pieces).
                var size = 0
                var count = 0
                while (count < pending.size &&
                    (count == 0 || size + pending[count].formattedLength <= sliceLimit)
                ) {
                    size += pending[count].formattedLength
                    count++
                }
                val taken = pending.take(count)
                repeat(count) { pending.removeAt(0) }
                PageChunker.chunk(taken, sliceLimit).forEach(::submit)
            }
        }

        /** Flushes the remainder and waits for every slice's notes, in book order. */
        fun finish(bookOrder: List<RulebookPage>): List<Map<SummarySection, String>> {
            if (!isSliced) {
                // The single-call attempt failed, so everything is sliced now.
                isSliced = true
                pending.clear()
                PageChunker.chunk(bookOrder, sliceLimit).forEach(::submit)
            } else if (pending.isNotEmpty()) {
                PageChunker.chunk(pending, sliceLimit).forEach(::submit)
                pending.clear()
            }

            val position = bookOrder.withIndex().associate { (i, page) -> page.uploadNumber to i }
            return slices
                .sortedBy { (pages, _) -> pages.minOf { position[it.uploadNumber] ?: Int.MAX_VALUE } }
                .map { (_, future) -> SummaryNotes.parse(future.join()) }
        }

        /** Stops slices that have not started yet, e.g. after a transcription failure. */
        fun abandon() = abandoned.set(true)

        private fun submit(slicePages: List<RulebookPage>) {
            val ordered = PageOrder.arrange(slicePages)
            val number = slices.size + 1
            val future = CompletableFuture.supplyAsync({
                if (abandoned.get()) throw IllegalStateException("A feldolgozás megszakadt.")
                groq.summaryNotes(number, RulebookText.join(ordered))
            }, summaryExecutor)
            slices += ordered to future
        }
    }

    private fun transcribePages(gameId: UUID, totalPages: Int, notes: NotesPipeline) {
        // Pages read on an earlier attempt still feed the summary pipeline.
        games.findTranscribedPages(gameId).forEach(notes::offer)

        val pending = games.findUntranscribedPageNumbers(gameId)
        if (pending.isEmpty()) {
            log.info("All {} pages of game {} already transcribed, skipping", totalPages, gameId)
            return
        }

        games.updateStatus(gameId, GameStatus.TRANSCRIBING, "Oldalak beolvasása")
        log.info("Transcribing {} of {} pages for game {}", pending.size, totalPages, gameId)

        pending.forEach { pageNumber ->
            val page = games.findPageImage(gameId, pageNumber)
            if (page == null) {
                log.warn("Page {} of game {} missing, skipping", pageNumber, gameId)
                return@forEach
            }

            games.updateProgress(
                id = gameId,
                pagesProcessed = games.countTranscribed(gameId),
                detail = "$pageNumber. kép beolvasása ($totalPages képből)",
            )

            // Pages were already normalised to a vision-sized JPEG at upload time,
            // so they go to the model exactly as stored. Re-encoding here would
            // only add a second generation of JPEG artefacts to the text.
            val transcript = groq.transcribePage(page.imageData, pageNumber, totalPages)
            games.updateTranscript(gameId, pageNumber, transcript.text, transcript.printedPageNumber)
            notes.offer(RulebookPage(pageNumber, transcript.printedPageNumber, transcript.text))

            games.updateProgress(
                id = gameId,
                pagesProcessed = games.countTranscribed(gameId),
                detail = "$pageNumber. kép beolvasva ($totalPages képből)",
            )
        }
    }

    /**
     * Replaces the placeholder title with the game's real name. A failure here is
     * cosmetic, so it must not sink an otherwise successful ingestion.
     */
    private fun resolveTitle(gameId: UUID, rulebook: String) {
        try {
            val inferred = groq.inferTitle(rulebook)
                .lineSequence().first()
                .trim()
                .trim('"', '\'', '.', '*')
                .take(120)
            if (inferred.isNotBlank() && !inferred.startsWith("Ismeretlen", ignoreCase = true)) {
                log.info("Inferred title '{}' for game {}", inferred, gameId)
                games.updateStatus(gameId, GameStatus.SUMMARIZING, "Cím felismerve: $inferred")
                games.updateTitle(gameId, inferred)
            }
        } catch (e: Exception) {
            log.warn("Could not infer a title for game {}: {}", gameId, e.message)
        }
    }
}
