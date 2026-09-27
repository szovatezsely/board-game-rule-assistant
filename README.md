# Társasjáték-szabály asszisztens

Web application for turning photographed board game rulebooks into something you
can actually use at the table: a spoken Hungarian summary you can replay as often
as you like, and a chat that answers rules questions **strictly from the rulebook
you uploaded**.

Built for Hungarian rulebooks end to end — the transcription, the summary, the
narration voice and the answers are all Hungarian.

---

## What it does

1. **Add a rulebook.** Photograph every page and upload the images. The upload
   list sorts by filename naturally (`2.jpg` before `10.jpg`), and pages can be
   moved or the whole order reversed before processing starts.
2. **The pages get read.** A vision model transcribes each page to Markdown,
   preserving headings, tables, and the labels printed on diagrams — those often
   carry rules of their own. It also reads the **printed page number**, which is
   used to put the pages back in book order when the photos were uploaded out of
   order. The transcript is stored exactly as read and never rewritten; see the
   note on OCR repair below for why.
3. **A summary is written** in the order you would teach the game: the goal,
   setup, how a turn works, every card or other game element in ascending value,
   and how the game ends and is won or lost. Its length scales with the rulebook
   — a few paragraphs for a filler game, more for a long one. Flavour text,
   credits and legal matter are dropped; the element list is always complete.
4. **Press play.** The summary is narrated by a Hungarian neural voice: Gemini
   TTS when a Gemini key is configured, offline Piper otherwise. The audio is
   cached server-side per voice, so replaying it is instant and costs nothing.
   Tempo is adjustable and you can restart from the beginning at any time.
5. **Ask about edge cases.** "What if the draw pile runs out mid-turn?" The
   assistant may reason lightly across sections and will show its derivation, but
   it will not invent a rule. When the rulebook is silent it says so, out loud:
   *"A szabálykönyv erre a helyzetre nem ad választ."*

### The groundedness guarantee

This is the design constraint the whole app is built around. A rules assistant
that confidently makes up a plausible rule is worse than one that admits
ignorance, because a made-up rule gets played. Three things enforce it:

- The full rulebook text is placed in the **system message**, outside the
  conversation history a user can steer.
- Every prompt states explicitly that outside knowledge — other games, general
  board-gaming convention — is off-limits, even when the model is confident.
- Answers restate the relevant rule in plain Hungarian and cite the printed page
  number where possible, so a claim can be checked against the physical book.
  They deliberately do not quote the transcript, which contains recognition
  noise (see below).

Light reasoning is allowed and encouraged, because most genuine edge-case
questions are answered by combining two separate rules. The model is told to show
that chain ("A szabálykönyv szerint …, továbbá …, ezért …") rather than assert a
conclusion.

---

## Technology

| Layer | Choice | Why |
| --- | --- | --- |
| Frontend | Vue 3 + TypeScript, Vite, Pinia, Vue Router | SPA with polling-driven progress; strict type checking in the build |
| Backend | Kotlin + Spring Boot 3.5 (Java 17) | Plain Spring JDBC over an ORM — see below |
| Database | PostgreSQL 17 + Flyway | Rulebooks, transcripts, chat history and cached audio |
| Page reading | Groq `qwen/qwen3.8-27b` | The one image-capable model on Groq's current catalogue; handles Hungarian diacritics well |
| Summary | Google `gemini-3.8-flash` when `GEMINI_API_KEY` is set, else Groq `qwen/qwen3.8-27b` | Gemini won a side-by-side Hungarian quality test clearly (below) |
| Q&A | Groq `openai/gpt-oss-120b`, `reasoning_effort: low` | Reasoning helps combine separate rules |
| Speech | Gemini TTS `gemini-3.8-flash-tts` (voice Kore), falling back to Piper `hu_HU-anna-medium` | Gemini sounds natural; Piper is fully offline but noticeably synthetic |
| Serving | nginx (static + API reverse proxy) | Single origin, so the frontend needs no API host config |

### Notable decisions

**Reasoning is switched off for transcription.** Copying text off a page is
mechanical, and the vision model is a reasoning model by default. On a dense
two-column page it spent its entire completion allowance deliberating and
returned an empty body (`finish_reason: length`). With `reasoning_effort: none`
the same page costs 2,700 tokens instead of 7,500 and transcribes better. The
text model keeps `low` reasoning, which it needs for combining rules.

