package io.rulesassistant.bgra.web

import io.rulesassistant.bgra.groq.GroqException
import io.rulesassistant.bgra.groq.GroqNotConfiguredException
import io.rulesassistant.bgra.service.GameNotFoundException
import io.rulesassistant.bgra.service.GameNotReadyException
import io.rulesassistant.bgra.service.InvalidUploadException
import io.rulesassistant.bgra.service.UnsupportedImageException
import io.rulesassistant.bgra.tts.TtsException
import io.rulesassistant.bgra.web.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

/**
 * Maps domain failures onto HTTP responses carrying a Hungarian message the UI
 * can show verbatim. The `code` lets the frontend branch on the cause without
 * parsing prose.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(GameNotFoundException::class)
    fun onNotFound(e: GameNotFoundException) =
        error(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", e.message)

    @ExceptionHandler(InvalidUploadException::class, UnsupportedImageException::class)
    fun onBadUpload(e: RuntimeException) =
        error(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD", e.message)

    @ExceptionHandler(IllegalArgumentException::class)
    fun onBadRequest(e: IllegalArgumentException) =
        error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.message)

    @ExceptionHandler(GameNotReadyException::class)
    fun onNotReady(e: GameNotReadyException) =
        error(HttpStatus.CONFLICT, "NOT_READY", e.message)

    @ExceptionHandler(GroqNotConfiguredException::class)
    fun onMissingKey(e: GroqNotConfiguredException) =
        error(HttpStatus.SERVICE_UNAVAILABLE, "GROQ_NOT_CONFIGURED", e.message)

    @ExceptionHandler(GroqException::class)
    fun onGroqFailure(e: GroqException): ResponseEntity<ErrorResponse> {
        log.error("Groq call failed", e)
        return error(HttpStatus.BAD_GATEWAY, "GROQ_ERROR", e.message)
    }

    @ExceptionHandler(TtsException::class)
    fun onTtsFailure(e: TtsException): ResponseEntity<ErrorResponse> {
        log.error("Speech synthesis failed", e)
        return error(HttpStatus.BAD_GATEWAY, "TTS_ERROR", e.message)
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun onTooLarge(e: MaxUploadSizeExceededException) = error(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "UPLOAD_TOO_LARGE",
        "A feltöltés túl nagy. Próbáld kevesebb oldallal, vagy kisebb felbontású képekkel.",
    )

    @ExceptionHandler(Exception::class)
    fun onUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled error", e)
        return error(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Váratlan hiba történt a kiszolgálón.",
        )
    }

    private fun error(status: HttpStatus, code: String, message: String?) =
        ResponseEntity.status(status).body(
            ErrorResponse(message ?: "Ismeretlen hiba.", code),
        )
}
