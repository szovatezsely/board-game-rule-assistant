package io.rulesassistant.bgra.service

import kotlin.math.pow
import kotlin.math.roundToInt

/** The summary's sections, in teaching order, with the tag the notes step uses. */
enum class SummarySection(val tag: String, val heading: String) {
    GOAL("@CEL", "A játék célja"),
    SETUP("@ELOKESZULET", "Előkészületek"),
    GAMEPLAY("@MENET", "A játék menete"),
    ELEMENTS("@ELEMEK", "az elemek szakasza (például A kártyák)"),
    ENDING("@VEGE", "A játék vége és a győzelem"),
}

/**
 * How long the summary should be.
 *
 * Grows with the rulebook, but sublinearly: a two-page filler game needs a
 * couple of paragraphs, a forty-page euro needs more, but not twenty times
 * more — the point is to get people playing, not to read the book aloud.
 * Roughly: 2 pages ≈ 200 words, 8 pages ≈ 500 words, 40 dense pages ≈ 2,000.
 */
object SummaryLength {
    /** Hungarian averages about seven characters per word including spaces. */
    private const val CHARS_PER_WORD = 7.0
    private const val TOKENS_PER_WORD = 2.6

    /** Low-effort reasoning happens before the visible text and counts against the completion. */
    private const val REASONING_ALLOWANCE = 900

    fun targetWords(rulebookChars: Int): Int {
        val words = rulebookChars / CHARS_PER_WORD
        val target = 6.5 * words.coerceAtLeast(1.0).pow(0.6)
        return ((target / 10).roundToInt() * 10).coerceIn(150, 2500)
    }

    fun completionTokens(words: Int): Int =
        (words * TOKENS_PER_WORD).roundToInt() + REASONING_ALLOWANCE
}

/** Parses the tagged notes the map step writes back into sections. */
object SummaryNotes {
    private val TAG = Regex("""^\s*[*_#\s]*(@[A-ZÁÉÍÓÖŐÚÜŰ]+)[*_\s:]*(.*)$""", RegexOption.IGNORE_CASE)

    fun parse(notes: String): Map<SummarySection, String> {
        val byTag = SummarySection.entries.associateBy { it.tag }
        val collected = linkedMapOf<SummarySection, StringBuilder>()
        // Anything before the first tag is most likely general rules.
        var current = SummarySection.GAMEPLAY

        notes.lineSequence().forEach { line ->
            val match = TAG.find(line)
            val section = match?.let { byTag[normalise(it.groupValues[1])] }
            if (section != null) {
                current = section
                val rest = match.groupValues[2].trim().trimStart('—', '–', '-', ':', ' ')
                if (rest.isNotEmpty()) collected.getOrPut(current) { StringBuilder() }.appendLine(rest)
            } else if (line.isNotBlank()) {
                collected.getOrPut(current) { StringBuilder() }.appendLine(line.trimEnd())
            }
        }
        return collected.mapValues { it.value.toString().trim() }.filterValues { it.isNotEmpty() }
    }

    /** Joins the notes of every slice section by section, in slice order. */
    fun merge(slices: List<Map<SummarySection, String>>): Map<SummarySection, String> =
        SummarySection.entries.associateWith { section ->
            slices.mapNotNull { it[section] }.joinToString("\n")
        }.filterValues { it.isNotBlank() }

    private fun normalise(tag: String): String = tag.uppercase()
        .replace('Á', 'A').replace('É', 'E').replace('Í', 'I')
        .replace('Ó', 'O').replace('Ö', 'O').replace('Ő', 'O')
        .replace('Ú', 'U').replace('Ü', 'U').replace('Ű', 'U')
}

/** One reduce call: which sections it writes, from which notes, at what length. */
data class WriteJob(
    val sections: List<SummarySection>,
    val notes: String,
    val targetWords: Int,
    val continuation: Boolean,
)

/**
 * Packs the reduce step into as few calls as the per-request budget allows.
 *
 * Usually everything fits in one call. For a long rulebook, sections are written
 * separately, and a section whose notes alone are too large — the card list of a
 * card-heavy game — is split and written as continuations. Nothing is ever cut
 * off to fit, which is what the previous `take(limit)` on the combined notes did.
 */
object SummaryPlanner {
    private const val MIN_SECTION_WORDS = 60