**The summary is structured for teaching, with a mandatory element list.** The
first version imposed a fixed template with no slot for the card reference, so
the card reference — which *is* the rules of a card game — was lost. The second
version mirrored the rulebook's own chapters instead, which fixed that but
inherited the upload order: a back-to-front upload produced a summary that opened
with the card reference and never clearly stated the goal. The current structure
is fixed again (goal, setup, turn, elements, end and victory), but the elements
section is mandatory whenever the game has elements and must list every one of
them in ascending value.

**The summary is written by Gemini when a key is set.** Several options were
compared on the same transcripts with the same prompt. `gemini-3.8-flash` was
the only model that got the goal, the game terms and the rulebook's idioms right
and also wrote grammatical Hungarian ("egy játékos hiján mindenki kiesett"
became "egyetlen játékos kivételével már mindenki kiesett"). Its million-token
context takes the whole rulebook in one call, so there is no slicing.

The free tier is frequently overloaded (HTTP 503; in testing, three attempts
failed before the fourth succeeded), and the overload is per model and comes in
spikes. So `gemini-3.8-flash`, the clearly better writer, gets three tries with
growing pauses (about 45 seconds of waiting), then `gemini-3.5-flash` (also
checked for Hungarian quality) gets two short ones, and only then does the
summary fall back to Groq. Pro models have no free quota. On the free tier
Google may use the requests to improve its products.

**Without Gemini, the summary is written by Qwen, not gpt-oss.** In the same
test, `gpt-oss-120b` at low effort misstated the game's goal, kept recognition
errors ("az ór" for "az őr"), used the wrong verbs and invented a tie-break rule.
At medium effort it no longer fits in an 8k request and was cut off.
`qwen/qwen3.8-27b` got the goal and the wording right and invented nothing,
though it stays closer to the source text and so occasionally carries its noise
through. A Groq summary that is cut off by the output limit is rewritten section
by section rather than stored incomplete.

