package io.rulesassistant.bgra.repository

import io.rulesassistant.bgra.domain.Game
import io.rulesassistant.bgra.domain.GamePage
import io.rulesassistant.bgra.domain.GamePageImage
import io.rulesassistant.bgra.domain.GameStatus
import io.rulesassistant.bgra.service.PageOrder
import io.rulesassistant.bgra.service.RulebookPage
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Hand-written SQL rather than an ORM: pages and audio are multi-megabyte BYTEA
 * columns, and an aggregate mapper would drag them into every library listing.
 * Each query here selects exactly the columns its caller needs.
 */
@Repository
class GameRepository(private val jdbc: NamedParameterJdbcTemplate) {

    private val gameMapper = RowMapper { rs, _ ->
        Game(
            id = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            status = GameStatus.valueOf(rs.getString("status")),
            statusDetail = rs.getString("status_detail"),
            errorMessage = rs.getString("error_message"),
            pageCount = rs.getInt("page_count"),
            pagesProcessed = rs.getInt("pages_processed"),
            summary = rs.getString("summary"),
            hasCover = rs.getBoolean("has_cover"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private companion object {
        /** Note the `cover_image IS NOT NULL` projection: the bytes stay in the database. */
        const val GAME_COLUMNS = "id, title, status, status_detail, error_message, page_count, " +
            "pages_processed, summary, cover_image IS NOT NULL AS has_cover, created_at, updated_at"

        const val EMPTY_TRANSCRIPT = "(transcript IS NULL OR transcript = '')"
        const val HAS_TRANSCRIPT = "(transcript IS NOT NULL AND transcript <> '')"
    }

    fun insert(id: UUID, title: String, pageCount: Int): Game {
        jdbc.update(
            """
            INSERT INTO game (id, title, status, status_detail, page_count, pages_processed)
            VALUES (:id, :title, :status, :detail, :pageCount, 0)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("title", title)
                .addValue("status", GameStatus.PENDING.name)
                .addValue("detail", "Feldolgozásra vár")
                .addValue("pageCount", pageCount),
        )
        return requireNotNull(findById(id)) { "Game $id disappeared right after insert" }
    }

    fun findAll(): List<Game> =
        jdbc.query("SELECT $GAME_COLUMNS FROM game ORDER BY created_at DESC", gameMapper)

    fun findById(id: UUID): Game? =
        jdbc.query(
            "SELECT $GAME_COLUMNS FROM game WHERE id = :id",
            MapSqlParameterSource("id", id),
            gameMapper,
        ).firstOrNull()

    fun updateStatus(id: UUID, status: GameStatus, detail: String?, errorMessage: String? = null) {
        jdbc.update(
            """
            UPDATE game
            SET status = :status, status_detail = :detail, error_message = :error, updated_at = now()
            WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name)
                .addValue("detail", detail)
                .addValue("error", errorMessage),
        )
    }

    fun updateProgress(id: UUID, pagesProcessed: Int, detail: String) {
        jdbc.update(
            """
            UPDATE game
            SET pages_processed = :processed, status_detail = :detail, updated_at = now()
            WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("processed", pagesProcessed)
                .addValue("detail", detail),
        )
    }

    fun updateSummary(id: UUID, summary: String) {
        jdbc.update(
            "UPDATE game SET summary = :summary, updated_at = now() WHERE id = :id",
            MapSqlParameterSource().addValue("id", id).addValue("summary", summary),
        )
    }

    fun updateTitle(id: UUID, title: String) {
        jdbc.update(
            "UPDATE game SET title = :title, updated_at = now() WHERE id = :id",
            MapSqlParameterSource().addValue("id", id).addValue("title", title),
        )
    }

    fun updateCover(id: UUID, image: ByteArray, mime: String) {
        jdbc.update(
            "UPDATE game SET cover_image = :image, cover_mime = :mime, updated_at = now() WHERE id = :id",
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("image", image)
                .addValue("mime", mime),
        )
    }

    fun findCover(id: UUID): Pair<ByteArray, String>? =
        jdbc.query(
            "SELECT cover_image, cover_mime FROM game WHERE id = :id AND cover_image IS NOT NULL",
            MapSqlParameterSource("id", id),
        ) { rs, _ -> rs.getBytes("cover_image") to rs.getString("cover_mime") }.firstOrNull()

    fun delete(id: UUID): Boolean =
        jdbc.update("DELETE FROM game WHERE id = :id", MapSqlParameterSource("id", id)) > 0

    /**
     * Marks work interrupted by a restart as failed. Without this, a container
     * crash mid-ingestion would leave games spinning in the UI forever.
     *
     * PENDING is included deliberately. The ingestion queue lives in memory, so
     * a PENDING row after a restart has no worker behind it and never will — and
     * because retrying is only offered for terminal states, leaving it PENDING
     * would strand the game with no way to recover it.
     */
    fun failInterrupted(): Int =
        jdbc.update(
            """
            UPDATE game
            SET status = 'FAILED',
                status_detail = NULL,
                error_message = :message,
                updated_at = now()
            WHERE status IN ('PENDING', 'TRANSCRIBING', 'SUMMARIZING')
            """.trimIndent(),
            MapSqlParameterSource(
                "message",
                "A feldolgozás megszakadt a szolgáltatás újraindulása miatt. " +
                    "Indítsd újra a feldolgozást.",
            ),
        )

    // ------------------------------------------------------------------ pages

    fun insertPage(gameId: UUID, pageNumber: Int, image: ByteArray, mime: String) {
        jdbc.update(
            """
            INSERT INTO game_page (id, game_id, page_number, image_data, image_mime)
            VALUES (:id, :gameId, :pageNumber, :image, :mime)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("gameId", gameId)
                .addValue("pageNumber", pageNumber)
                .addValue("image", image)
                .addValue("mime", mime),
        )
    }

    fun findPages(gameId: UUID): List<GamePage> =
        jdbc.query(
            """
            SELECT id, game_id, page_number, image_mime, transcript, printed_page_number
            FROM game_page WHERE game_id = :gameId ORDER BY page_number
            """.trimIndent(),
            MapSqlParameterSource("gameId", gameId),
        ) { rs, _ ->
            GamePage(
                id = rs.getObject("id", UUID::class.java),
                gameId = rs.getObject("game_id", UUID::class.java),
                pageNumber = rs.getInt("page_number"),
                imageMime = rs.getString("image_mime"),
                transcript = rs.getString("transcript"),
                printedPageNumber = rs.getObject("printed_page_number") as Int?,
            )
        }

    /** Loads a single page's image bytes, called once per page during transcription. */
    fun findPageImage(gameId: UUID, pageNumber: Int): GamePageImage? =
        jdbc.query(
            """
            SELECT page_number, image_mime, image_data
            FROM game_page WHERE game_id = :gameId AND page_number = :pageNumber
            """.trimIndent(),
            MapSqlParameterSource().addValue("gameId", gameId).addValue("pageNumber", pageNumber),
        ) { rs, _ ->
            GamePageImage(
                pageNumber = rs.getInt("page_number"),
                imageMime = rs.getString("image_mime"),
                imageData = rs.getBytes("image_data"),
            )
        }.firstOrNull()

    fun updateTranscript(gameId: UUID, pageNumber: Int, transcript: String, printedPageNumber: Int?) {
        jdbc.update(
            """
            UPDATE game_page SET transcript = :transcript, printed_page_number = :printed
            WHERE game_id = :gameId AND page_number = :pageNumber
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("gameId", gameId)
                .addValue("pageNumber", pageNumber)
                .addValue("transcript", transcript)
                .addValue("printed", printedPageNumber),
        )
    }

    /** Pages still lacking a transcript, so a retried run skips work already paid for. */
    fun findUntranscribedPageNumbers(gameId: UUID): List<Int> =
        jdbc.queryForList(
            """
            SELECT page_number FROM game_page
            WHERE game_id = :gameId AND $EMPTY_TRANSCRIPT
            ORDER BY page_number
            """.trimIndent(),
            MapSqlParameterSource("gameId", gameId),
            Int::class.java,
        )

    /**
     * Discards every transcript so the pages are read again from the stored
     * images. Used when the transcription prompt improves — the resumable
     * pipeline would otherwise skip pages it has already read.
     */
    fun clearTranscripts(gameId: UUID): Int =
        jdbc.update(
            "UPDATE game_page SET transcript = NULL, printed_page_number = NULL WHERE game_id = :gameId",
            MapSqlParameterSource("gameId", gameId),
        )


    fun countTranscribed(gameId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM game_page WHERE game_id = :gameId AND $HAS_TRANSCRIPT",
            MapSqlParameterSource("gameId", gameId),
            Int::class.java,
        ) ?: 0

    /**
     * Every transcribed page in upload order, with its printed page number. The
     * callers put them in book order with
     * [io.rulesassistant.bgra.service.PageOrder].
     */
    fun findTranscribedPages(gameId: UUID): List<RulebookPage> =
        jdbc.query(
            """
            SELECT page_number, printed_page_number, transcript
            FROM game_page
            WHERE game_id = :gameId AND $HAS_TRANSCRIPT
            ORDER BY page_number
            """.trimIndent(),
            MapSqlParameterSource("gameId", gameId),
        ) { rs, _ ->
            RulebookPage(
                uploadNumber = rs.getInt("page_number"),
                printedNumber = rs.getObject("printed_page_number") as Int?,
                text = rs.getString("transcript"),
            )
        }

    /** The rulebook in book order, capped at [limitChars] of formatted text. */
    fun loadRulebookPages(gameId: UUID, limitChars: Int): List<RulebookPage> {
        var used = 0
        return PageOrder.arrange(findTranscribedPages(gameId)).takeWhile { page ->
            used += page.formattedLength
            used <= limitChars
        }
    }
}
