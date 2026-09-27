package io.rulesassistant.bgra.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "groq")
data class GroqProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://api.groq.com/openai/v1",
    /** Text model used for the Hungarian summary and for answering questions. */
    val textModel: String = "openai/gpt-oss-120b",
    /**
     * Vision model used to read the uploaded rulebook photos; gpt-oss is
     * text-only and cannot accept images at all.
     *
     * This must be a model whose `input_modalities` include `image`. Groq's
     * catalogue changes: the Llama 4 vision models this originally used were
     * retired, and `GET /models` is the authority on what the key can reach.
     * [io.rulesassistant.bgra.config.GroqModelValidator] checks it on startup.
     */
    val visionModel: String = "qwen/qwen3.8-27b",
    /**
     * Groq model that writes the read-aloud summary when Gemini is not
     * configured or unavailable; with a Gemini key, Gemini writes it (see
     * [io.rulesassistant.bgra.gemini.GeminiClient]). Blank means [textModel].
     *
     * Defaults to the Qwen model rather than gpt-oss-120b after a side-by-side
     * test on the same transcripts: gpt-oss misstated the game's goal, kept
     * recognition errors ("az ór" for "az őr"), used the wrong verbs and invented
     * a tie-break rule, and neither a stricter prompt, an editing pass nor medium
     * reasoning fixed that (medium no longer fits in an 8k request). Qwen got the
     * goal and wording right and invented nothing, though it stays closer to the
     * source text. Answers stay on [textModel], which needs its reasoning to
     * combine rules.
     *
     * It shares the vision model's per-minute allowance when both are Qwen; the
     * summary is written after transcription, so they rarely compete.
     */
    val summaryModel: String = "qwen/qwen3.8-27b",
    /** Reasoning effort for [summaryModel]: Qwen takes `none` or `default`, gpt-oss `low`/`medium`/`high`. */
    val summaryReasoningEffort: String = "none",
    /** gpt-oss reasoning budget. Kept low deliberately: rules answers should quote, not ruminate. */
    val reasoningEffort: String = "low",

    /**
     * Reasoning budget for the vision model, disabled by default.
     *
     * Transcription is mechanical: there is nothing to reason about, only text
     * to copy. Left on, the model spent its entire completion allowance thinking
     * about a dense two-column page and returned nothing at all — `finish_reason:
     * length` with an empty body. Switching it off cut a page from roughly 7,500
     * tokens to 2,700 and made the output strictly better.
     *
     * Note the vision model accepts only `none` or `default` here, not the
     * `low`/`medium`/`high` scale gpt-oss uses. Blank omits the parameter.
     */
    val visionReasoningEffort: String = "none",
    /**
     * `hidden` keeps chain-of-thought out of the response body. Without it the
     * vision model returns its deliberation inline and it gets stored as the
     * page transcript. Blank disables the parameter entirely.
     */
    val reasoningFormat: String = "hidden",
    val timeout: Duration = Duration.ofSeconds(180),
    /** Floor on the spacing between two calls, independent of token cost. */
    val minCallInterval: Duration = Duration.ofMillis(1200),
    val maxRetries: Int = 6,

    /**
     * Tokens-per-minute ceiling, measured from `x-ratelimit-limit-tokens`
     * (8000 on the free tier for both models used here).
     *
     * This is the binding constraint on the whole design, and it is easy to
     * misread. Groq counts `max_completion_tokens` against it at request time,
     * not the tokens actually generated — so reserving a large completion
     * budget gets a request rejected before a single token is produced. It also
     * means **no single request may exceed this total**, regardless of the
     * model's 131k context window.
     */
    val maxTokensPerMinute: Int = 8000,

    /**
     * Completion budget for a page transcription on the first attempt.
     *
     * Reserved against the per-minute allowance whether or not it is used, so it
     * is kept close to what a page really needs: measured pages of a card game
     * took 390-500 tokens. A page that runs out is re-read with
     * [visionMaxTokensDense] instead of being stored truncated.
     */
    val visionMaxTokens: Int = 1000,
    /** Completion budget for re-reading a page that did not fit in [visionMaxTokens]. */
    val visionMaxTokensDense: Int = 3500,
    /** Completion budget for the notes of one slice of a long rulebook. */
    val summaryNotesMaxTokens: Int = 2500,
    /** Completion budget for a single answer, including low-effort reasoning. */
    val answerMaxTokens: Int = 1200,

) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    val effectiveSummaryModel: String get() = summaryModel.ifBlank { textModel }

    /**
     * Prompt tokens available to a call reserving [completionTokens], with a
     * safety margin for the tokeniser estimate being approximate.
     */
    fun promptBudget(completionTokens: Int): Int =
        ((maxTokensPerMinute - completionTokens) * 0.9).toInt().coerceAtLeast(500)
}

/** Google Gemini, used for the summary when a key is set; Groq remains the fallback. */
@ConfigurationProperties(prefix = "gemini")
data class GeminiProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    /**
     * Tried in order. Overload on the free tier is per model and comes in
     * spikes, so a second Flash model usually answers when the first does not.
     * Both were checked for Hungarian quality on the same rulebook. Flash only:
     * Pro models have no free-tier quota at all (limit 0).
     */
    val models: List<String> = listOf("gemini-3.8-flash", "gemini-3.5-flash"),
    /** Includes the model's thinking, which measured ~4,000 tokens for an 8-page book. */
    val maxOutputTokens: Int = 24_000,
    /** Rulebook text sent in one call; far below the 1M-token window, but bounds a runaway upload. */
    val maxContextChars: Int = 400_000,
    val timeout: Duration = Duration.ofMinutes(5),
    /**
     * The first model is clearly the best writer, so it is worth waiting for:
     * with pauses growing by [retryDelay] each time, three attempts wait about
     * 45 seconds in total before the next model is tried.
     */
    val primaryAttempts: Int = 3,
    /** Fallback models get a short try each: moving on beats waiting out a spike. */
    val attemptsPerModel: Int = 2,
    val retryDelay: Duration = Duration.ofSeconds(15),
)

@ConfigurationProperties(prefix = "tts")
data class TtsProperties(
    val baseUrl: String = "http://tts:8200",
    val timeout: Duration = Duration.ofMinutes(5),
    /** >1.0 slows the voice down, which measurably helps comprehension of rules text. */
    val lengthScale: Double = 1.06,
)

@ConfigurationProperties(prefix = "ingestion")
data class IngestionProperties(
    /** Longest edge in pixels of the JPEG sent to the vision model. */
    val maxImageEdge: Int = 1600,
    val jpegQuality: Double = 0.8,
    /** Longest edge of the stored library thumbnail. */
    val thumbnailEdge: Int = 640,
    val maxPagesPerGame: Int = 80,
    /** Characters of rulebook text handed to the model. ~4 chars/token keeps us inside 131k. */
    val maxContextChars: Int = 220_000,
)
