package io.rulesassistant.bgra.config

import io.rulesassistant.bgra.groq.GroqClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.atomic.AtomicReference

/**
 * Outcome of checking the configured models against what the API key can reach.
 *
 * [problem] is a ready-to-display Hungarian sentence, or null when everything
 * checks out.
 */
data class ModelCheck(
    val checked: Boolean = false,
    val textModelAvailable: Boolean = false,
    val visionModelAvailable: Boolean = false,
    val visionModelSupportsImages: Boolean = false,
    val imageCapableModels: List<String> = emptyList(),
    val problem: String? = null,
) {
    val healthy: Boolean
        get() = checked && textModelAvailable && visionModelAvailable && visionModelSupportsImages
}

/**
 * Verifies at startup that the configured Groq models exist and that the vision
 * model can actually accept images.
 *
 * This exists because of a real failure: the app shipped pointing at a Llama 4
 * vision model that Groq later retired, and the only symptom was an HTTP 404
 * raised *after* a user had photographed and uploaded a whole rulebook. Checking
 * `GET /models` once at boot turns that into a banner on the home page, and the
 * message names the models the key can actually use.
 *
 * A failure here never blocks startup: the API may be briefly unreachable, and
 * that must not stop the app from serving already-processed rulebooks.
 */
@Configuration
class GroqModelValidator(private val groq: GroqClient) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val result = AtomicReference(ModelCheck())

    fun current(): ModelCheck = result.get()

    @Bean
    fun validateGroqModels(properties: GroqProperties): ApplicationRunner = ApplicationRunner {
        if (!properties.hasApiKey) {
            log.warn("GROQ_API_KEY is not set - skipping model validation")
            return@ApplicationRunner
        }
        try {
            val models = groq.listModels()
            val byId = models.associateBy { it.id }
            val imageCapable = models.filter { it.supportsImages }.map { it.id }.sorted()

            val text = byId[properties.textModel]
            val vision = byId[properties.visionModel]

            val problem = when {
                text == null ->
                    "A beállított szövegmodell (${properties.textModel}) nem érhető el ezzel az API kulccsal."
                vision == null ->
                    "A beállított képfelismerő modell (${properties.visionModel}) nem érhető el ezzel az API " +
                        "kulccsal. Képet feldolgozni tudó modellek: ${imageCapable.joinToString(", ").ifBlank { "nincs" }}."
                !vision.supportsImages ->
                    "A beállított képfelismerő modell (${properties.visionModel}) nem fogad képet. " +
                        "Képet feldolgozni tudó modellek: ${imageCapable.joinToString(", ").ifBlank { "nincs" }}."
                else -> null
            }

            result.set(
                ModelCheck(
                    checked = true,
                    textModelAvailable = text != null,
                    visionModelAvailable = vision != null,
                    visionModelSupportsImages = vision?.supportsImages == true,
                    imageCapableModels = imageCapable,
                    problem = problem,
                ),
            )

            if (problem == null) {
                log.info(
                    "Groq models OK - text={}, vision={} (image-capable models: {})",
                    properties.textModel, properties.visionModel, imageCapable,
                )
            } else {
                log.error("Groq model configuration problem: {}", problem)
            }
        } catch (e: Exception) {
            // Non-fatal by design; the app must still serve existing rulebooks.
            log.warn("Could not validate Groq models at startup: {}", e.message)
        }
    }
}
