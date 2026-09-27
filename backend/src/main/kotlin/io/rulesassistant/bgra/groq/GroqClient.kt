package io.rulesassistant.bgra.groq

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.rulesassistant.bgra.config.GroqProperties
import io.rulesassistant.bgra.service.PrintedPageNumber
import io.rulesassistant.bgra.service.SummaryLength
import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/** Raised when Groq cannot be reached or refuses the request after all retries. */
class GroqException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Raised when one request is larger than the whole per-minute allowance. Retrying
 * cannot help — the same request is rejected every time — so callers that can
 * split the work (the summary) catch this and fall back to smaller requests.
 */
class GroqRequestTooLargeException(message: String) : RuntimeException(message)

/** Raised when the API key is missing, so the UI can say so instead of showing a 500. */
class GroqNotConfiguredException :
    RuntimeException("A GROQ_API_KEY nincs beállítva, ezért a szabálykönyv nem dolgozható fel.")

// --- Wire format (OpenAI-compatible chat completions) -----------------------

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class ChatRequest(
    @JsonProperty("model") val model: String,
    @JsonProperty("messages") val messages: List<Message>,
    @JsonProperty("temperature") val temperature: Double,
    @JsonProperty("max_completion_tokens") val maxCompletionTokens: Int,
    /** gpt-oss-specific knob; omitted for models that do not accept it. */
    @JsonProperty("reasoning_effort") val reasoningEffort: String? = null,
    /**
     * Keeps chain-of-thought out of `message.content`. Both models in use are
     * reasoning models, and without this the vision model returns its entire
     * deliberation inline, which would be stored verbatim as the page transcript.
     */
    @JsonProperty("reasoning_format") val reasoningFormat: String? = null,
)

