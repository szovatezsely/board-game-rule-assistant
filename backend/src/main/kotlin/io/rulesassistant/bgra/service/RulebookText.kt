package io.rulesassistant.bgra.service

import kotlin.math.ln

/**
 * One transcribed page as model context.
 *
 * [uploadNumber] is the position the photo was uploaded at; [printedNumber] is
 * the page number printed in the book, when the vision model could read one.
 * The printed number is preferred in the label because it is what a player can
 * look up in the physical rulebook.
 */
data class RulebookPage(val uploadNumber: Int, val printedNumber: Int?, val text: String) {
    val label: String get() = printedNumber?.let { "$it. oldal" } ?: "$uploadNumber. kép"

    fun formatted(): String = "=== $label ===\n$text"

    /** Length once formatted, including the separator [RulebookText.join] adds. */
    val formattedLength: Int get() = formatted().length + 2
}

object RulebookText {
    fun join(pages: List<RulebookPage>): String = pages.joinToString("\n\n") { it.formatted() }
}

/**
 * Splits the `OLDALSZÁM: …` line the transcription prompt asks for off the top
 * of a transcript.
 *
 * Tolerant by design: the model sometimes wraps the line in Markdown emphasis or
 * writes a range for a two-page spread ("18–19"), and if it forgets the line
 * altogether the transcript is kept as it is with no page number.
 */
object PrintedPageNumber {
    private val LINE = Regex(
        """^\s*[*_#>\s]*OLDALSZ[ÁA]M[*_\s]*:[*_\s]*([^\n]*)(?:\n|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBER = Regex("""\d{1,4}""")

    fun split(raw: String): Pair<Int?, String> {
        val match = LINE.find(raw) ?: return null to raw.trim()
        val number = NUMBER.find(match.groupValues[1])?.value?.toIntOrNull()?.takeIf { it in 1..999 }
        return number to raw.removeRange(match.range).trim()
    }
}

/**
 * Puts pages back in book order using the printed page numbers.
 *
 * Photos routinely arrive out of order — shot from the back cover forwards, or
 * named so that `10.jpg` sorts before `2.jpg`. When a card's effect text runs
 * onto the next page, processing the pages in the wrong order detaches that
 * text from its heading, and the model then reports the card as undocumented.
 *
 * Only applied when at least half the pages carry a number, so a couple of
 * misread digits cannot scramble an otherwise correct upload. Pages without a
 * number stay attached behind the page they followed, after correcting for a
 * reversed upload.
 */
object PageOrder {
    fun arrange(pages: List<RulebookPage>): List<RulebookPage> {
        val numbered = pages.mapNotNull { it.printedNumber }
        if (numbered.size < 2 || numbered.size * 2 < pages.size) return pages

        val steps = numbered.zipWithNext()
        val descending = steps.count { (a, b) -> b < a }
        val ascending = steps.count { (a, b) -> b > a }
        val base = if (descending > ascending) pages.asReversed() else pages

        var lastKey = Int.MIN_VALUE
        val keyed = base.mapIndexed { index, page ->
            lastKey = page.printedNumber ?: lastKey
            Triple(lastKey, index, page)
        }
        return keyed.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}

/**
 * Chooses which pages accompany a question when the whole rulebook does not fit
 * in one request.
 *
 * The free tier caps a request at 8,000 tokens, which holds only a handful of
 * pages next to the prompt and the conversation. Taking the *first* pages — the
 * previous behaviour — meant most questions were answered from the goal and
 * setup chapters alone, while the card reference sat unseen at the back.
 *
 * This is lexical ranking, not embeddings (Groq offers none): words are reduced
 * to a five-letter prefix, which is crude but effective against Hungarian
 * suffixes ("kártyát", "kártyák", "kártyával" all match "kárty"). Rarer words
 * weigh more. Whatever budget is left after the best matches is filled with the
 * remaining pages in book order, so nothing is dropped that could have fitted.
 */
object PageSelector {
    data class Selection(val pages: List<RulebookPage>, val omitted: Int)

    /** A piece of text to match against, with its weight. */
    data class Query(val text: String, val weight: Double)

    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val STOP_WORDS = setOf(
        "akkor", "amikor", "amit", "annak", "azt", "csak", "ebben", "egy", "egyik", "ennek",
        "ezt", "hogy", "hogyan", "hány", "igen", "kell", "lehet", "meddig", "mennyi", "mert",
        "mikor", "milyen", "melyik", "még", "már", "mint", "mit", "mivel", "nem", "pedig",
        "sem", "van", "vagy", "után", "előtt", "alatt", "között", "neki", "nekem", "mindig",
    )

    fun select(pages: List<RulebookPage>, queries: List<Query>, budgetChars: Int): Selection {
        if (pages.sumOf { it.formattedLength } <= budgetChars) return Selection(pages, 0)

        val pageStems = pages.map { page -> stems(page.text).groupingBy { it }.eachCount() }
        val documentFrequency = pageStems.flatMap { it.keys }.groupingBy { it }.eachCount()
        val queryWeights = mutableMapOf<String, Double>()
        queries.forEach { query ->
            stems(query.text).toSet().forEach { stem ->
                queryWeights.merge(stem, query.weight, ::maxOf)
            }
        }

        val scores = pages.indices.associateWith { index ->
            queryWeights.entries.sumOf { (stem, weight) ->
                val tf = pageStems[index][stem] ?: 0
                if (tf == 0) 0.0
                else weight * ln(1.0 + pages.size.toDouble() / documentFrequency.getValue(stem)) * (1 + ln(tf.toDouble()))
            }
        }

        // Best matches first, then everything else in book order.
        val candidates = pages.indices.sortedWith(
            compareByDescending<Int> { scores.getValue(it) }.thenBy { it },
        )
        val chosen = mutableSetOf<Int>()
        var used = 0
        candidates.forEach { index ->
            val size = pages[index].formattedLength
            if (used + size <= budgetChars) {
                chosen += index
                used += size
            }
        }
        val kept = pages.filterIndexed { index, _ -> index in chosen }
        return Selection(kept, pages.size - kept.size)
    }

    internal fun stems(text: String): List<String> =
        WORD.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .map { it.take(5) }
            .toList()
}
