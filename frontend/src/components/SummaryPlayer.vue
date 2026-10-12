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

// Feeds the green fill of the seek bar's track (bound in the CSS below);
// WebKit has no ::range-progress pseudo-element to do it natively.
const seekFill = computed(() => `${progressPercent.value}%`)

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
  padding: 30px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.summary__head {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.summary__title {
  margin-top: 8px;
}

.player {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface-alt);
}

.player__play {
  width: 56px;
  height: 56px;
  flex: none;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: var(--green);
  color: var(--green-ink);
  font-size: 1rem;
  box-shadow: 0 0 0 0 rgba(0, 201, 91, 0.45);
  transition:
    background var(--transition),
    transform var(--transition),
    box-shadow var(--transition);
}

.player__play:hover:not(:disabled) {
  background: var(--green-bright);
  transform: scale(1.05);
}

/* A soft halo while the narration plays. */
.player__play--active {
  background: var(--text);
  box-shadow: 0 0 0 6px rgba(0, 201, 91, 0.18);
}

.player__play:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.spinner--light {
  border-color: rgba(0, 46, 29, 0.3);
  border-top-color: var(--green-ink);
}

.player__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.player__status {
  font-size: 0.86rem;
  color: var(--text-dim);
}

/* The seek bar: a thin rounded track filled in green up to the playhead. */
.player__seek {
  --fill: v-bind(seekFill);
  width: 100%;
  height: 18px;
  margin: 0;
  background: transparent;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
}

.player__seek::-webkit-slider-runnable-track {
  height: 5px;
  border-radius: 999px;
  background: linear-gradient(
    to right,
    var(--green) 0,
    var(--green) var(--fill),
    var(--line-strong) var(--fill)
  );
}

.player__seek::-moz-range-track {
  height: 5px;
  border-radius: 999px;
  background: var(--line-strong);
}

.player__seek::-moz-range-progress {
  height: 5px;
  border-radius: 999px;
  background: var(--green);
}

.player__seek::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 14px;
  height: 14px;
  margin-top: -4.5px;
  border-radius: 50%;
  background: var(--text);
  border: 0;
}

.player__seek::-moz-range-thumb {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--text);
  border: 0;
}

.player__seek:disabled {
  cursor: default;
  opacity: 0.45;
}

.player__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.player__time {
  font-size: 0.8rem;
  font-variant-numeric: tabular-nums;
  color: var(--text-faint);
}

.player__controls {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.player__chip {
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 0.76rem;
  font-weight: 500;
  color: var(--text-dim);
  background: transparent;
  border: 1px solid var(--line);
  transition:
    border-color var(--transition),
    color var(--transition),
    background var(--transition);
}

.player__chip:hover:not(:disabled) {
  border-color: var(--line-strong);
  background: var(--surface-hover);
  color: var(--text);
}

.player__chip:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.summary__error {
  margin: 0;
}

.summary__text {
  border-top: 1px solid var(--line);
  padding-top: 22px;
}

@media (max-width: 700px) {
  .summary {
    padding: 22px 18px;
  }

  .player {
    flex-direction: column;
    align-items: stretch;
  }

  .player__play {
    align-self: flex-start;
  }
}
</style>
