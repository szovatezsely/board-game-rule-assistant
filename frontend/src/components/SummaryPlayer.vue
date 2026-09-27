<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { api } from '@/api/client'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{
  gameId: string
  summary: string | null
}>()

const audio = ref<HTMLAudioElement | null>(null)
const playing = ref(false)
const preparing = ref(false)
const errorMessage = ref<string | null>(null)
const currentTime = ref(0)
const duration = ref(0)
const rate = ref(1)

const RATES = [0.85, 1, 1.15, 1.35]

const summaryHtml = computed(() => renderMarkdown(props.summary))

const progressPercent = computed(() =>
  duration.value > 0 ? (currentTime.value / duration.value) * 100 : 0,
)

// Drop the trailing zero so normal speed reads "1×" rather than "1.0×".
const rateLabel = computed(() => String(rate.value))

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds)) return '--:--'
  const total = Math.floor(seconds)
  const mins = Math.floor(total / 60)
  const secs = total % 60
  return `${mins}:${secs.toString().padStart(2, '0')}`
}

/**
 * The first play triggers server-side synthesis, which takes as long as the
 * narration itself, so the button reports "preparing" until audio is actually
 * available. Later plays hit the server cache and start immediately.
 */
async function toggle() {
  errorMessage.value = null
  const element = audio.value
  if (!element) return

  if (playing.value) {
    element.pause()
    return
  }

  if (!element.src) {
    preparing.value = true
    element.src = api.summaryAudioUrl(props.gameId)
  }

  try {
    element.playbackRate = rate.value
    await element.play()
  } catch {
    preparing.value = false
    errorMessage.value =
      'A felolvasás nem indult el. Előfordulhat, hogy a felolvasó szolgáltatás még dolgozik, próbáld újra néhány másodperc múlva.'
  }
}

function restart() {
  const element = audio.value
  if (!element) return
  element.currentTime = 0
  if (!playing.value) void toggle()
}

function seek(event: Event) {
  const element = audio.value
  const input = event.target as HTMLInputElement
  if (!element || !duration.value) return
  element.currentTime = (Number(input.value) / 100) * duration.value
}

function cycleRate() {
  const next = RATES[(RATES.indexOf(rate.value) + 1) % RATES.length]
  rate.value = next
  if (audio.value) audio.value.playbackRate = next
}

function onLoaded() {
  preparing.value = false
  duration.value = audio.value?.duration ?? 0
}

function onError() {
  preparing.value = false
  playing.value = false
  errorMessage.value =
    'A hangfájl nem érhető el. Ellenőrizd, hogy fut-e a felolvasó (tts) szolgáltatás.'
}

onBeforeUnmount(() => audio.value?.pause())
</script>

<template>
  <section class="summary panel" aria-labelledby="summary-heading">
    <header class="summary__head">
      <div>
        <span class="eyebrow">Összefoglaló</span>
        <h2 id="summary-heading" class="display--sm summary__title">
          A szabályok röviden
        </h2>
      </div>
    </header>

    <div class="player">
      <button
        type="button"
        class="player__play"
        :class="{ 'player__play--active': playing }"
        :disabled="!props.summary"
        :aria-label="playing ? 'Felolvasás megállítása' : 'Felolvasás indítása'"
        @click="toggle"
      >
        <span v-if="preparing" class="spinner spinner--light" aria-hidden="true"></span>
        <span v-else aria-hidden="true">{{ playing ? '❚❚' : '▶' }}</span>
      </button>

      <div class="player__body">
        <div class="player__status">
          <span v-if="preparing">Hang előkészítése… ez az első alkalommal eltarthat egy percig.</span>
          <span v-else-if="playing">Felolvasás folyamatban</span>
          <span v-else-if="duration">Felolvasás szüneteltetve</span>
          <span v-else>Hallgasd meg a szabályok összefoglalóját magyar hanggal</span>
        </div>

        <input
          class="player__seek"
          type="range"
          min="0"
          max="100"
          step="0.1"
          :value="progressPercent"
          :disabled="!duration"
          aria-label="Pozíció a felolvasásban"
          @input="seek"
        />

        <div class="player__meta">
          <span class="player__time">
            {{ formatTime(currentTime) }} / {{ formatTime(duration) }}
          </span>
          <div class="player__controls">
            <button
              type="button"
              class="player__chip"
              :disabled="!duration"
              @click="restart"
            >
              Újra elejétől
            </button>
            <button type="button" class="player__chip" @click="cycleRate">
              {{ rateLabel }}× tempó
            </button>
          </div>
        </div>
      </div>
    </div>

    <audio
      ref="audio"
      preload="none"
      @play="playing = true"
      @pause="playing = false"
      @ended="playing = false"
      @loadedmetadata="onLoaded"
      @timeupdate="currentTime = audio?.currentTime ?? 0"
      @error="onError"
    ></audio>

    <p v-if="errorMessage" class="alert alert--error summary__error">
      {{ errorMessage }}
    </p>

    <article
      v-if="props.summary"
      class="prose prose--scroll-tables summary__text"
      v-html="summaryHtml"
    ></article>
    <p v-else class="muted">Ehhez a játékhoz még nem készült összefoglaló.</p>
  </section>
</template>

<style scoped>
.summary {
  padding: 36px;
  display: flex;
  flex-direction: column;
  gap: 26px;
}

.summary__head {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.summary__title {
  margin-top: 10px;
}

.player {
  display: flex;
  align-items: stretch;
  gap: 20px;
  padding: 22px;
  background: var(--color-darkgray);
  color: var(--color-white);
}

.player__play {
  width: 62px;
  height: 62px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: var(--color-primary);
  color: var(--color-black);
  font-size: 1rem;
  transition: background-color 0.2s ease;
}

.player__play:hover:not(:disabled) {
  background: var(--color-white);
}

.player__play--active {
  background: var(--color-white);
}

.player__play:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.spinner--light {
  border-color: rgba(0, 0, 0, 0.25);
  border-top-color: var(--color-black);
}

.player__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.player__status {
  font-size: 0.85rem;
  color: rgba(255, 255, 255, 0.72);
}

.player__seek {
  width: 100%;
  accent-color: var(--color-primary);
  cursor: pointer;
}

.player__seek:disabled {
  cursor: default;
  opacity: 0.5;
}

.player__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.player__time {
  font-size: 0.8rem;
  font-variant-numeric: tabular-nums;
  color: rgba(255, 255, 255, 0.72);
}

.player__controls {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.player__chip {
  padding: 6px 12px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-white);
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.28);
  transition: border-color 0.2s ease, color 0.2s ease;
}

.player__chip:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.player__chip:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.summary__error {
  margin: 0;
}

.summary__text {
  border-top: 1px solid var(--color-offwhite);
  padding-top: 24px;
}

@media (max-width: 700px) {
  .summary {
    padding: 24px 20px;
  }

  .player {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
