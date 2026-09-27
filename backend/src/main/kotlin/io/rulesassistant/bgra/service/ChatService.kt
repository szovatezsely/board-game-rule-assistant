package io.rulesassistant.bgra.service

import io.rulesassistant.bgra.config.IngestionProperties
import io.rulesassistant.bgra.domain.ChatMessage
import io.rulesassistant.bgra.domain.ChatRole
import io.rulesassistant.bgra.domain.GameStatus
import io.rulesassistant.bgra.groq.GroqClient
import io.rulesassistant.bgra.groq.Prompts
import io.rulesassistant.bgra.groq.TokenEstimator
import io.rulesassistant.bgra.repository.ChatRepository
import io.rulesassistant.bgra.repository.GameRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

class GameNotReadyException(message: String) : RuntimeException(message)

@Service
class ChatService(
    private val games: GameRepository,
    private val chats: ChatRepository,
    private val groq: GroqClient,
    private val properties: IngestionProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val MAX_QUESTION_CHARS = 2000

        /**
         * Most recent question-and-answer pairs replayed to the model. Enough for
         * follow-ups ("és ha mégis elfogy?") without crowding out the rulebook,
         * which is by far the more valuable half of the context.
         */
        const val MAX_EXCHANGES = 6

        /** Upper bound on the tokens the replayed conversation may take. */
        const val HISTORY_TOKENS = 1_400

        /** Older answers are shortened to this; the latest one is kept whole. */
        const val OLDER_ANSWER_CHARS = 700

        /** Weight of earlier questions when picking pages, relative to the current one. */
        const val EARLIER_QUESTION_WEIGHT = 0.5

        /** Headroom for the framing that surrounds the rulebook in the system prompt. */
        const val SYSTEM_FRAMING_TOKENS = 250
    }

    fun history(gameId: UUID): List<ChatMessage> {
        requireGame(gameId)
        return chats.findHistory(gameId)
    }

    /**
     * Answers [question] from the rulebook and records both sides of the exchange.
     *
     * The question is stored before the model is called so the transcript stays
     * ordered, and removed again if answering fails: the UI puts a failed question
     * back into the input box for another try, and a stored copy would otherwise
     * show up twice — and be replayed to the model as an unanswered turn.
     */
    fun ask(gameId: UUID, question: String): ChatMessage {
        val game = requireGame(gameId)
        val trimmed = question.trim()

        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("A kérdés nem lehet üres.")
        }
        if (trimmed.length > MAX_QUESTION_CHARS) {
            throw IllegalArgumentException("A kérdés túl hosszú (legfeljebb $MAX_QUESTION_CHARS karakter).")
        }
        if (game.status != GameStatus.READY) {
            throw GameNotReadyException(
                "A szabálykönyv feldolgozása még nem fejeződött be, ezért egyelőre nem tudok kérdésekre válaszolni.",
            )
        }

        val pages = games.loadRulebookPages(gameId, properties.maxContextChars)
        if (pages.isEmpty()) {
            throw GameNotReadyException("Ehhez a játékhoz nincs beolvasott szabálykönyv-szöveg.")
        }

        val exchanges = completedExchanges(chats.findHistory(gameId))
        val history = replayableHistory(exchanges)
        val historyTokens = history.sumOf { groq.estimateTextTokens(it.second) + 4 }

        // Everything shares one request, capped at 8,000 tokens on the free tier
        // including the reserved answer: the system prompt, the conversation, the
        // question, and whatever is left for the rulebook itself.
        val frameTokens = groq.estimateTextTokens(Prompts.qaSystemPrompt(game.title, "", null)) +
            SYSTEM_FRAMING_TOKENS
        val rulebookTokens = groq.answerPromptTokens() - frameTokens - historyTokens -
            groq.estimateTextTokens(trimmed)
        val budgetChars = TokenEstimator.charsFor(rulebookTokens)

        // Earlier questions count too, so a follow-up ("és a királlyal?") keeps
        // the pages its predecessor was answered from.
        val queries = buildList {
            add(PageSelector.Query(trimmed, 1.0))
            exchanges.takeLast(2).forEach { add(PageSelector.Query(it.first.content, EARLIER_QUESTION_WEIGHT)) }
        }
        val selection = PageSelector.select(pages, queries, budgetChars)
        val warning = if (selection.omitted > 0) {
            // The prompt is told about this so the model cannot claim the rulebook
            // is silent on something that was simply left out.
            log.info(
                "Rulebook for '{}' does not fit ({} of {} pages sent): {}",
                game.title, selection.pages.size, pages.size, selection.pages.joinToString { it.label },
            )
            Prompts.partialContextWarning(selection.pages.map { it.label }, selection.omitted)
        } else {
            null
        }

        val asked = chats.append(gameId, ChatRole.USER, trimmed)
        log.info(
            "Answering question about '{}' ({} pages, {} earlier messages)",
            game.title, selection.pages.size, history.size,
        )

        val answer = try {
            groq.answerQuestion(
                gameTitle = game.title,
                rulebookText = RulebookText.join(selection.pages),
                history = history,
                question = trimmed,
                contextWarning = warning,
            )
        } catch (e: Exception) {
            chats.delete(asked.id)
            throw e
        }

        // The conversation may have been cleared while the model was thinking.
        // Storing the answer then would leave it orphaned at the top of an
        // otherwise empty chat, so it is returned without being kept.
        if (!chats.exists(asked.id)) {
            log.info("Chat for '{}' was cleared while answering; not storing the answer", game.title)
            return ChatMessage(UUID.randomUUID(), gameId, ChatRole.ASSISTANT, answer, Instant.now())
        }
        return chats.append(gameId, ChatRole.ASSISTANT, answer)
    }

    /**
     * Pairs each question with the answer that followed it. A question with no
     * answer (a failed call from before failures were rolled back) is dropped,
     * since replaying it would make the model answer it again.
     */
    private fun completedExchanges(messages: List<ChatMessage>): List<Pair<ChatMessage, ChatMessage>> =
        messages.zipWithNext()
            .filter { (first, second) -> first.role == ChatRole.USER && second.role == ChatRole.ASSISTANT }

    /**
     * The most recent exchanges that fit [HISTORY_TOKENS], oldest first, as
     * role/content pairs. The newest answer is kept whole because follow-ups
     * usually refer to it; older ones are shortened.
     */
    private fun replayableHistory(exchanges: List<Pair<ChatMessage, ChatMessage>>): List<Pair<String, String>> {
        val kept = ArrayDeque<Pair<String, String>>()
        var tokens = 0
        exchanges.takeLast(MAX_EXCHANGES).asReversed().forEachIndexed { index, (question, answer) ->
            val answerText = if (index == 0) answer.content else shorten(answer.content)
            val cost = groq.estimateTextTokens(question.content) + groq.estimateTextTokens(answerText) + 8
            if (tokens + cost > HISTORY_TOKENS && kept.isNotEmpty()) return kept.toList()
            if (tokens + cost > HISTORY_TOKENS) {
                // Even the latest exchange alone is too long; keep its gist.
                kept.addFirst("assistant" to shorten(answer.content))
                kept.addFirst("user" to question.content)
                return kept.toList()
            }
            kept.addFirst("assistant" to answerText)
            kept.addFirst("user" to question.content)
            tokens += cost
        }
        return kept.toList()
    }

    private fun shorten(text: String): String =
        if (text.length <= OLDER_ANSWER_CHARS) text else text.take(OLDER_ANSWER_CHARS).trimEnd() + " …"

    private fun requireGame(gameId: UUID) =
        games.findById(gameId) ?: throw GameNotFoundException(gameId)
}
