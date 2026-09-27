package io.rulesassistant.bgra.groq

import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * A tokens-per-minute allowance for one model, modelled as a continuously
 * refilling bucket and kept honest by the rate-limit headers Groq returns.
 *
 * The first version tracked a 60-second sliding window of our own estimates.
 * That was accurate in the worst case only: every page transcription reserved
 * ~6,500 tokens and then sat out a full minute for it to age out, although the
 * vision call itself took about a second. Groq reports the real state on every
 * response (`x-ratelimit-remaining-tokens`, `x-ratelimit-reset-tokens`), so the
 * bucket is re-synchronised from those after each call and only waits as long as
 * the server says it must.
 *
 * Estimates are still used to decide whether a request fits *before* it is sent;
 * an underestimate costs one 429 and a retry honouring `Retry-After`.
 *
 * Groq meters each model separately, so [GroqClient] keeps one budget per model:
 * a summary call on the text model never waits behind page transcriptions.
 */
class TokenBudget(
    private val tokensPerMinute: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    private val defaultRefillPerMs = tokensPerMinute / WINDOW_MS.toDouble()
    private var available = tokensPerMinute.toDouble()
    private var refillPerMs = defaultRefillPerMs
    private var updatedAt = clock()

    /**
     * Blocks until [estimatedTokens] fit, then deducts them.
     *
     * A request larger than the whole allowance can never fit; it is let through
     * once the bucket is full so Groq reports the problem instead of this
     * waiting forever.
     */
    fun reserve(estimatedTokens: Int, label: String) {
        if (estimatedTokens >= tokensPerMinute) {
            log.warn(
                "Estimated {} tokens for {} exceeds the {}/min ceiling; sending anyway so the API reports it",
                estimatedTokens, label, tokensPerMinute,
            )
        }
        val needed = min(estimatedTokens, tokensPerMinute).toDouble()

        while (true) {
            val waitMs = lock.withLock {
                refill()
                if (available >= needed) {
                    available -= estimatedTokens
                    return
                }
                ((needed - available) / refillPerMs).toLong().coerceAtLeast(50)
            }
            log.info(
                "Token budget low ({}/min); waiting {} ms before {}",
                tokensPerMinute, waitMs, label,
            )
            try {
                sleeper(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw GroqException("A feldolgozás megszakadt.", e)
            }
        }
    }

    /**
     * Adopts the server's view of the allowance.
     *
     * [resetMs] is how long Groq says it will take to refill completely, which
     * gives the refill rate directly; without it the nominal per-minute rate is
     * assumed.
     */
    fun syncFromServer(remainingTokens: Int, resetMs: Long?) {
        lock.withLock {
            updatedAt = clock()
            available = remainingTokens.coerceIn(0, tokensPerMinute).toDouble()
            val missing = tokensPerMinute - available
            refillPerMs = if (resetMs != null && resetMs > 0 && missing > 0) {
                // Bounded so a malformed header cannot stall or flood the queue.
                (missing / resetMs).coerceIn(defaultRefillPerMs / 4, defaultRefillPerMs * 8)
            } else {
                defaultRefillPerMs
            }
        }
    }

    private fun refill() {
        val now = clock()
        available = min(tokensPerMinute.toDouble(), available + (now - updatedAt) * refillPerMs)
        updatedAt = now
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}

/** Parses Groq's Go-style durations such as `7.66s`, `2m59.56s` or `120ms`. */
object RateLimitDuration {
    private val PART = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")

    fun toMillis(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = PART.findAll(value).toList()
        if (parts.isEmpty() || parts.sumOf { it.value.length } != value.length) return null
        return parts.sumOf { match ->
            val amount = match.groupValues[1].toDouble()
            when (match.groupValues[2]) {
                "h" -> amount * 3_600_000
                "m" -> amount * 60_000
                "s" -> amount * 1_000
                else -> amount
            }
        }.toLong()
    }
}

/**
 * Rough token estimate for pacing purposes only.
 *
 * Hungarian is agglutinative and tokenises worse than English, so this uses a
 * deliberately pessimistic characters-per-token ratio: over-estimating costs a
 * little throughput, under-estimating costs a rejected request.
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 3.0

    /** Measured cost of one downscaled rulebook page image (~1600px long edge). */
    const val IMAGE_TOKENS = 2000

    fun forText(text: String): Int = (text.length / CHARS_PER_TOKEN).toInt() + 8

    /** Characters that fit in [tokens], for trimming rulebook context to budget. */
    fun charsFor(tokens: Int): Int = (tokens * CHARS_PER_TOKEN).toInt().coerceAtLeast(0)
}