Two other approaches were tried and removed. A second Groq pass that checked
the draft against the source and corrected its Hungarian fixed some grammar but
left the misreadings in place and added new errors. Writing the summary in
English and translating it with DeepL (with a glossary of the rulebook's terms)
improved grammar, but mixed up "kör" (turn) and "forduló" (round),
mistranslated the player's role, and gave glossary terms broken articles:
errors that change the rules.

**Pages are reordered by their printed page numbers.** Photos are often not in
book order. When a card's text runs onto the next page and the pages are
processed in the wrong order, that text detaches from its heading and the model
reports the card as undocumented. The vision model reports the printed page
number of each photo, and when at least half the pages have one, the text is
reassembled in book order (a reversed upload is detected as such). Answers cite
the printed number, which is what a player can look up in the physical book.

**The transcript is never rewritten.** Answers once quoted the raw transcript
verbatim, so recognition noise ended up inside quotation marks. The obvious fix —
a pass that repairs the OCR text once at ingestion — was built, tested, and
removed again, because it corrupted text that was already correct: `AJÁTEK CÉLJA`
(a missing space in `A JÁTÉK CÉLJA`) became `AJÁNDÉK CÉLJA`, and the correct
heading `AZ UDVARONCKÁRTYÁK` was split in two. Constraining it to word-level
edits and rejecting structural changes in code reduced but did not eliminate the
damage.

The lesson: a model asked to "repair" rules text will quietly improve things that
were not broken, and trading correctness for readability is the wrong trade here.
So the raw transcript stays the single source of truth, and readability is handled
where it belongs — the summary and answering prompts rewrite in their own words,
never quoting recognition noise. Heading typos are corrected in the summary only,
which is derived content, never in the stored transcript.

**Reading order matters more than character accuracy.** A photo of an open book
is a two-page spread, often multi-column. Read across the columns instead of down
them and a card's effect text lands under the wrong card heading — a
transcription that looks clean while asserting the wrong rule, which is far more
dangerous than a few garbled characters. The transcription prompt specifies the
column order explicitly and requires headings to stay bound to the text beneath
them. Photographing one page at a time avoids the problem entirely.

**`gpt-oss-120b` cannot read images.** It is text-only, so the pipeline needs a
separate vision step. A vision model was chosen over local Tesseract OCR because
photographed rulebook pages — glossy paper, two-column layouts, text baked into
diagrams — are exactly where classic OCR degrades, and garbled text poisons every
answer downstream.

**The whole rulebook goes into context where it fits.** Chunked retrieval's
characteristic failure is dropping the one clause that answers an edge case,
which is precisely the query this app exists to serve.

The free tier bounds this, though: a request caps at 8,000 tokens *including* the
reserved answer, the system prompt and the conversation so far, which leaves room
for only a handful of pages. So:

- **Summaries** are not affected when Gemini writes them. On the Groq
  fallback, longer rulebooks are summarised map-reduce style: each slice of
  pages is sorted into tagged notes (goal, setup, turn, elements, end), and each
  summary section is then written from the notes of every slice that mentioned
  it. Sections too long for one call are written in continuations, so nothing is
  cut off to fit. The notes are written while later pages are still being read.
- **Questions** get the most relevant pages when the book does not fit, chosen
  by a lexical match on the question and the two before it (a five-letter prefix
  match copes with Hungarian suffixes; Groq offers no embedding model). The
  prompt names the pages included and says others were left out, so the
  assistant says the book was only partly available rather than claiming it is
  silent on a rule that was simply not sent.

**Hand-written SQL instead of JPA.** Pages and cached audio are multi-megabyte
`BYTEA` columns. An ORM aggregate mapper drags those bytes into every library
listing, and `@Lob` lazy loading is unreliable in practice. Each query selects
exactly the columns its caller needs — note `cover_image IS NOT NULL AS has_cover`,
which reports cover presence without transferring the image.

**Ingestion is paced by the server's own rate-limit headers.** Groq meters
tokens per minute per model. The first version tracked a 60-second sliding window
of its own pessimistic estimates, so each page reserved ~6,500 tokens and then
waited a full minute although the vision call took about a second. The budget is
now a refilling bucket per model, re-synchronised after every call from
`x-ratelimit-remaining-tokens` and `x-ratelimit-reset-tokens`. Page transcription
reserves a modest completion budget first and re-reads only a page that actually
runs out, instead of reserving for the densest page every time. Rulebooks are
still processed one at a time; 429 responses are retried honouring `Retry-After`.

**Ingestion is resumable.** Each page transcript is committed as it arrives, and a
retry only requests pages still missing — a failure at page 30 of 40 never
re-pays for the first 29. Games left mid-flight by a container restart are marked
failed at startup, so the UI shows an actionable error instead of a spinner that
never resolves.

**Images are downscaled before upload.** Phone photos are 8–12 MP; rulebook text
stays perfectly legible at 1600px on the long edge, and vision token cost scales
with resolution. EXIF orientation is honoured, because a page handed to the model
sideways transcribes badly. Downscaling is what makes a 40-page rulebook
affordable on a free tier.

---

## Running it

### 1. Get a Groq API key

Free, and no credit card required: **<https://console.groq.com/keys>**

Measured from the `x-ratelimit-*` headers, the free tier gives **1,000 requests
per day and 8,000 tokens per minute**, per model. A 30-page rulebook costs about
32 requests, so daily volume is a non-issue.

The tokens-per-minute ceiling is the real constraint, and it shapes the design.
Groq meters `prompt + max_completion_tokens` **at request time**, so no single
request may exceed 8,000 tokens no matter how large the model context window is,
and every page costs its image plus its reserved completion budget. The backend
follows the allowance Groq reports on each response and waits only as long as it
must. On the free tier, expect a couple of pages a minute; a paid tier raises the
ceiling, and setting `GROQ_MAX_TOKENS_PER_MINUTE` to match speeds everything up
proportionally.

### 2. Configure

```bash
cp .env.example .env
```

Then put your key in `.env`:

```
GROQ_API_KEY=gsk_...
```

The app starts without a key, but it will show a warning banner and refuse to
process uploads, rather than failing silently.

**Optional, recommended: better Hungarian summaries.** Add a free Gemini key
from <https://aistudio.google.com/apikey> as `GEMINI_API_KEY=...`. The summary is
then written by Gemini, which writes much better Hungarian than the Groq models.

The same Gemini key also gives you **a natural narration voice**: the summary is
read aloud by Gemini TTS instead of the offline Piper voice, which is
understandable but robotic. `GEMINI_TTS_VOICE` picks one of Gemini's prebuilt
voices (default `Kore`). Existing recordings are re-made automatically the next
time you press play, because cached audio is keyed by voice.

### 3. Start

```bash
docker compose up --build
```

Open **<http://localhost:8081>**.

The first build takes a few minutes: it compiles the Kotlin backend, builds the
frontend, and bakes the 63 MB Piper voice into the TTS image so no model is
downloaded at runtime.

### Stopping

```bash
docker compose down
```

Add `-v` to also drop the database volume and delete every stored rulebook.

---

## Using it

**Photographing pages.** Good light, page flat, whole page in frame. Sharpness
matters much more than resolution — the images are downscaled anyway. Number the
files so they sort correctly.

**Page order.** Numbered filenames still help, and the upload list lets you move
pages or reverse the whole order. If the page numbers are visible in the photos,
out-of-order uploads are also corrected automatically during processing.

**Shoot one page at a time.** Photographing an open book as a two-page spread is
the single biggest quality risk. Multi-column spreads make the vision model read
across columns, and a card effect can end up attached to the wrong card. The
transcription prompt now spells out the correct column order, which fixes most
cases, but one page per image is materially more reliable.

**If the transcription looks wrong,** use *Oldalak újraolvasása* on the game
page. It throws the stored transcripts away and reads every page again from the
uploaded images, then rebuilds the summary. *Összefoglaló újra* is the cheaper
option when only the summary is off — it reuses the transcripts and costs a
single call.

**Formats.** JPG and PNG. iPhone HEIC files must be converted first; the app
rejects them at upload with a clear message rather than failing mid-ingestion.

**Progress.** Transcription accounts for 90% of the progress bar and the summary
the last 10%, so the bar does not sit at 100% while the summary is still being
written. Processing runs in the background — you can close the tab.

**First playback takes a moment.** Gemini renders speech about six times
faster than realtime (measured: 5.4 minutes of audio in 50 s), normally in a
single request. Piper runs at roughly 2.5× faster than realtime on a typical CPU
(measured: 21.8 s of audio in 8.8 s), so a five-minute summary needs about two
minutes the first time. Every replay afterwards is served from the server-side
cache in well under a second. The player says so while it waits.

**Gemini narration is limited to 10 recordings a day.** The free tier allows
only ten requests per day for a TTS model, and a summary normally takes one.
When the day's quota is spent, the summary is read by Piper instead. That
recording is cached and replayed as is until the quota resets at midnight
Pacific time (09:00 in Hungary). The app treats Pacific time as UTC-8 all year,
so in summer it tries Gemini again from 10:00; the first play after that
re-records the summary with Gemini automatically.

**Follow-up questions work.** The last few questions and answers are sent with
each new question, so "és ha mégis elfogy?" is understood in context. *Törlés*
clears that history on the server too: the assistant starts the next question
with no memory of the old conversation.

---

## Local development

Backend and TTS in containers, frontend on Vite with hot reload:

```bash
docker compose up --build db tts backend
```

```bash
cd frontend && npm install && npm run dev
```

The dev server runs on <http://localhost:5173> and proxies `/api` to the backend
on port 8080, so the same relative URLs work in every environment.

Backend tests:

```bash
cd backend && ./gradlew test
```

---

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/status` | Whether a Groq key is configured, and which models are in use |
| `GET` | `/api/games` | Library listing |
| `POST` | `/api/games` | Upload pages (multipart: `files[]`, optional `title`) |
| `GET` | `/api/games/{id}` | Detail, including summary and per-page state |
| `DELETE` | `/api/games/{id}` | Delete a game and everything belonging to it |
| `POST` | `/api/games/{id}/retry` | Resume a failed ingestion, keeping existing transcripts |
| `POST` | `/api/games/{id}/rescan` | Discard transcripts and re-read every page (one call per page) |
| `POST` | `/api/games/{id}/summary/regenerate` | Rewrite the summary from stored transcripts |
| `GET` | `/api/games/{id}/cover` | Cover thumbnail |
| `GET` | `/api/games/{id}/summary/audio` | Narration WAV (synthesised once, then cached) |
| `GET` | `/api/games/{id}/chat` | Chat history |
| `POST` | `/api/games/{id}/chat` | Ask a question |
| `DELETE` | `/api/games/{id}/chat` | Clear chat history |

Errors return `{ "message": "<Hungarian text>", "code": "<MACHINE_CODE>" }`, so
the UI can show the message directly and branch on the code.

---

## Layout

```
backend/    Kotlin + Spring Boot API, Flyway migrations, Groq, Gemini and TTS clients
frontend/   Vue 3 SPA, nginx config for static serving and API proxying
tts/        Gemini TTS / Piper wrapper behind a small HTTP API
docker-compose.yml
.env.example
```

---

## Configuration reference

Every value below is optional except `GROQ_API_KEY`; see `.env.example`.

| Variable | Default | Notes |
| --- | --- | --- |
| `GROQ_API_KEY` | *(none)* | **Required.** |
| `APP_PORT` | `8081` | Host port for the web UI |
| `GROQ_TEXT_MODEL` | `openai/gpt-oss-120b` | Q&A |
| `GROQ_VISION_MODEL` | `qwen/qwen3.8-27b` | Must accept image input; checked at startup |
| `GROQ_REASONING_FORMAT` | `hidden` | Keeps chain-of-thought out of transcripts |
| `GROQ_REASONING_EFFORT` | `low` | Text model: `low`, `medium` or `high` |
| `GROQ_VISION_REASONING_EFFORT` | `none` | Vision model: `none` or `default`. Leave it off |
| `GROQ_MIN_CALL_INTERVAL` | `2500ms` | Floor between calls, on top of token pacing |
| `GROQ_MAX_TOKENS_PER_MINUTE` | `8000` | Free-tier ceiling; raise on a paid tier for big speedups |
| `GEMINI_API_KEY` | *(none)* | Recommended. Gemini writes the summary; Groq is the fallback |
| `GEMINI_MODELS` | `gemini-3.8-flash,gemini-3.5-flash` | Tried in order when one is overloaded; Pro models have no free quota |
| `GROQ_SUMMARY_MODEL` | `qwen/qwen3.8-27b` | Writes the summary when Gemini is not configured or unavailable; blank uses `GROQ_TEXT_MODEL` |
| `GROQ_SUMMARY_REASONING_EFFORT` | `none` | `none`/`default` for Qwen, `low`/`medium`/`high` for gpt-oss |
| `GROQ_VISION_MAX_TOKENS` | `1000` | Completion budget per page; counts against the TPM ceiling |
| `GROQ_VISION_MAX_TOKENS_DENSE` | `3500` | Budget for re-reading a page that ran out of the first |
| `GEMINI_TTS_MODEL` | `gemini-3.8-flash-tts` | Narration model; uses `GEMINI_API_KEY` |
| `GEMINI_TTS_VOICE` | `Kore` | A Gemini prebuilt voice, e.g. `Puck`, `Charon`, `Aoede` |
| `TTS_LENGTH_SCALE` | `1.06` | Piper only: above 1.0 speaks more slowly |
| `INGESTION_MAX_PAGES` | `80` | Page cap per rulebook |
| `INGESTION_MAX_IMAGE_EDGE` | `1600` | Long edge sent to the vision model |
| `LOG_LEVEL` | `INFO` | Application log level |

---

## Limitations

- **Images only, not PDFs.** A digital rulebook has to be exported to page images
  first.
- **Transcription quality bounds everything.** A blurry page produces a weak
  summary and weak answers. The detail screen shows how many pages were read;
  if something is off, *Oldalak újraolvasása* reads every page again.
- **Long rulebooks are slow on the free tier.** The 8,000 tokens-per-minute cap
  bounds page transcription to a couple of pages a minute. Processing is
  resumable and runs in the background. A paid tier lifts the ceiling; set
  `GROQ_MAX_TOKENS_PER_MINUTE` to match and it speeds up proportionally.
- **Questions on longer rulebooks see a selection of pages.** Only what fits in
  one 8,000-token request is sent, chosen by relevance to the question. The
  assistant is told which pages it has and says so instead of claiming the
  rulebook is silent. The summary is unaffected: it covers the whole book.
- **No authentication.** Anyone who can reach the port sees every rulebook.
  Intended for local or trusted-network use.
- **The offline voice is robotic.** Piper's Hungarian voices are all "medium"
  quality. With a Gemini key, narration uses Gemini TTS instead; that sends the
  summary text to Google.

---

## Styling

A light, editorial design system: `#00c95b` primary green with `#20a575` for
links and secondary accents, Inter for body text and Roboto Slab for display
type, an 18px/1.56 base, sharp-cornered surfaces, generous whitespace on a 128px
section rhythm, and small uppercase green labels carrying the accent work. The
tokens all live at the top of `frontend/src/assets/styles/main.css`, so
retheming means editing one block.

Fonts are bundled rather than loaded from a CDN, and only the latin and latin-ext
subsets are included — latin-ext is what carries the Hungarian **ő** and **ű**.
