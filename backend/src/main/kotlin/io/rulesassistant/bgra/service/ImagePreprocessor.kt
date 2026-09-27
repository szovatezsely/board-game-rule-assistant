package io.rulesassistant.bgra.service

import io.rulesassistant.bgra.config.IngestionProperties
import net.coobird.thumbnailator.Thumbnails
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max

/** Thrown when an upload is not an image this JVM can decode (HEIC, for instance). */
class UnsupportedImageException(message: String) : RuntimeException(message)

/**
 * Normalises uploaded rulebook photos before they reach Groq.
 *
 * Phone cameras produce 8-12 MP JPEGs. Groq caps inline base64 images at 4 MB,
 * and — more importantly on a free tier — image token cost scales with
 * resolution while rulebook text stays perfectly legible at around 1600 px on
 * the long edge. Downscaling here is what makes a 40-page rulebook affordable.
 */
@Component
class ImagePreprocessor(private val properties: IngestionProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Re-encodes to a JPEG bounded by [IngestionProperties.maxImageEdge]. */
    fun prepareForVision(original: ByteArray): ByteArray =
        resize(original, properties.maxImageEdge, properties.jpegQuality, "vision")

    /** Small JPEG used as the library card cover. */
    fun createThumbnail(original: ByteArray): ByteArray =
        resize(original, properties.thumbnailEdge, 0.75, "thumbnail")

    /**
     * Decodes the upload to confirm the JVM can read it, so an unsupported format
     * fails at upload time with a clear message rather than mid-ingestion.
     */
    fun validateDecodable(bytes: ByteArray, filename: String) {
        val image = decode(bytes)
            ?: throw UnsupportedImageException(
                "A(z) \"$filename\" fájl nem olvasható képként. " +
                    "Támogatott formátumok: JPG és PNG. " +
                    "Az iPhone HEIC képeit előbb JPG-be kell konvertálni.",
            )
        if (image.width < 200 || image.height < 200) {
            throw UnsupportedImageException(
                "A(z) \"$filename\" kép túl kicsi (${image.width}x${image.height} képpont), " +
                    "a szabálykönyv szövege nem lenne kiolvasható belőle.",
            )
        }
    }

    private fun decode(bytes: ByteArray): BufferedImage? =
        try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            log.debug("Image decode failed", e)
            null
        }

    private fun resize(original: ByteArray, maxEdge: Int, quality: Double, purpose: String): ByteArray {
        val source = decode(original)
            ?: throw UnsupportedImageException("A kép nem olvasható. Támogatott formátumok: JPG és PNG.")

        // Thumbnailator's size() would happily upscale, which wastes tokens on a
        // blurrier image, so the scale factor is capped at 1.0 explicitly.
        val scale = minOf(1.0, maxEdge.toDouble() / max(source.width, source.height))

        val output = ByteArrayOutputStream()
        Thumbnails.of(ByteArrayInputStream(original))
            .scale(scale)
            // Honour the EXIF orientation flag: photos shot in portrait are
            // otherwise handed to the model sideways, which wrecks transcription.
            .useExifOrientation(true)
            .outputFormat("jpg")
            .outputQuality(quality)
            .toOutputStream(output)

        val result = output.toByteArray()
        log.debug(
            "Prepared {} image: {}x{} {} KB -> scale {}, {} KB",
            purpose, source.width, source.height, original.size / 1024,
            String.format("%.2f", scale), result.size / 1024,
        )
        return result
    }
}
