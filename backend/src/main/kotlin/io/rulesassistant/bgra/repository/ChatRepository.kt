package io.rulesassistant.bgra.repository

import io.rulesassistant.bgra.domain.ChatMessage
import io.rulesassistant.bgra.domain.ChatRole
import io.rulesassistant.bgra.domain.StoredAudio
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ChatRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun append(gameId: UUID, role: ChatRole, content: String): ChatMessage {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO chat_message (id, game_id, role, content)
            VALUES (:id, :gameId, :role, :content)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("gameId", gameId)
                .addValue("role", role.name)
                .addValue("content", content),
        )
        return findById(id) ?: error("Chat message $id disappeared right after insert")
    }

    fun findById(id: UUID): ChatMessage? =
        jdbc.query(
            "SELECT id, game_id, role, content, created_at FROM chat_message WHERE id = :id",
            MapSqlParameterSource("id", id),
        ) { rs, _ ->
            ChatMessage(
                id = rs.getObject("id", UUID::class.java),
                gameId = rs.getObject("game_id", UUID::class.java),
                role = ChatRole.valueOf(rs.getString("role")),
                content = rs.getString("content"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }.firstOrNull()

    fun findHistory(gameId: UUID): List<ChatMessage> =
        jdbc.query(
            """
            SELECT id, game_id, role, content, created_at FROM chat_message
            WHERE game_id = :gameId ORDER BY created_at, id
            """.trimIndent(),
            MapSqlParameterSource("gameId", gameId),
        ) { rs, _ ->
            ChatMessage(
                id = rs.getObject("id", UUID::class.java),
                gameId = rs.getObject("game_id", UUID::class.java),
                role = ChatRole.valueOf(rs.getString("role")),
                content = rs.getString("content"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

    fun exists(id: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM chat_message WHERE id = :id)",
            MapSqlParameterSource("id", id),
            Boolean::class.java,
        ) == true

    fun delete(id: UUID) {
        jdbc.update("DELETE FROM chat_message WHERE id = :id", MapSqlParameterSource("id", id))
    }

    fun clearHistory(gameId: UUID): Int =
        jdbc.update(
            "DELETE FROM chat_message WHERE game_id = :gameId",
            MapSqlParameterSource("gameId", gameId),
        )
}

/**
 * Cache for synthesised summary audio. Keyed by game with the summary's hash
 * alongside, so replaying costs nothing and a regenerated summary invalidates
 * the stale recording automatically.
 */
@Repository
class AudioRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun find(gameId: UUID): StoredAudio? =
        jdbc.query(
            "SELECT summary_hash, mime_type, audio_data FROM game_audio WHERE game_id = :gameId",
            MapSqlParameterSource("gameId", gameId),
        ) { rs, _ ->
            StoredAudio(
                summaryHash = rs.getString("summary_hash"),
                mimeType = rs.getString("mime_type"),
                audioData = rs.getBytes("audio_data"),
            )
        }.firstOrNull()

    fun save(gameId: UUID, summaryHash: String, mimeType: String, audio: ByteArray) {
        jdbc.update(
            """
            INSERT INTO game_audio (game_id, summary_hash, mime_type, audio_data)
            VALUES (:gameId, :hash, :mime, :audio)
            ON CONFLICT (game_id) DO UPDATE
            SET summary_hash = excluded.summary_hash,
                mime_type    = excluded.mime_type,
                audio_data   = excluded.audio_data,
                created_at   = now()
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("gameId", gameId)
                .addValue("hash", summaryHash)
                .addValue("mime", mimeType)
                .addValue("audio", audio),
        )
    }

    fun delete(gameId: UUID) {
        jdbc.update(
            "DELETE FROM game_audio WHERE game_id = :gameId",
            MapSqlParameterSource("gameId", gameId),
        )
    }
}
