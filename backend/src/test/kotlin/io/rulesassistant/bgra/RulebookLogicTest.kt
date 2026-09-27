package io.rulesassistant.bgra

import io.rulesassistant.bgra.groq.RateLimitDuration
import io.rulesassistant.bgra.groq.TokenBudget
import io.rulesassistant.bgra.service.PageChunker
import io.rulesassistant.bgra.service.PageOrder
import io.rulesassistant.bgra.service.PageSelector
import io.rulesassistant.bgra.service.PrintedPageNumber
import io.rulesassistant.bgra.service.RulebookPage
import io.rulesassistant.bgra.service.SummaryLength
import io.rulesassistant.bgra.service.SummaryNotes
import io.rulesassistant.bgra.service.SummaryPlanner
import io.rulesassistant.bgra.service.SummarySection
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintedPageNumberTest {

    @Test
    fun `page number line is split off the transcript`() {
        val (number, text) = PrintedPageNumber.split("OLDALSZÁM: 18\n\n# 1) ODETTE, AZ ŐR\n\nSzöveg")
        assertEquals(18, number)
        assertEquals("# 1) ODETTE, AZ ŐR\n\nSzöveg", text)
    }

    @Test
    fun `spreads, emphasis and missing numbers are tolerated`() {
        assertEquals(18, PrintedPageNumber.split("**OLDALSZÁM:** 18–19\n\nSzöveg").first)
        val (none, text) = PrintedPageNumber.split("OLDALSZÁM: nincs\n\n# A JÁTÉK CÉLJA")
        assertNull(none)
        assertEquals("# A JÁTÉK CÉLJA", text)
    }

    @Test
    fun `a transcript without the line is kept whole`() {
        val (number, text) = PrintedPageNumber.split("# A JÁTÉK VÉGE\n\n18 pont")
        assertNull(number)
        assertEquals("# A JÁTÉK VÉGE\n\n18 pont", text)
    }
}

class PageOrderTest {

    private fun page(upload: Int, printed: Int?) = RulebookPage(upload, printed, "oldal $upload")

    @Test
    fun `a rulebook photographed back to front is reversed`() {
        // The real case: 1.jpg held printed pages 18-19, 8.jpg the opening chapter.
        val uploaded = listOf(18, 16, 14, 12, 10, 8, 6, 4).mapIndexed { i, n -> page(i + 1, n) }
        val arranged = PageOrder.arrange(uploaded)
        assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1), arranged.map { it.uploadNumber })
    }

    @Test
    fun `an unnumbered page stays behind the page it followed in the book`() {
        // Upload is reversed; upload 3 has no number and physically follows upload 4.
        val uploaded = listOf(page(1, 6), page(2, 5), page(3, null), page(4, 3), page(5, 2))
        val arranged = PageOrder.arrange(uploaded).map { it.uploadNumber }
        assertEquals(listOf(5, 4, 3, 2, 1), arranged)
    }

    @Test
    fun `shuffled pages are sorted by printed number`() {
        val uploaded = listOf(page(1, 3), page(2, 1), page(3, 4), page(4, 2))
        assertEquals(listOf(2, 4, 1, 3), PageOrder.arrange(uploaded).map { it.uploadNumber })
    }

    @Test
    fun `too few numbers leave the upload order alone`() {
        val uploaded = listOf(page(1, 9), page(2, null), page(3, null), page(4, 2), page(5, null))
        assertEquals(listOf(1, 2, 3, 4, 5), PageOrder.arrange(uploaded).map { it.uploadNumber })
    }
}

class PageSelectorTest {

    private val pages = listOf(
        RulebookPage(1, 1, "A játék célja: juttasd el a szerelmeslevelet a hercegnőhöz. " + "x".repeat(400)),
        RulebookPage(2, 2, "Előkészületek: keverjétek meg a paklit, egy lapot tegyetek félre. " + "y".repeat(400)),
        RulebookPage(3, 3, "A szobalány: eldobása után a következő körödig védett vagy. " + "z".repeat(400)),
        RulebookPage(4, 4, "A forduló vége: ha kifogy a húzópakli, a legnagyobb lap nyer. " + "w".repeat(400)),
    )

    @Test
    fun `everything is sent when it fits`() {
        val selection = PageSelector.select(pages, listOf(PageSelector.Query("bármi", 1.0)), 100_000)
        assertEquals(4, selection.pages.size)
        assertEquals(0, selection.omitted)
    }

    @Test
    fun `the relevant page wins over the first page when space is short`() {
        val budget = pages[0].formattedLength + 10
        val selection = PageSelector.select(
            pages, listOf(PageSelector.Query("Mi történik, ha kifogyott a húzópakli?", 1.0)), budget,
        )
        assertEquals(listOf(4), selection.pages.map { it.uploadNumber })
        assertEquals(3, selection.omitted)
    }

    @Test
    fun `selected pages keep book order and leftover room is filled`() {
        val budget = pages[0].formattedLength * 2 + 10
        val selection = PageSelector.select(
            pages, listOf(PageSelector.Query("védett a szobalány?", 1.0)), budget,
        )
        // Page 3 matches; the remaining room goes to the first page in book order.
        assertEquals(listOf(1, 3), selection.pages.map { it.uploadNumber })
    }
}

