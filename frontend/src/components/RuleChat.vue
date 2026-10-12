<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ApiError, api } from '@/api/client'
import type { ChatMessage } from '@/api/types'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{
  gameId: string
  ready: boolean
}>()

const messages = ref<ChatMessage[]>([])
const question = ref('')
const asking = ref(false)
const errorMessage = ref<string | null>(null)
const scroller = ref<HTMLElement | null>(null)

/** Seeds an empty chat so players see the kind of question that works well. */
const EXAMPLES = [
  'Mi történik, ha elfogy a húzópakli?',
  'Hányan játszhatják és mennyi ideig tart?',
  'Egy körben többször is építhetek?',
  'Döntetlen esetén ki nyer?',
]

async function scrollToEnd() {
  await nextTick()
  const element = scroller.value
  if (element) element.scrollTop = element.scrollHeight
}

async function loadHistory() {
  try {
    messages.value = await api.chatHistory(props.gameId)
    await scrollToEnd()
  } catch (e) {
    errorMessage.value = e instanceof ApiError ? e.message : 'A beszélgetés nem tölthető be.'
  }
}

async function ask(text?: string) {
  const value = (text ?? question.value).trim()
  if (!value || asking.value) return

  asking.value = true
  errorMessage.value = null
  question.value = ''

  // Show the question straight away; the id is replaced when the server answers.
  const optimistic: ChatMessage = {
    id: `pending-${Date.now()}`,
    role: 'user',
    content: value,
    createdAt: new Date().toISOString(),
  }
  messages.value = [...messages.value, optimistic]
  await scrollToEnd()

  try {
    await api.ask(props.gameId, value)
    // Reload rather than appending: this picks up the server's canonical ids
    // and keeps ordering correct if two tabs are asking at once.
    messages.value = await api.chatHistory(props.gameId)
  } catch (e) {
    messages.value = messages.value.filter((m) => m.id !== optimistic.id)
    question.value = value
    errorMessage.value = e instanceof ApiError ? e.message : 'A kérdés elküldése nem sikerült.'
  } finally {
    asking.value = false
    await scrollToEnd()
  }
}

async function clear() {
  // Blocked while a question is in flight: the answer would otherwise land in
  // the freshly cleared conversation (the backend also discards it).
  if (!messages.value.length || asking.value) return
  if (!window.confirm('Biztosan törlöd a beszélgetést? Az asszisztens ezután az előzményeket sem látja.')) {
    return
  }
  try {
    await api.clearChat(props.gameId)
    messages.value = []
  } catch (e) {
    errorMessage.value = e instanceof ApiError ? e.message : 'A beszélgetés törlése nem sikerült.'
  }
}

function onEnter(event: KeyboardEvent) {
  // Enter sends, Shift+Enter inserts a newline.
  if (event.shiftKey) return
  event.preventDefault()
  void ask()
}

onMounted(loadHistory)
</script>

