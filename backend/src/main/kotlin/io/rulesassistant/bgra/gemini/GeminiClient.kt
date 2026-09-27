package io.rulesassistant.bgra.gemini

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.rulesassistant.bgra.config.GeminiProperties
import io.rulesassistant.bgra.groq.Prompts
import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration

class GeminiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// --- Wire format (generateContent) ------------------------------------------

private data class Part(@JsonProperty("text") val text: String)

private data class Content(
    @JsonProperty("parts") val parts: List<Part>,
    @JsonProperty("role") val role: String? = null,
)

private data class GenerationConfig(
    @JsonProperty("temperature") val temperature: Double,
    @JsonProperty("maxOutputTokens") val maxOutputTokens: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class GenerateRequest(
    @JsonProperty("systemInstruction") val systemInstruction: Content,
    @JsonProperty("contents") val contents: List<Content>,
    @JsonProperty("generationConfig") val generationConfig: GenerationConfig,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GenerateResponse(
    val candidates: List<Candidate> = emptyList(),
    val usageMetadata: Usage? = null,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Candidate(val content: CandidateContent? = null, val finishReason: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CandidateContent(val parts: List<ResponsePart> = emptyList())

    /** `thought` parts carry the model's reasoning and are never part of the answer. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ResponsePart(val text: String? = null, val thought: Boolean? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Usage(
        val promptTokenCount: Int? = null,
        val candidatesTokenCount: Int? = null,
        val thoughtsTokenCount: Int? = null,
    )
}

/**
 * Writes the read-aloud summary with Google Gemini, when a key is configured.
 *
 * Chosen after a side-by-side test on the same transcripts and prompt: it was
 * the only model that got the goal, the game terms and the idioms of the
 * rulebook right *and* wrote grammatical Hungarian ("egy játékos hiján mindenki
 * kiesett" correctly as "egyetlen játékos kivételével"). The Groq models either
 * misread the source or made grammar errors, and translating an English summary
 * with DeepL confused game terms.
 *
 * Two practical differences from Groq shape this client:
 *  - The context window is a million tokens and the free tier has no 8k
 *    per-request ceiling, so the whole rulebook always goes in one call.
 *  - The free tier is often overloaded (HTTP 503 "high demand"); in testing the
 *    first three attempts failed and the fourth succeeded. Retries are brief,
 *    and the caller falls back to Groq if Gemini stays unavailable. Overload is
 *    per model, so the configured models are tried in turn, briefly each.
 */
@Component
class GeminiClient(
    private val properties: GeminiProperties,
    restClientBuilder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = restClientBuilder
        .baseUrl(properties.baseUrl)
        .defaultHeader("x-goog-api-key", properties.apiKey)
        .requestFactory(
            ClientHttpRequestFactoryBuilder.detect().build(
                ClientHttpRequestFactorySettings.defaults()
                    .withConnectTimeout(Duration.ofSeconds(20))
                    .withReadTimeout(properties.timeout),
            ),
        )
        .build()

    val isConfigured: Boolean get() = properties.apiKey.isNotBlank()

    val models: List<String> get() = properties.models

    /** Characters of rulebook that may go in one summary call. */
    val contextChars: Int get() = properties.maxContextChars

    fun summarise(gameTitle: String, rulebookText: String, targetWords: Int): String {
        if (!isConfigured) throw GeminiException("Nincs beállítva GEMINI_API_KEY.")

        val request = GenerateRequest(
            systemInstruction = Content(listOf(Part(Prompts.SUMMARY_SYSTEM))),
            contents = listOf(
                Content(listOf(Part(Prompts.summaryUserPrompt(gameTitle, rulebookText, targetWords))), "user"),
            ),
            generationConfig = GenerationConfig(temperature = 0.3, maxOutputTokens = properties.maxOutputTokens),
        )

        var lastError: Exception? = null
        properties.models.forEachIndexed { modelIndex, model ->
            val attempts = if (modelIndex == 0) properties.primaryAttempts else properties.attemptsPerModel
            for (attempt in 1..attempts) {
                try {
                    return generate(model, request, gameTitle)
                } catch (e: RestClientResponseException) {
                    lastError = e
                    val status = e.statusCode.value()
                    if (status == 401 || status == 403) {
                        throw GeminiException("A Gemini API kulcs érvénytelen (HTTP $status).", e)
                    }
                    // Overload (503) and rate limits (429) are worth a short retry,
                    // except a quota of zero, which never recovers on this model.
                    val retryable = status >= 500 || (status == 429 && !e.responseBodyAsString.contains("limit: 0"))
                    log.warn("Gemini {} returned {} for '{}' (attempt {}/{})",
                        model, status, gameTitle, attempt, attempts)
                    if (!retryable) break
                } catch (e: GeminiException) {
                    lastError = e
                    log.warn("Gemini {} gave no usable summary for '{}': {}", model, gameTitle, e.message)
                    break
                } catch (e: Exception) {
                    lastError = e
                    log.warn("Gemini {} call for '{}' failed (attempt {}): {}", model, gameTitle, attempt, e.message)
                }
                if (attempt < attempts) {
                    // Growing pauses on the model worth waiting for; short ones on fallbacks.
                    val factor = if (modelIndex == 0) attempt else 1
                    sleep(properties.retryDelay.toMillis() * factor)
                } else if (modelIndex < properties.models.lastIndex) {
                    sleep(properties.retryDelay.toMillis())
                }
            }
        }
        throw GeminiException(
            "A Gemini egyik modellje sem érhető el most (${properties.models.joinToString()}): ${lastError?.message}",
            lastError,
        )
    }

    private fun generate(model: String, request: GenerateRequest, gameTitle: String): String {
        val response = client.post()
            .uri("/models/{model}:generateContent", model)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(GenerateResponse::class.java)

        val candidate = response?.candidates?.firstOrNull()
        val text = candidate?.content?.parts.orEmpty()
            .filter { it.thought != true }
            .joinToString("") { it.text.orEmpty() }
            .trim()
        response?.usageMetadata?.let {
            log.info(
                "Gemini {} summary of '{}': {} prompt + {} output + {} thinking tokens",
                model, gameTitle, it.promptTokenCount, it.candidatesTokenCount, it.thoughtsTokenCount,
            )
        }
        if (candidate?.finishReason == "MAX_TOKENS") {
            throw GeminiException("A Gemini összefoglaló a kimeneti korlát miatt megszakadt.")
        }
        if (text.isBlank()) {
            throw GeminiException("A Gemini üres választ adott (finishReason=${candidate?.finishReason}).")
        }
        return text
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GeminiException("A feldolgozás megszakadt.", e)
        }
    }
}
