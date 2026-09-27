package io.rulesassistant.bgra.web

import io.rulesassistant.bgra.config.GroqModelValidator
import io.rulesassistant.bgra.config.GroqProperties
import io.rulesassistant.bgra.groq.GroqClient
import io.rulesassistant.bgra.service.ChatService
import io.rulesassistant.bgra.service.GameService
import io.rulesassistant.bgra.service.SummaryAudioService
import io.rulesassistant.bgra.web.dto.AskRequest
import io.rulesassistant.bgra.web.dto.ChatMessageResponse
import io.rulesassistant.bgra.web.dto.GameDetailResponse
import io.rulesassistant.bgra.web.dto.GameSummaryResponse
import io.rulesassistant.bgra.web.dto.PageResponse
import io.rulesassistant.bgra.web.dto.ServiceStatusResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api")
class GameController(
    private val gameService: GameService,
    private val chatService: ChatService,
    private val audioService: SummaryAudioService,
    private val groqClient: GroqClient,
    private val groqProperties: GroqProperties,
    private val modelValidator: GroqModelValidator,
) {

    /** Lets the UI warn about a missing key or an unusable model before an upload. */
    @GetMapping("/status")
    fun status(): ServiceStatusResponse {
        val check = modelValidator.current()
        return ServiceStatusResponse(
            groqConfigured = groqClient.isConfigured,
            visionModel = groqProperties.visionModel,
            textModel = groqProperties.textModel,
            // Unchecked (API unreachable at boot) is reported as healthy: an
            // unverifiable configuration is not a known-bad one, and crying wolf
            // here would train users to ignore the banner.
            modelsHealthy = !check.checked || check.healthy,
            modelProblem = check.problem,
            imageCapableModels = check.imageCapableModels,
        )
    }

    @GetMapping("/games")
    fun listGames(): List<GameSummaryResponse> =
        gameService.listGames().map(GameSummaryResponse::from)

    @GetMapping("/games/{id}")
    fun getGame(@PathVariable id: UUID): GameDetailResponse {
        val game = gameService.getGame(id)
        val pages = gameService.pageStates(id)
        return GameDetailResponse.from(game, pages)
    }

    /**
     * Uploads a rulebook as a set of page images. `title` is optional — when it
     * is omitted the pipeline reads the game's name off the pages instead.
     */
    @PostMapping("/games", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createGame(
        @RequestParam(required = false) title: String?,
        @RequestParam("files") files: List<MultipartFile>,
    ): GameSummaryResponse = GameSummaryResponse.from(gameService.createGame(title, files))

    @PostMapping("/games/{id}/retry")
    fun retry(@PathVariable id: UUID): GameSummaryResponse =
        GameSummaryResponse.from(gameService.retryIngestion(id))

    /** Re-reads every page from its stored image; costs one Groq call per page. */
    @PostMapping("/games/{id}/rescan")
    fun rescan(@PathVariable id: UUID): GameSummaryResponse =
        GameSummaryResponse.from(gameService.rescan(id))

    @PostMapping("/games/{id}/summary/regenerate")
    fun regenerateSummary(@PathVariable id: UUID): GameSummaryResponse =
        GameSummaryResponse.from(gameService.regenerateSummary(id))

    @DeleteMapping("/games/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteGame(@PathVariable id: UUID) = gameService.deleteGame(id)

    @GetMapping("/games/{id}/cover")
    fun cover(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (bytes, mime) = gameService.findCover(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mime))
            .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
            .body(bytes)
    }

    /**
     * The spoken summary. Synthesised on first request and cached thereafter, so
     * replaying it — which is the point of the feature — is instant and free.
     */
    @GetMapping("/games/{id}/summary/audio")
    fun summaryAudio(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val audio = audioService.getOrCreateAudio(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(audio.mimeType))
            // The hash identifies the recording (summary + voice). The URL never
            // changes, so the browser must revalidate on every play: with a
            // max-age it kept playing a stale recording after the summary or the
            // voice changed. An unchanged recording costs only a 304.
            .eTag("\"${audio.summaryHash}\"")
            .cacheControl(CacheControl.noCache().cachePrivate())
            .body(audio.audioData)
    }

    // ------------------------------------------------------------------- chat

    @GetMapping("/games/{id}/chat")
    fun chatHistory(@PathVariable id: UUID): List<ChatMessageResponse> =
        chatService.history(id).map(ChatMessageResponse::from)

    @PostMapping("/games/{id}/chat")
    fun ask(@PathVariable id: UUID, @RequestBody request: AskRequest): ChatMessageResponse =
        ChatMessageResponse.from(chatService.ask(id, request.question))

    @DeleteMapping("/games/{id}/chat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun clearChat(@PathVariable id: UUID) = gameService.clearChat(id)
}