<template>
  <section class="chat panel" aria-labelledby="chat-heading">
    <header class="chat__head">
      <div>
        <span class="eyebrow">Kérdezz a szabályokról</span>
        <h2 id="chat-heading" class="display--sm chat__title">Szabálykérdések</h2>
      </div>
      <button
        v-if="messages.length"
        type="button"
        class="btn btn--ghost btn--sm"
        :disabled="asking"
        @click="clear"
      >
        Törlés
      </button>
    </header>

    <p class="chat__disclaimer alert alert--info">
      A válaszok kizárólag a feltöltött szabálykönyvre épülnek. Ha a szabálykönyv
      nem rendelkezik egy helyzetről, az asszisztens ezt kimondja, és nem talál ki
      szabályt.
    </p>

    <div ref="scroller" class="chat__log" role="log" aria-live="polite">
      <div v-if="!messages.length" class="chat__empty">
        <p class="muted chat__empty-text">
          Tegyél fel egy kérdést, például egy szélsőséges helyzetről, amiről a
          szabálykönyv nem beszél egyértelműen.
        </p>
        <div class="chat__examples">
          <button
            v-for="example in EXAMPLES"
            :key="example"
            type="button"
            class="chat__example"
            :disabled="!props.ready || asking"
            @click="ask(example)"
          >
            {{ example }}
          </button>
        </div>
      </div>

      <article
        v-for="message in messages"
        :key="message.id"
        class="bubble"
        :class="`bubble--${message.role}`"
      >
        <span class="bubble__role">
          {{ message.role === 'user' ? 'Te' : 'Asszisztens' }}
        </span>
        <div
          v-if="message.role === 'assistant'"
          class="prose bubble__body"
          v-html="renderMarkdown(message.content)"
        ></div>
        <p v-else class="bubble__body bubble__body--plain">{{ message.content }}</p>
      </article>

      <div v-if="asking" class="bubble bubble--assistant bubble--thinking">
        <span class="bubble__role">Asszisztens</span>
        <div class="bubble__thinking">
          <span class="spinner" aria-hidden="true"></span>
          <span>Keresem a választ a szabálykönyvben…</span>
        </div>
      </div>
    </div>

    <p v-if="errorMessage" class="alert alert--error chat__error">{{ errorMessage }}</p>

    <form class="chat__form" @submit.prevent="ask()">
      <label class="sr-only" for="chat-question">Kérdés a szabályokról</label>
      <textarea
        id="chat-question"
        v-model="question"
        class="textarea"
        rows="2"
        maxlength="2000"
        :disabled="!props.ready || asking"
        :placeholder="
          props.ready
            ? 'Írd be a kérdésedet… (Enter: küldés, Shift+Enter: új sor)'
            : 'A szabálykönyv feldolgozása még folyamatban van…'
        "
        @keydown.enter="onEnter"
      ></textarea>
      <button
        type="submit"
        class="btn btn--primary"
        :disabled="!props.ready || asking || !question.trim()"
      >
        Kérdezek
      </button>
    </form>
  </section>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  padding: 26px;
  gap: 18px;
  /* Sits beside the summary on desktop; the log scrolls inside this height. */
  max-height: calc(100vh - var(--header-height) - 48px);
  position: sticky;
  top: calc(var(--header-height) + 24px);
}

.chat__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.chat__title {
  margin-top: 8px;
}

.chat__disclaimer {
  margin: 0;
  font-size: 0.82rem;
}

.chat__log {
  flex: 1;
  min-height: 220px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-right: 6px;
}

.chat__empty {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.chat__empty-text {
  margin: 0;
  font-size: 0.9rem;
}

.chat__examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chat__example {
  text-align: left;
  padding: 8px 14px;
  border-radius: 999px;
  font-size: 0.84rem;
  color: var(--text-dim);
  background: var(--surface-alt);
  border: 1px solid var(--line);
  transition:
    border-color var(--transition),
    background var(--transition),
    color var(--transition);
}

.chat__example:hover:not(:disabled) {
  border-color: var(--green-line);
  background: var(--green-veil);
  color: var(--text);
}

.chat__example:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.bubble {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.bubble__role {
  font-size: 0.64rem;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.bubble__body {
  font-size: 0.93rem;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
}

.bubble__body--plain {
  margin: 0;
  white-space: pre-wrap;
}

.bubble--user {
  align-items: flex-end;
}

.bubble--user .bubble__body {
  max-width: 88%;
  background: var(--green-veil);
  border: 1px solid var(--green-line);
  color: var(--text);
  border-bottom-right-radius: 4px;
}

.bubble--assistant .bubble__body {
  background: var(--surface-alt);
  border: 1px solid var(--line);
  border-left: 3px solid var(--green);
  border-bottom-left-radius: 4px;
}

.bubble__thinking {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  background: var(--surface-alt);
  border: 1px dashed var(--line-strong);
  font-size: 0.88rem;
  color: var(--text-dim);
}

.chat__error {
  margin: 0;
}

.chat__form {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  border-top: 1px solid var(--line);
  padding-top: 18px;
}

.chat__form .textarea {
  flex: 1;
}

@media (max-width: 1080px) {
  .chat {
    position: static;
    max-height: none;
  }

  .chat__log {
    max-height: 460px;
  }
}

@media (max-width: 700px) {
  .chat {
    padding: 22px 18px;
  }

  .chat__form {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
