package io.rulesassistant.bgra

import io.rulesassistant.bgra.config.IngestionProperties
import io.rulesassistant.bgra.domain.Game
import io.rulesassistant.bgra.domain.GameStatus
import io.rulesassistant.bgra.service.ImagePreprocessor
import io.rulesassistant.bgra.service.UnsupportedImageException
import io.rulesassistant.bgra.web.dto.GameSummaryResponse
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProgressReportingTest {

    private fun game(status: GameStatus, pageCount: Int = 0, processed: Int = 0) = Game(
        id = UUID.randomUUID(),
        title = "Teszt",
        status = status,
        pageCount = pageCount,
        pagesProcessed = processed,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `transcription progress is capped below completion`() {
        // All pages read but the summary is still pending, so the bar must not
        // claim 100% — that is the whole reason transcription tops out at 90.
        val allPagesRead = game(GameStatus.TRANSCRIBING, pageCount = 10, processed = 10)
        assertEquals(90, GameSummaryResponse.progressOf(allPagesRead))

        assertEquals(45, GameSummaryResponse.progressOf(game(GameStatus.TRANSCRIBING, 10, 5)))
        assertEquals(0, GameSummaryResponse.progressOf(game(GameStatus.PENDING)))
        assertEquals(92, GameSummaryResponse.progressOf(game(GameStatus.SUMMARIZING, 10, 10)))
        assertEquals(100, GameSummaryResponse.progressOf(game(GameStatus.READY, 10, 10)))
    }

    @Test
    fun `progress does not divide by zero when the page count is unknown`() {
        assertEquals(0, GameSummaryResponse.progressOf(game(GameStatus.TRANSCRIBING, 0, 0)))
    }
}

class ImagePreprocessorTest {

    private val preprocessor = ImagePreprocessor(IngestionProperties())

    private fun jpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = Color.WHITE
            fillRect(0, 0, width, height)
            paint = Color.BLACK
            drawString("A jatek celja", 20, height / 2)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> =
        ImageIO.read(ByteArrayInputStream(bytes)).let { it.width to it.height }

    @Test
    fun `oversized pages are scaled down to the configured edge`() {
        val (width, height) = dimensionsOf(preprocessor.prepareForVision(jpeg(4000, 3000)))
        assertEquals(1600, width)
        assertEquals(1200, height)
    }

    @Test
    fun `small pages are never upscaled`() {
        // Upscaling would cost extra vision tokens for a blurrier image, so the
        // scale factor is clamped at 1.0.
        val (width, height) = dimensionsOf(preprocessor.prepareForVision(jpeg(900, 700)))
        assertEquals(900, width)
        assertEquals(700, height)
    }

    @Test
    fun `thumbnails are bounded by the shorter thumbnail edge`() {
        val (width, _) = dimensionsOf(preprocessor.createThumbnail(jpeg(4000, 3000)))
        assertEquals(640, width)
    }

    @Test
    fun `undecodable uploads are rejected with a Hungarian message`() {
        val notAnImage = "ez nem kep".toByteArray()
        val failure = assertFailsWith<UnsupportedImageException> {
            preprocessor.validateDecodable(notAnImage, "heic-kep.heic")
        }
        assertTrue(failure.message!!.contains("heic-kep.heic"))
        assertTrue(failure.message!!.contains("JPG"))
    }

    @Test
    fun `images too small to hold readable rules text are rejected`() {
        assertFailsWith<UnsupportedImageException> {
            preprocessor.validateDecodable(jpeg(120, 120), "apro.jpg")
        }
    }
}
