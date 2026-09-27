package io.rulesassistant.bgra.tts

import com.fasterxml.jackson.annotation.JsonProperty
import io.rulesassistant.bgra.config.TtsProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

class TtsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
private data class HealthResponse(@JsonProperty("engine") val engine: String? = null)

private data class SynthesizeRequest(
    @JsonProperty("text") val text: String,
    @JsonProperty("length_scale") val lengthScale: Double,
)

/**
 * Client for the TTS sidecar that renders Hungarian speech, with Gemini TTS
 * when configured and offline Piper otherwise.
 *
 * Piper synthesis is CPU-bound and runs at roughly 2.5x realtime, so a long
 * summary can take a couple of minutes; the generous timeout reflects that, and
 * callers cache the result so a summary is only ever synthesised once per voice.
 */
@Component
class TtsClient(
    private val properties: TtsProperties,
    restClientBuilder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient = restClientBuilder
        .baseUrl(properties.baseUrl)
        .requestFactory(buildRequestFactory(properties.timeout))
        .build()

    private companion object {
        /**
         * HTTP/1.1 is pinned deliberately.
         *
         * The JDK HTTP client defaults to HTTP/2 and, over plaintext, opens with
         * an h2c upgrade request. The TTS sidecar runs on uvicorn's h11 worker,
         * which speaks HTTP/1.1 only: it rejects the upgrade and the request body
         * never arrives, so synthesis fails with a confusing "field required"
         * from FastAPI rather than a connection error.
         *
         * The read timeout is generous because synthesis is measured in minutes
         * for a long summary, not seconds.
         */
        fun buildRequestFactory(readTimeout: Duration): JdkClientHttpRequestFactory {
            val httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            return JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(readTimeout)
            }
        }
    }

    /**
     * Which engine and voice the sidecar will use, e.g. `gemini:gemini-3.8-flash-tts:Kore`.
     * Part of the audio cache key, so switching voices re-records the summary
     * instead of replaying the old voice.
     */
    fun engineId(): String = try {
        client.get().uri("/health").retrieve().body(HealthResponse::class.java)?.engine ?: "unknown"
    } catch (e: Exception) {
        log.warn("TTS health check failed: {}", e.message)
        "unknown"
    }

    /** Returns WAV bytes for [text]. */
    /** WAV audio plus the engine that actually produced it (Gemini, or Piper as fallback). */
    class Synthesis(val audio: ByteArray, val engine: String)

    fun synthesize(text: String): Synthesis {
        log.info("Requesting speech synthesis for {} characters", text.length)
        val response = try {
            client.post()
                .uri("/synthesize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(SynthesizeRequest(text, properties.lengthScale))
                .retrieve()
                .toEntity(ByteArray::class.java)
        } catch (e: org.springframework.web.client.RestClientResponseException) {
            throw TtsException("A felolvasó szolgáltatás hibát adott: ${e.message}", e)
        } catch (e: Exception) {
            throw TtsException(
                "A felolvasó szolgáltatás nem érhető el vagy hibát adott: ${e.message}",
                e,
            )
        }

        val audio = response.body
        if (audio == null || audio.isEmpty()) {
            throw TtsException("A felolvasó szolgáltatás üres hangfájlt adott vissza.")
        }
        val engine = response.headers.getFirst("X-TTS-Engine") ?: "unknown"
        log.info("Received {} KB of audio from {}", audio.size / 1024, engine)
        return Synthesis(audio, engine)
    }
}