/** One entry from `GET /models`, used to validate configuration at startup. */
data class GroqModelInfo(val id: String, val inputModalities: List<String>) {
    val supportsImages: Boolean get() = inputModalities.any { it.equals("image", ignoreCase = true) }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ModelsResponse(
    @JsonProperty("data") val data: List<ModelEntry> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ModelEntry(
    @JsonProperty("id") val id: String,
    @JsonProperty("input_modalities") val inputModalities: List<String>? = null,
)

/** [content] is either a plain String or a list of [TextPart]/[ImagePart] for vision calls. */
private data class Message(
    @JsonProperty("role") val role: String,
    @JsonProperty("content") val content: Any,
)

private data class TextPart(
    @JsonProperty("text") val text: String,
    @JsonProperty("type") val type: String = "text",
)

private data class ImagePart(
    @JsonProperty("image_url") val imageUrl: ImageUrl,
    @JsonProperty("type") val type: String = "image_url",
)

private data class ImageUrl(@JsonProperty("url") val url: String)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Choice(
        val message: ResponseMessage?,
        @JsonProperty("finish_reason") val finishReason: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ResponseMessage(val content: String?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Usage(
        @JsonProperty("prompt_tokens") val promptTokens: Int? = null,
        @JsonProperty("completion_tokens") val completionTokens: Int? = null,
    )
}

private data class Completion(val content: String, val finishReason: String?)

/** A page transcript with the printed page number split off its first line. */
data class PageTranscript(val text: String, val printedPageNumber: Int?)

/**
 * Thin client over Groq's OpenAI-compatible chat completions endpoint.
 *
 * Two models are in play and they are not interchangeable:
 *  - [GroqProperties.visionModel] reads the uploaded page photos. `gpt-oss-120b`
 *    is text-only and cannot accept images at all.
 *  - [GroqProperties.summaryModel] writes the summary; see its note for why
 *    that is not the text model.
 *  - [GroqProperties.textModel] answers questions, with
 *    `reasoning_effort` pinned low.
 *
 * Calls are paced per model: Groq meters tokens per minute separately for each
 * model, so page transcription (vision) and summary writing (text) run against
 * separate allowances and can overlap.
 */
@Component
class GroqClient(
    private val properties: GroqProperties,
    restClientBuilder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = restClientBuilder
        .baseUrl(properties.baseUrl)
        .defaultHeader("Authorization", "Bearer ${properties.apiKey}")
        // A vision call on a dense page can run well past the default timeout.
        .requestFactory(
            ClientHttpRequestFactoryBuilder.detect().build(
                ClientHttpRequestFactorySettings.defaults()
                    .withConnectTimeout(Duration.ofSeconds(20))
                    .withReadTimeout(properties.timeout),
            ),
        )
        .build()

    private val pacingLock = ReentrantLock()
    private var lastCallAt = 0L

    /** One tokens-per-minute allowance per model, matching how Groq meters them. */
    private val budgets = ConcurrentHashMap<String, TokenBudget>()

    private fun budgetFor(model: String): TokenBudget =
        budgets.computeIfAbsent(model) { TokenBudget(properties.maxTokensPerMinute) }

    private companion object {
        val THINK_BLOCK = Regex("(?is)<think>.*?</think>\\s*")

        /**
         * Groq reports token-rate rejections as HTTP 413 with this code, which
         * reads like an oversized payload but is really "come back shortly".
         */
        const val RATE_LIMIT_CODE = "rate_limit_exceeded"

        /**
         * Groq's wording when a single request exceeds the whole per-minute
         * allowance. Same status and code as a transient rate limit, but retrying
         * sends the same request and is rejected again.
         */
        const val TOO_LARGE_HINT = "Request too large"

        /** Extracts "Please try again in 3.465s" from a rate-limit body. */
        val RETRY_HINT = Regex("""try again in ([0-9.]+)s""")

        /** Allowance for the framing text around the rulebook in a user prompt. */
        const val USER_PROMPT_OVERHEAD_TOKENS = 250

        /**
         * Slack between our estimate and the figure Groq meters (chat template,
         * image tokenisation). It only decides how long to wait before a call:
         * the budget itself is re-synchronised from the response headers, so this
         * no longer has to cover the worst case.
         */
        const val REQUEST_OVERHEAD_TOKENS = 600
    }

    val isConfigured: Boolean get() = properties.hasApiKey

    /**
     * Transcribes one rulebook page. The image is sent inline as a base64 data
     * URL, which Groq accepts up to 4 MB — the caller downscales well below that.
     */
    fun transcribePage(jpegBytes: ByteArray, pageNumber: Int, totalPages: Int): PageTranscript {
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpegBytes)
        val messages = listOf(
            Message("system", Prompts.TRANSCRIBE_SYSTEM),
            Message(
                "user",
                listOf(
                    TextPart(Prompts.transcribeUserPrompt(pageNumber, totalPages)),
                    ImagePart(ImageUrl(dataUrl)),
                ),
            ),
        )

        fun attempt(maxTokens: Int) = complete(
            model = properties.visionModel,
            messages = messages,
            temperature = 0.0,
            maxTokens = maxTokens,
            // Defaults to "none": copying text needs no deliberation, and leaving
            // it on let reasoning consume the whole completion budget on dense
            // pages, returning an empty body.
            reasoningEffort = properties.visionReasoningEffort.takeIf { it.isNotBlank() },
            label = "page $pageNumber/$totalPages transcription",
        )

        // The completion budget counts against the per-minute allowance whether
        // or not it is used, so it starts modest — a typical page needs a few
        // hundred tokens — and is raised only for a dense page that actually runs
        // out. A truncated transcript used to be stored without any warning.
        var result = attempt(properties.visionMaxTokens)
        if (result.finishReason == "length" && properties.visionMaxTokensDense > properties.visionMaxTokens) {
            log.info(
                "Page {} hit the {}-token limit; re-reading with {}",
                pageNumber, properties.visionMaxTokens, properties.visionMaxTokensDense,
            )
            result = attempt(properties.visionMaxTokensDense)
        }
        if (result.finishReason == "length") {
            log.warn("Page {} transcript is truncated even at {} tokens", pageNumber, properties.visionMaxTokensDense)
        }
        val (printed, text) = PrintedPageNumber.split(result.content)
        return PageTranscript(text, printed)
    }

    /**
     * The models this API key can actually reach, with their input modalities.
     *
     * Groq retires models with little notice — the Llama 4 vision models this
     * app originally targeted disappeared entirely — so the configured models
     * are checked against this list at startup rather than discovered the hard
     * way, mid-ingestion, after a user has uploaded forty pages.
     */
    fun listModels(): List<GroqModelInfo> {
        if (!properties.hasApiKey) throw GroqNotConfiguredException()
        val response = try {
            client.get().uri("/models").retrieve().body(ModelsResponse::class.java)
        } catch (e: Exception) {
            throw GroqException("A Groq modell-lista nem kérdezhető le: ${e.message}", e)
        }
        return response?.data.orEmpty().map {
            GroqModelInfo(it.id, it.inputModalities.orEmpty())
        }
    }

    /**
     * Writes the whole read-aloud summary in one call, straight from the rulebook
     * text. Throws [GroqRequestTooLargeException] rather than retrying when the
     * rulebook turns out not to fit, so the caller can slice it instead.
     */
    fun summarise(gameTitle: String, rulebookText: String, targetWords: Int): String {
        val result = complete(
            model = properties.effectiveSummaryModel,
            messages = listOf(
                Message("system", Prompts.SUMMARY_SYSTEM),
                Message("user", Prompts.summaryUserPrompt(gameTitle, rulebookText, targetWords)),
            ),
            temperature = 0.3,
            maxTokens = summaryCompletionTokens(targetWords),
            reasoningEffort = properties.summaryReasoningEffort.takeIf { it.isNotBlank() },
            label = "summary of '$gameTitle'",
            failFastWhenTooLarge = true,
        )
        // A summary cut off mid-sentence must not be stored as if complete. The
        // caller treats this like an oversized request and writes the summary
        // section by section instead, where each call has room to finish.
        if (result.finishReason == "length") {
            throw GroqRequestTooLargeException("Summary of '$gameTitle' ran out of completion tokens")
        }
        return result.content
    }

    /** Map step: sorts one slice of a long rulebook into tagged factual notes. */
    fun summaryNotes(part: Int, text: String): String = complete(
        model = properties.effectiveSummaryModel,
        messages = listOf(
            Message("system", Prompts.SUMMARY_NOTES_SYSTEM),
            Message("user", Prompts.summaryNotesUserPrompt(part, text)),
        ),
        temperature = 0.2,
        maxTokens = properties.summaryNotesMaxTokens,
        reasoningEffort = properties.summaryReasoningEffort.takeIf { it.isNotBlank() },
        label = "summary notes, slice $part",
    ).content

    /** Reduce step: writes one or more summary sections from the collected notes. */
    fun writeSections(
        gameTitle: String,
        sectionNames: List<String>,
        notes: String,
        targetWords: Int,
        continuation: Boolean,
        wholeSummary: Boolean,
    ): String = complete(
        model = properties.effectiveSummaryModel,
        messages = listOf(
            Message("system", Prompts.SUMMARY_SYSTEM),
            Message(
                "user",
                Prompts.writeSectionsUserPrompt(
                    gameTitle, sectionNames, notes, targetWords, continuation, wholeSummary,
                ),
            ),
        ),
        temperature = 0.3,
        maxTokens = summaryCompletionTokens(targetWords),
        reasoningEffort = properties.summaryReasoningEffort.takeIf { it.isNotBlank() },
        label = "summary sections ${sectionNames.joinToString()} of '$gameTitle'",
    ).content

    /**
     * Completion reserved for a summary of [targetWords], capped so a prompt
     * always has room beside it inside the per-request ceiling.
     */
    fun summaryCompletionTokens(targetWords: Int): Int =
        SummaryLength.completionTokens(targetWords).coerceIn(1_200, properties.maxTokensPerMinute / 2)

    /** Characters of rulebook text that fit beside a single-call summary of [targetWords]. */
    fun summaryContextChars(targetWords: Int): Int =
        contextChars(Prompts.SUMMARY_SYSTEM, summaryCompletionTokens(targetWords))

    /** Characters of rulebook text per slice of the notes step. */
    fun summaryNotesContextChars(): Int =
        contextChars(Prompts.SUMMARY_NOTES_SYSTEM, properties.summaryNotesMaxTokens)

    /** Characters of notes that fit beside a reduce call reserving [completionTokens]. */
    fun sectionContextChars(completionTokens: Int): Int =
        contextChars(Prompts.SUMMARY_SYSTEM, completionTokens.coerceIn(1_200, properties.maxTokensPerMinute / 2))

    private fun contextChars(systemPrompt: String, completionTokens: Int): Int = TokenEstimator.charsFor(
        properties.promptBudget(completionTokens) -
            TokenEstimator.forText(systemPrompt) - USER_PROMPT_OVERHEAD_TOKENS,
    )

    /**
     * Prompt tokens one question may use in total — system prompt, rulebook,
     * history and the question itself — beside the reserved answer.
     */
    fun answerPromptTokens(): Int = properties.promptBudget(properties.answerMaxTokens)

    /** Derives a display title from the first pages when the user did not supply one. */
    fun inferTitle(rulebookText: String): String = complete(
        model = properties.textModel,
        messages = listOf(
            Message("system", Prompts.TITLE_SYSTEM),
            Message("user", rulebookText.take(6000)),
        ),
        temperature = 0.0,
        // Leaves room for the low-effort reasoning that precedes the one-line answer.
        maxTokens = 400,
        reasoningEffort = properties.reasoningEffort,
        label = "title inference",
    ).content

    /**
     * Answers a player question strictly from [rulebookText]. [history] carries
     * earlier turns so follow-ups like "és ha mégis elfogy?" resolve correctly.
     * [contextWarning] tells the model when only part of the rulebook is present.
     */
    fun answerQuestion(
        gameTitle: String,
        rulebookText: String,
        history: List<Pair<String, String>>,
        question: String,
        contextWarning: String?,
    ): String {
        val messages = buildList {
            add(Message("system", Prompts.qaSystemPrompt(gameTitle, rulebookText, contextWarning)))
            history.forEach { (role, content) -> add(Message(role, content)) }
            add(Message("user", question))
        }
        return complete(
            model = properties.textModel,
            messages = messages,
            temperature = 0.2,
            maxTokens = properties.answerMaxTokens,
            reasoningEffort = properties.reasoningEffort,
            label = "question about '$gameTitle'",
        ).content
    }

    fun estimateTextTokens(text: String): Int = TokenEstimator.forText(text)

    // ---------------------------------------------------------------- internals

    private fun complete(
        model: String,
        messages: List<Message>,
        temperature: Double,
        maxTokens: Int,
        reasoningEffort: String?,
        label: String,
        failFastWhenTooLarge: Boolean = false,
    ): Completion {
        if (!properties.hasApiKey) throw GroqNotConfiguredException()

        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            maxCompletionTokens = maxTokens,
            reasoningEffort = reasoningEffort,
            reasoningFormat = properties.reasoningFormat.takeIf { it.isNotBlank() },
        )

        // Reserved up front because Groq bills max_completion_tokens against the
        // per-minute allowance at request time, not the tokens actually returned.
        val estimated =
            messages.sumOf { estimateMessageTokens(it) } + maxTokens + REQUEST_OVERHEAD_TOKENS
        if (failFastWhenTooLarge && estimated > properties.maxTokensPerMinute) {
            throw GroqRequestTooLargeException("Estimated $estimated tokens for $label")
        }

        val budget = budgetFor(model)
        var lastError: Exception? = null
        repeat(properties.maxRetries) { attempt ->
            budget.reserve(estimated, label)
            pace()
            try {
                val entity = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(ChatResponse::class.java)
                syncBudget(budget, entity.headers)

                val response = entity.body
                response?.usage?.let { usage ->
                    log.info(
                        "{}: {} prompt + {} completion tokens (reserved {})",
                        label, usage.promptTokens, usage.completionTokens, estimated,
                    )
                }

                val choice = response?.choices?.firstOrNull()
                val content = stripReasoning(choice?.message?.content)
                if (content.isNullOrBlank()) {
                    // Retryable rather than fatal: an empty body is usually a
                    // transient hiccup or a completion budget swallowed whole by
                    // reasoning tokens, and both often succeed on a second try.
                    lastError = GroqException(emptyResponseMessage(choice?.finishReason, label))
                    log.warn(
                        "Groq returned no content for {} (finish_reason={}, choices={}) - attempt {}/{}",
                        label, choice?.finishReason, response?.choices?.size ?: 0,
                        attempt + 1, properties.maxRetries,
                    )
                    sleep(backoffMs(attempt))
                    return@repeat
                }
                return Completion(content, choice?.finishReason)
            } catch (e: RestClientResponseException) {
                lastError = e
                e.responseHeaders?.let { syncBudget(budget, it) }
                val status = e.statusCode
                val body = e.responseBodyAsString
                if (status.value() == 413 && body.contains(TOO_LARGE_HINT)) {
                    log.warn("Groq rejected {} as larger than the per-minute allowance: {}", label, body)
                    if (failFastWhenTooLarge) throw GroqRequestTooLargeException(body)
                    throw GroqException(
                        "A kérés nagyobb, mint a Groq percenkénti tokenkerete ($label). " +
                            "Fizetős keret esetén növeld a GROQ_MAX_TOKENS_PER_MINUTE értékét.",
                        e,
                    )
                }
                if (!isRetryable(status, body)) {
                    log.error("Groq rejected {} with {}: {}", label, status, body)
                    throw GroqException(describe(status, body), e)
                }
                val waitMs = retryDelayMs(e, attempt)
                log.warn(
                    "Groq returned {} for {} (attempt {}/{}), retrying in {} ms",
                    status, label, attempt + 1, properties.maxRetries, waitMs,
                )
                sleep(waitMs)
            } catch (e: GroqException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val waitMs = backoffMs(attempt)
                log.warn(
                    "Groq call for {} failed (attempt {}/{}): {} — retrying in {} ms",
                    label, attempt + 1, properties.maxRetries, e.message, waitMs,
                )
                sleep(waitMs)
            }
        }
        throw GroqException(
            "A Groq hívás ${properties.maxRetries} próbálkozás után sem sikerült ($label): " +
                (lastError?.message ?: "ismeretlen hiba"),
            lastError,
        )
    }

    /** Adopts the rate-limit state Groq reports, when it reports one. */
    private fun syncBudget(budget: TokenBudget, headers: HttpHeaders) {
        val remaining = headers.getFirst("x-ratelimit-remaining-tokens")?.toDoubleOrNull() ?: return
        val resetMs = RateLimitDuration.toMillis(headers.getFirst("x-ratelimit-reset-tokens"))
        budget.syncFromServer(remaining.toInt(), resetMs)
    }

    /**
     * Explains an empty completion in terms the user can act on. `length` means
     * the completion budget was exhausted before any visible text was produced,
     * which on a reasoning model means thinking consumed all of it.
     */
    private fun emptyResponseMessage(finishReason: String?, label: String): String = when (finishReason) {
        "length" ->
            "A modell elérte a válaszhosszra szabott keretet, mielőtt bármit leírt volna ($label). " +
                "Növeld a GROQ_VISION_MAX_TOKENS értékét."
        else -> "A modell üres választ adott ($label)."
    }

    /**
     * Belt-and-braces removal of chain-of-thought.
     *
     * `reasoning_format=hidden` already keeps thinking out of the content, but a
     * model swap or a provider change could reintroduce it, and a `<think>` block
     * saved as a page transcript would silently poison the summary and every
     * answer built on it. Cheap to strip, expensive to miss.
     */
    private fun stripReasoning(raw: String?): String? = raw
        ?.replace(THINK_BLOCK, "")
        ?.trim()

    /**
     * 429 is expected on the free tier and 5xx are transient.
     *
     * 413 needs the body to disambiguate: Groq returns it both for a genuinely
     * oversized image (fatal — retrying sends the same bytes) and for exceeding
     * the tokens-per-minute allowance (retryable — the window refills). Only the
     * `rate_limit_exceeded` code distinguishes them.
     */
    private fun isRetryable(status: HttpStatusCode, body: String): Boolean = when {
        status.value() == 429 -> true
        status.is5xxServerError -> true
        status.value() == 413 -> body.contains(RATE_LIMIT_CODE)
        else -> false
    }

    private fun estimateMessageTokens(message: Message): Int = when (val content = message.content) {
        is String -> TokenEstimator.forText(content)
        is List<*> -> content.sumOf { part ->
            when (part) {
                is TextPart -> TokenEstimator.forText(part.text)
                is ImagePart -> TokenEstimator.IMAGE_TOKENS
                else -> 0
            }
        }
        else -> 0
    }

    private fun retryDelayMs(e: RestClientResponseException, attempt: Int): Long {
        // Groq sends Retry-After in seconds (sometimes fractional) on rate limits.
        val header = e.responseHeaders?.getFirst("retry-after")
            ?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() }
        // Some rate-limit responses carry the delay only in the message body,
        // e.g. "Please try again in 3.465s"; honouring it beats blind backoff.
        val fromBody = header ?: RETRY_HINT.find(e.responseBodyAsString)
            ?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() }
        // Padded: the server window must have actually moved on by the retry.
        return fromBody?.plus(750L)?.coerceIn(1_000L, 90_000L) ?: backoffMs(attempt)
    }

    private fun backoffMs(attempt: Int): Long = min(2_000L shl attempt, 30_000L)

    private fun describe(status: HttpStatusCode, body: String): String = when {
        status.value() == 401 || status.value() == 403 ->
            "A Groq API kulcs érvénytelen vagy lejárt. Ellenőrizd a GROQ_API_KEY értékét."
        status.value() == 404 ->
            "A beállított Groq modell nem érhető el ezzel az API kulccsal. " +
                "Ellenőrizd a GROQ_VISION_MODEL és GROQ_TEXT_MODEL értékét."
        status.value() == 413 ->
            "A beküldött oldal képe túl nagy a modell számára. Csökkentsd az " +
                "INGESTION_MAX_IMAGE_EDGE értékét."
        else -> "A Groq hívás elutasítva (HTTP ${status.value()}): ${body.take(400)}"
    }

    /**
     * Enforces a minimum gap between consecutive calls, across both models,
     * because the requests-per-minute ceiling is not the binding one but still
     * exists. Holding the lock across the sleep keeps the pacing global.
     */
    private fun pace() {
        pacingLock.withLock {
            val minGap = properties.minCallInterval.toMillis()
            val waitFor = lastCallAt + minGap - System.currentTimeMillis()
            if (waitFor > 0) sleep(waitFor)
            lastCallAt = System.currentTimeMillis()
        }
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GroqException("A feldolgozás megszakadt.", e)
        }
    }
}
