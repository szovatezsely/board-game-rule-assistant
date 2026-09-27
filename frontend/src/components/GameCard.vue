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
  background: var(--color-white);
  border: 1px solid var(--color-offwhite);
  transition: border-color 0.25s ease, transform 0.25s ease;
  height: 100%;
}

.card:hover {
  border-color: var(--color-primary);
  transform: translateY(-3px);
}

.card__media {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: var(--color-lightgray);
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
  font-size: 12px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--color-mediumgray);
}

.card__badge {
  position: absolute;
  top: 12px;
  left: 12px;
}

.card__body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 22px;
  flex: 1;
}

.card__title {
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
  gap: 12px;
  flex-wrap: wrap;
}

.card__date {
  font-size: 0.8rem;
  color: var(--text-color-secondary);
}

.card__error {
  margin: auto 0 0;
  font-size: 0.85rem;
  color: var(--color-error);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card__ready {
  margin: auto 0 0;
  font-size: 0.85rem;
  color: var(--color-main);
  display: flex;
  align-items: center;
  gap: 8px;
}

.card__ready-icon {
  font-size: 0.7rem;
}
</style>
