<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import { isInProgress } from '@/api/types'
import ProgressBar from '@/components/ProgressBar.vue'
import RuleChat from '@/components/RuleChat.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import SummaryPlayer from '@/components/SummaryPlayer.vue'
import { useGamesStore } from '@/stores/games'
import { formatDateTime } from '@/utils/markdown'

const props = defineProps<{ id: string }>()

const store = useGamesStore()
const router = useRouter()

const actionError = ref<string | null>(null)
const working = ref(false)
const confirmingDelete = ref(false)

const game = computed(() => store.detail)
const busy = computed(() => (game.value ? isInProgress(game.value.status) : false))
const ready = computed(() => game.value?.status === 'READY')
const transcribedPages = computed(
  () => game.value?.pages.filter((p) => p.transcribed).length ?? 0,
)

async function load() {
  await store.loadGame(props.id)
  if (game.value && isInProgress(game.value.status)) {
    store.startDetailPolling(props.id)
  }
}

async function run(action: () => Promise<unknown>) {
  working.value = true
  actionError.value = null
  try {
    await action()
  } catch (e) {
    actionError.value = e instanceof ApiError ? e.message : 'A művelet nem sikerült.'
  } finally {
    working.value = false
  }
}

const retry = () => run(() => store.retry(props.id))
// Re-reads every page: costs one Groq call per page, so it is confirmed by the
// button title rather than fired silently.
const rescan = () => run(() => store.rescan(props.id))
const regenerate = () => run(() => store.regenerateSummary(props.id))

async function remove() {
  await run(async () => {
    await store.deleteGame(props.id)
    await router.push({ name: 'library' })
  })
}

onMounted(load)

// Re-load when navigating straight from one game to another.
watch(
  () => props.id,
  () => {
    store.stopDetailPolling()
    void load()
  },
)

onUnmounted(() => store.stopDetailPolling())
</script>

<template>
  <div class="container game">
    <RouterLink to="/" class="game__back">← Vissza a könyvtárhoz</RouterLink>

    <p v-if="store.error && !game" class="alert alert--error">{{ store.error }}</p>

    <div v-if="store.loading && !game" class="game__loading">
      <span class="spinner" aria-hidden="true"></span>
      <span class="muted">Betöltés…</span>
    </div>

    <template v-else-if="game">
      <header class="game__head">
        <div class="game__headings">
          <div class="row wrap gap-12 game__badges">
            <StatusBadge :status="game.status" />
            <span class="tag">{{ game.pageCount }} oldal</span>
            <span class="tag">{{ transcribedPages }} beolvasva</span>
          </div>
          <h1 class="display game__title">{{ game.title }}</h1>
          <p class="muted game__timestamp">
            Feltöltve: {{ formatDateTime(game.createdAt) }}
          </p>
        </div>

        <div class="game__actions">
          <button
            v-if="ready"
            type="button"
            class="btn btn--ghost btn--sm"
            :disabled="working"
            @click="regenerate"
          >
            Összefoglaló újra
          </button>
          <button
            v-if="game.status === 'READY' || game.status === 'FAILED'"
            type="button"
            class="btn btn--ghost btn--sm"
            :disabled="working"
            title="Minden oldalt újra beolvas a feltöltött képekből. Oldalanként egy Groq hívás."
            @click="rescan"
          >
            Oldalak újraolvasása
          </button>
          <button
            v-if="game.status === 'FAILED'"
            type="button"
            class="btn btn--primary btn--sm"
            :disabled="working"
            @click="retry"
          >
            Újrapróbálás
          </button>
          <button
            v-if="!confirmingDelete"
            type="button"
            class="btn btn--danger btn--sm"
            :disabled="working"
            @click="confirmingDelete = true"
          >
            Törlés
          </button>
          <template v-else>
            <button
              type="button"
              class="btn btn--danger btn--sm"
              :disabled="working"
              @click="remove"
            >
              Biztosan törlöm
            </button>
            <button
              type="button"
              class="btn btn--ghost btn--sm"
              @click="confirmingDelete = false"
            >
              Mégsem
            </button>
          </template>
        </div>
      </header>

      <p v-if="actionError" class="alert alert--error game__alert">{{ actionError }}</p>

      <section v-if="busy" class="game__progress panel panel--flat">
        <span class="eyebrow">Feldolgozás</span>
        <p class="game__progress-text">
          A szabálykönyv oldalait egyenként olvassuk be, majd elkészül a magyar
          összefoglaló. Az ingyenes Groq keret miatt egy oldal nagyjából fél percig
          tart. A feldolgozás a háttérben fut, közben nyugodtan bezárhatod az oldalt.
        </p>
        <ProgressBar
          :percent="game.progressPercent"
          :label="game.statusDetail ?? 'Feldolgozás folyamatban'"
        />
      </section>

      <section v-else-if="game.status === 'FAILED'" class="game__failed alert alert--error">
        <strong>A feldolgozás nem sikerült.</strong>
        <p class="game__failed-text">
          {{ game.errorMessage ?? 'Ismeretlen hiba történt.' }}
        </p>
        <p class="game__failed-hint">
          A már beolvasott oldalak megmaradtak, ezért az újrapróbálás csak a
          hiányzó részeket dolgozza fel.
        </p>
      </section>

      <div class="game__layout">
        <SummaryPlayer :game-id="game.id" :summary="game.summary" />
        <RuleChat :game-id="game.id" :ready="ready" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.game {
  padding-top: 44px;
}

.game__back {
  display: inline-block;
  margin-bottom: 32px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--color-main);
}

.game__back:hover {
  color: var(--color-black);
}

.game__loading {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 60px 0;
}

.game__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  padding-bottom: 36px;
  border-bottom: 1px solid var(--color-offwhite);
  flex-wrap: wrap;
}

.game__headings {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.game__badges {
  margin-bottom: 2px;
}

.game__title {
  /* Just under the hero step, since a game name can be long. */
  font-size: clamp(1.35rem, 2.2vw, 1.8rem);
  word-break: break-word;
}

.game__timestamp {
  margin: 0;
  font-size: 0.84rem;
}

.game__actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.game__alert {
  margin-top: 28px;
}

.game__progress {
  margin-top: 36px;
  padding: 30px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.game__progress-text {
  margin: 0;
  font-size: 0.92rem;
  color: var(--text-color-secondary);
}

.game__failed {
  margin-top: 36px;
}

.game__failed-text {
  margin: 8px 0 0;
}

.game__failed-hint {
  margin: 8px 0 0;
  font-size: 0.85rem;
  color: var(--text-color-secondary);
}

.game__layout {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: 32px;
  margin-top: 44px;
  align-items: start;
}

@media (max-width: 1080px) {
  .game__layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .game__head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
