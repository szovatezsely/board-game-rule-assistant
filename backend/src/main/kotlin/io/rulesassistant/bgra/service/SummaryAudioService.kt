package io.rulesassistant.bgra.service

import io.rulesassistant.bgra.domain.StoredAudio
import io.rulesassistant.bgra.repository.AudioRepository
import io.rulesassistant.bgra.repository.GameRepository
import io.rulesassistant.bgra.tts.TtsClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the spoken summary, synthesising it at most once per summary version.
 *
 * The user wanted to replay the narration freely, so caching is the whole point
 * here: offline Piper runs at roughly 2.5x realtime and Gemini costs free-tier
 * requests, so a summary is synthesised once per voice and replayed for free.
 */
@Service
class SummaryAudioService(
    private val games: GameRepository,
    private val audio: AudioRepository,
    private val tts: TtsClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Per-game locks. Two browser tabs hitting play at the same moment would
     * otherwise both start a multi-minute synthesis of identical audio.
     */
    private val locks = ConcurrentHashMap<UUID, Any>()

    fun getOrCreateAudio(gameId: UUID): StoredAudio {
        val game = games.findById(gameId) ?: throw GameNotFoundException(gameId)
        val summary = game.summary?.takeIf { it.isNotBlank() }
            ?: throw GameNotReadyException(
                "Ehhez a játékhoz még nem készült összefoglaló, ezért nincs mit felolvasni.",
            )

        // The voice is part of the key: switching from Piper to Gemini (or to
        // another voice) must re-record, not replay the old recording.
        val hash = sha256(tts.engineId() + "\n" + summary)
        audio.find(gameId)?.let { cached ->
            if (cached.summaryHash == hash) {
                log.debug("Serving cached narration for game {}", gameId)
                return cached
            }
            log.info("Cached narration for game {} is stale, re-synthesising", gameId)
        }

        val lock = locks.computeIfAbsent(gameId) { Any() }
        synchronized(lock) {
            // Re-check inside the lock: a concurrent request may have finished
            // the synthesis while this thread was waiting for its turn.
            audio.find(gameId)?.let { if (it.summaryHash == hash) return it }

            val result = tts.synthesize(summary)
            // Keyed by the engine that actually spoke. When Gemini was down and
            // Piper stood in, this key differs from the preferred one, so the
            // next play tries Gemini again instead of replaying the fallback.
            val storedHash = sha256(result.engine + "\n" + summary)
            audio.save(gameId, storedHash, "audio/wav", result.audio)
            log.info("Stored {} KB of narration for game {} ({})", result.audio.size / 1024, gameId, result.engine)
            return StoredAudio(storedHash, "audio/wav", result.audio)
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
