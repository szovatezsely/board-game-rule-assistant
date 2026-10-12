<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import GameCard from '@/components/GameCard.vue'
import UploadPanel from '@/components/UploadPanel.vue'
import { useGamesStore } from '@/stores/games'

const store = useGamesStore()
const router = useRouter()

const showUpload = ref(false)
const uploadSection = ref<HTMLElement | null>(null)

const readyCount = computed(() => store.games.filter((g) => g.status === 'READY').length)
const busyCount = computed(
  () => store.games.filter((g) => g.status !== 'READY' && g.status !== 'FAILED').length,
)

async function openUpload() {
  showUpload.value = true
  // Wait a tick so the panel exists before scrolling to it.
  await new Promise((resolve) => requestAnimationFrame(resolve))
  uploadSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onCreated(id: string) {
  showUpload.value = false
  void router.push({ name: 'game', params: { id } })
}

onMounted(async () => {
  await store.loadGames()
  if (store.hasBusyGames) store.startLibraryPolling()
  // First visit: lead straight into the upload flow rather than an empty page.
  if (!store.games.length) showUpload.value = true
})

onUnmounted(() => store.stopLibraryPolling())
</script>

<template>
  <div>
    <section class="hero">
      <div class="container hero__inner">
        <span class="eyebrow eyebrow--accent">Beolvasás · felolvasás · kérdések</span>
        <h1 class="display hero__title">
          Fotózd le a szabálykönyvet.<br />
          <span class="accent">Hallgasd meg,</span> és kérdezz róla.
        </h1>
        <p class="lead hero__lead">
          Töltsd fel a szabálykönyv oldalainak fotóit. Az alkalmazás kiolvassa a
          szöveget, magyar összefoglalót készít belőle, amit fel is olvas, és
          válaszol a szabályokkal kapcsolatos kérdésekre — kizárólag abból, ami a
          szabálykönyvben szerepel.
        </p>

        <div class="hero__actions">
          <button type="button" class="btn btn--primary" @click="openUpload">
            Új szabálykönyv hozzáadása
          </button>
          <div v-if="store.games.length" class="stats">
            <span class="stats__item">
              <span class="stats__value">{{ readyCount }}</span>
              <span class="stats__label">feldolgozott játék</span>
            </span>
            <span v-if="busyCount" class="stats__item stats__item--busy">
              <span class="stats__value">{{ busyCount }}</span>
              <span class="stats__label">feldolgozás alatt</span>
            </span>
          </div>
        </div>
      </div>
    </section>

    <section v-if="showUpload" ref="uploadSection" class="container upload-wrap">
      <UploadPanel @created="onCreated" />
    </section>

    <section class="container library">
      <header class="library__head">
        <div>
          <span class="eyebrow">Könyvtár</span>
          <h2 class="display--sm library__title">Beolvasott szabálykönyvek</h2>
        </div>
        <button
          v-if="!showUpload"
          type="button"
          class="btn btn--ghost btn--sm"
          @click="openUpload"
        >
          Hozzáadás
        </button>
      </header>

      <p v-if="store.error" class="alert alert--error">{{ store.error }}</p>

      <div v-if="store.loading && !store.games.length" class="library__loading">
        <span class="spinner" aria-hidden="true"></span>
        <span class="muted">Könyvtár betöltése…</span>
      </div>

      <div v-else-if="!store.games.length" class="library__empty panel">
        <img class="library__empty-mark" src="/favicon.svg" alt="" width="40" height="40" />
        <p class="library__empty-title">Még nincs egyetlen szabálykönyv sem.</p>
        <p class="muted library__empty-text">
          Kezdd azzal, hogy lefotózod egy szabálykönyv oldalait, és feltöltöd őket.
        </p>
      </div>

      <div v-else class="library__grid">
        <GameCard v-for="game in store.games" :key="game.id" :game="game" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.hero {
  position: relative;
  padding: 72px 0 56px;
  border-bottom: 1px solid var(--line);
  overflow: hidden;
}

/* A faint green glow behind the headline, the only decoration on the page. */
.hero::before {
  content: '';
  position: absolute;
  top: -180px;
  left: -120px;
  width: 620px;
  height: 420px;
  background: radial-gradient(closest-side, rgba(0, 201, 91, 0.12), transparent);
  pointer-events: none;
}

.hero__inner {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 20px;
  align-items: flex-start;
}

/* Wide enough that each sentence keeps to its own line on desktop. */
.hero__title {
  max-width: 30ch;
}

.hero__lead {
  margin: 0;
}

.hero__actions {
  display: flex;
  align-items: center;
  gap: 18px 28px;
  flex-wrap: wrap;
  margin-top: 12px;
}

/* A thin pill strip, as the score bar on Clue Trainer. */
.stats {
  display: flex;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  overflow: hidden;
}

.stats__item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 20px;
}

.stats__item + .stats__item {
  border-left: 1px solid var(--line);
}

.stats__value {
  font-family: var(--font-display);
  font-size: 1.05rem;
  color: var(--green-bright);
  font-variant-numeric: tabular-nums;
}

.stats__item--busy .stats__value {
  color: var(--amber);
}

.stats__label {
  font-size: 0.66rem;
  font-weight: 600;
  letter-spacing: 0.13em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.upload-wrap {
  padding-top: 48px;
}

.library {
  padding-top: 56px;
}

.library__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 28px;
}

.library__title {
  margin-top: 10px;
}

.library__loading {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 40px 0;
}

.library__empty {
  padding: 56px 32px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  border-style: dashed;
}

.library__empty-mark {
  margin-bottom: 18px;
  opacity: 0.9;
}

.library__empty-title {
  margin: 0 0 8px;
  font-family: var(--font-display);
  font-size: 1.25rem;
  font-weight: 300;
}

.library__empty-text {
  margin: 0;
  font-size: 0.92rem;
}

.library__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 22px;
}

@media (max-width: 640px) {
  .hero {
    padding: 44px 0 36px;
  }

  .library__grid {
    grid-template-columns: 1fr;
  }

  .library__head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