class SummaryPlanningTest {

    @Test
    fun `tagged notes are parsed into sections, tolerating markdown and accents`() {
        val notes = """
            **@CEL**
            - Juttasd el a levelet a hercegnőhöz.
            ### @ELEMEK
            - 1) Őr: tippelj egy lapra.
            @VÉGE: - A forduló végén a legnagyobb lap nyer.
        """.trimIndent()
        val parsed = SummaryNotes.parse(notes)
        assertEquals(setOf(SummarySection.GOAL, SummarySection.ELEMENTS, SummarySection.ENDING), parsed.keys)
        assertTrue(parsed.getValue(SummarySection.ENDING).startsWith("A forduló végén"))
    }

    @Test
    fun `notes from several slices merge section by section in slice order`() {
        val merged = SummaryNotes.merge(
            listOf(
                mapOf(SummarySection.ELEMENTS to "- 1) Őr"),
                mapOf(SummarySection.ELEMENTS to "- 2) Pap", SummarySection.GOAL to "- cél"),
            ),
        )
        assertEquals("- 1) Őr\n- 2) Pap", merged[SummarySection.ELEMENTS])
        assertEquals(listOf(SummarySection.GOAL, SummarySection.ELEMENTS), merged.keys.toList())
    }

    @Test
    fun `small notes are written in a single call`() {
        val notes = mapOf(SummarySection.GOAL to "- cél", SummarySection.ENDING to "- vége")
        val jobs = SummaryPlanner.plan(notes, 400) { 10_000 }
        assertEquals(1, jobs.size)
        assertEquals(listOf(SummarySection.GOAL, SummarySection.ENDING), jobs.single().sections)
    }

    @Test
    fun `an oversized section is split into continuations without losing lines`() {
        val cards = (1..200).joinToString("\n") { "- $it) kártya: hosszú hatásleírás a kártyához" }
        val notes = mapOf(SummarySection.GOAL to "- cél", SummarySection.ELEMENTS to cards)
        val jobs = SummaryPlanner.plan(notes, 2000) { 3_000 }

        val elementJobs = jobs.filter { SummarySection.ELEMENTS in it.sections }
        assertTrue(elementJobs.size > 1)
        assertEquals(false, elementJobs.first().continuation)
        assertTrue(elementJobs.drop(1).all { it.continuation })
        (1..200).forEach { n -> assertTrue(elementJobs.any { "- $n) kártya" in it.notes }, "card $n lost") }
    }

    @Test
    fun `summary length grows with the rulebook but sublinearly`() {
        val short = SummaryLength.targetWords(2_000)
        val eightPages = SummaryLength.targetWords(10_000)
        val long = SummaryLength.targetWords(120_000)
        assertTrue(short in 150..300, "short: $short")
        assertTrue(eightPages in 400..650, "8 pages: $eightPages")
        assertTrue(long in 1500..2500, "long: $long")
        assertTrue(long.toDouble() / eightPages < 120_000.0 / 10_000)
    }

    @Test
    fun `pages are chunked on page boundaries within the limit`() {
        val pages = (1..6).map { RulebookPage(it, it, "s".repeat(900)) }
        val chunks = PageChunker.chunk(pages, 2_000)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { chunk -> chunk.sumOf { it.formattedLength } <= 2_000 })
        assertEquals((1..6).toList(), chunks.flatten().map { it.uploadNumber })
    }
}

class RateLimitTest {

    @Test
    fun `groq durations are parsed`() {
        assertEquals(7_660, RateLimitDuration.toMillis("7.66s"))
        assertEquals(179_560, RateLimitDuration.toMillis("2m59.56s"))
        assertEquals(120, RateLimitDuration.toMillis("120ms"))
        assertNull(RateLimitDuration.toMillis("soon"))
        assertNull(RateLimitDuration.toMillis(null))
    }

    @Test
    fun `the budget waits only as long as the server says it must`() {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val budget = TokenBudget(8_000, clock = { now }, sleeper = { sleeps += it; now += it })

        budget.reserve(5_000, "first")
        assertTrue(sleeps.isEmpty(), "a full bucket must not wait")

        // Server: 1,000 left, full again in 7 s -> refills at 1 token/ms.
        budget.syncFromServer(remainingTokens = 1_000, resetMs = 7_000)
        budget.reserve(5_000, "second")
        assertEquals(4_000L, sleeps.sum())
    }

    @Test
    fun `without server hints the bucket refills at the nominal rate`() {
        var now = 0L
        val budget = TokenBudget(6_000, clock = { now }, sleeper = { now += it })
        budget.reserve(6_000, "drain")
        budget.reserve(3_000, "half")
        // 3,000 tokens at 6,000/min is 30 s.
        assertEquals(30_000L, now)
    }
}

class SummaryFormattingTest {

    @Test
    fun `hard line breaks become paragraphs`() {
        val fixed = io.rulesassistant.bgra.service.SummaryFormatting.normaliseParagraphs(
            "## A kártyák\n\nAz egyes az őr.  \nA kettes a pap.  \nA hármas a báró.",
        )
        assertEquals("## A kártyák\n\nAz egyes az őr.\n\nA kettes a pap.\n\nA hármas a báró.", fixed)
    }
}
