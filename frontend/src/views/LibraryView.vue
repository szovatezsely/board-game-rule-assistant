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
        <span class="eyebrow">Társasjáték-szabály asszisztens</span>
        <h1 class="display hero__title">
          Fotózd le a szabálykönyvet.<br />
          Hallgasd meg, és kérdezz róla.
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
          <div v-if="store.games.length" class="hero__stats">
            <span><strong>{{ readyCount }}</strong> feldolgozott játék</span>
            <span v-if="busyCount">
              <strong>{{ busyCount }}</strong> feldolgozás alatt
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

      <div v-else-if="!store.games.length" class="library__empty panel panel--flat">
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
  padding: var(--section-padding-half) 0 var(--section-padding-half);
  border-bottom: 1px solid var(--color-offwhite);
}

.hero__inner {
  display: flex;
  flex-direction: column;
  gap: 22px;
  align-items: flex-start;
}

.hero__title {
  /* Widened along with the smaller type so each sentence keeps to one line
     instead of fragmenting across four. */
  max-width: 32ch;
}

.hero__lead {
  margin: 0;
}

.hero__actions {
  display: flex;
  align-items: center;
  gap: 32px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.hero__stats {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  font-size: 0.86rem;
  color: var(--text-color-secondary);
}

.hero__stats strong {
  color: var(--text-color-primary);
  font-weight: 600;
}

.upload-wrap {
  padding-top: var(--section-padding-half);
}

.library {
  padding-top: var(--section-padding-half);
}

.library__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 36px;
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
  padding: 56px 40px;
  text-align: center;
}

.library__empty-title {
  margin: 0 0 8px;
  font-family: var(--font-family-serif);
  font-size: 1.2rem;
}

.library__empty-text {
  margin: 0;
  font-size: 0.92rem;
}

.library__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 28px;
}

@media (max-width: 640px) {
  .library__grid {
    grid-template-columns: 1fr;
  }

  .library__head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
