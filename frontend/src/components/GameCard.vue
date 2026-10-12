<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '@/api/client'
import type { GameSummary } from '@/api/types'
import { isInProgress } from '@/api/types'
import ProgressBar from '@/components/ProgressBar.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { formatDate } from '@/utils/markdown'

const props = defineProps<{ game: GameSummary }>()

const busy = computed(() => isInProgress(props.game.status))
const pageLabel = computed(() =>
  props.game.pageCount === 1 ? '1 oldal' : `${props.game.pageCount} oldal`,
)
</script>

<template>
  <RouterLink :to="{ name: 'game', params: { id: props.game.id } }" class="card">
    <div class="card__media">
      <img
        v-if="props.game.hasCover"
        :src="api.coverUrl(props.game.id)"
        :alt="`${props.game.title} szabálykönyv borító`"
        class="card__image"
        loading="lazy"
      />
      <div v-else class="card__placeholder" aria-hidden="true">
        <span>Nincs borító</span>
      </div>
      <span class="card__badge"><StatusBadge :status="props.game.status" /></span>
    </div>

    <div class="card__body">
      <h3 class="card__title display--sm">{{ props.game.title }}</h3>

      <div class="card__meta">
        <span class="tag">{{ pageLabel }}</span>
        <span class="card__date">{{ formatDate(props.game.createdAt) }}</span>
      </div>

      <ProgressBar
        v-if="busy"
        :percent="props.game.progressPercent"
        :label="props.game.statusDetail ?? 'Feldolgozás folyamatban'"
      />

      <p v-else-if="props.game.status === 'FAILED'" class="card__error">
        {{ props.game.errorMessage ?? 'A feldolgozás nem sikerült.' }}
      </p>

      <p v-else class="card__ready">
        <span class="card__ready-icon" aria-hidden="true">▶</span>
        Összefoglaló és kérdések készen állnak
      </p>
    </div>
  </RouterLink>
</template>

<style scoped>
.card {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition:
    border-color var(--transition),
    transform var(--transition),
    box-shadow var(--transition);
}

.card:hover {
  border-color: var(--green-line);
  transform: translateY(-3px);
  box-shadow: var(--shadow), 0 0 0 1px rgba(0, 201, 91, 0.08);
}

.card__media {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: var(--surface-alt);
}

/* The cover fades into the card, so photos of any brightness sit calmly. */
.card__media::after {
  content: '';
  position: absolute;
  inset: 55% 0 0 0;
  background: linear-gradient(to bottom, transparent, var(--surface));
  pointer-events: none;
}

.card__image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.5s ease;
}

.card:hover .card__image {
  transform: scale(1.04);
}

.card__placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.card__badge {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 1;
}

.card__body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 6px 20px 20px;
  flex: 1;
}

.card__title {
  color: var(--text);
  /* Two lines max so cards in a row keep a consistent height. */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.card__date {
  font-size: 0.78rem;
  color: var(--text-faint);
}

.card__error {
  margin: auto 0 0;
  font-size: 0.84rem;
  color: var(--red);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card__ready {
  margin: auto 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--line);
  font-size: 0.84rem;
  color: var(--text-dim);
  display: flex;
  align-items: center;
  gap: 10px;
  transition: color var(--transition);
}

.card:hover .card__ready {
  color: var(--text);
}

.card__ready-icon {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 0.55rem;
  background: var(--green);
  color: var(--green-ink);
  flex: none;
}
</style>