    /**
     * [contextCharsFor] returns how many characters of notes fit in one request
     * that reserves the given number of completion tokens.
     */
    fun plan(
        notes: Map<SummarySection, String>,
        totalWords: Int,
        contextCharsFor: (completionTokens: Int) -> Int,
    ): List<WriteJob> {
        val present = SummarySection.entries.filter { !notes[it].isNullOrBlank() }
        if (present.isEmpty()) return emptyList()
        val totalChars = present.sumOf { notes.getValue(it).length }.coerceAtLeast(1)

        fun wordsFor(chars: Int) =
            (totalWords.toDouble() * chars / totalChars).roundToInt().coerceAtLeast(MIN_SECTION_WORDS)

        fun fits(chars: Int, words: Int) =
            chars + 400 <= contextCharsFor(SummaryLength.completionTokens(words))

        // One job per section, splitting any section too large for one request.
        val single = present.flatMap { section ->
            val text = notes.getValue(section)
            val words = wordsFor(text.length)
            if (fits(text.length, words)) {
                listOf(WriteJob(listOf(section), labelled(section, text), words, continuation = false))
            } else {
                splitLines(text) { chars -> fits(chars, wordsFor(chars)) }.mapIndexed { i, part ->
                    WriteJob(listOf(section), labelled(section, part), wordsFor(part.length), continuation = i > 0)
                }
            }
        }

        // Merge neighbouring whole sections while the combination still fits.
        val merged = mutableListOf<WriteJob>()
        single.forEach { job ->
            val last = merged.lastOrNull()
            if (last != null && !last.continuation && !job.continuation) {
                val notesText = last.notes + "\n\n" + job.notes
                val words = last.targetWords + job.targetWords
                if (fits(notesText.length, words)) {
                    merged[merged.lastIndex] = WriteJob(last.sections + job.sections, notesText, words, false)
                    return@forEach
                }
            }
            merged += job
        }
        return merged
    }

    private fun labelled(section: SummarySection, text: String) = "${section.tag}\n$text"

    /** Splits on line boundaries into the largest parts [fits] accepts. */
    private fun splitLines(text: String, fits: (Int) -> Boolean): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        text.lines().forEach { line ->
            if (current.isNotEmpty() && !fits(current.length + line.length + 1)) {
                parts += current.toString().trim()
                current.setLength(0)
            }
            current.appendLine(line)
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts
    }
}

/**
 * Groups pages into slices for the notes step, breaking only on page
 * boundaries so a rule is never cut mid-sentence. A single page larger than
 * the limit is split on paragraph boundaries as a last resort.
 */
object PageChunker {
    fun chunk(pages: List<RulebookPage>, limitChars: Int): List<List<RulebookPage>> {
        val chunks = mutableListOf<List<RulebookPage>>()
        var current = mutableListOf<RulebookPage>()
        var size = 0

        pages.flatMap { splitOversized(it, limitChars) }.forEach { page ->
            if (current.isNotEmpty() && size + page.formattedLength > limitChars) {
                chunks += current
                current = mutableListOf()
                size = 0
            }
            current += page
            size += page.formattedLength
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun splitOversized(page: RulebookPage, limitChars: Int): List<RulebookPage> {
        if (page.formattedLength <= limitChars) return listOf(page)
        val room = (limitChars - page.label.length - 16).coerceAtLeast(500)
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        page.text.split("\n\n").forEach { paragraph ->
            if (current.isNotEmpty() && current.length + paragraph.length + 2 > room) {
                pieces += current.toString()
                current.setLength(0)
            }
            // A single paragraph beyond the limit has to be cut somewhere.
            paragraph.chunked(room).forEach { piece ->
                if (current.isNotEmpty() && current.length + piece.length + 2 > room) {
                    pieces += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces.map { page.copy(text = it) }
    }
}

/** Tidies the summary text before it is stored. */
object SummaryFormatting {
    private val HARD_BREAK = Regex("""[ \t]{2,}\n""")
    private val EXTRA_BLANKS = Regex("""\n{3,}""")

    /**
     * Turns Markdown hard line breaks into paragraph breaks. The writer sometimes
     * separates card paragraphs with trailing double spaces instead of blank
     * lines, which renders as one crowded block on screen.
     */
    fun normaliseParagraphs(text: String): String =
        text.replace("\r\n", "\n")
            .replace(HARD_BREAK, "\n\n")
            .replace(EXTRA_BLANKS, "\n\n")
            .trim()
}
