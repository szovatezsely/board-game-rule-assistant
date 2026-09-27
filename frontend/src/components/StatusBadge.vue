<script setup lang="ts">
import { computed } from 'vue'
import type { GameStatus } from '@/api/types'
import { statusLabel } from '@/api/types'

const props = defineProps<{ status: GameStatus }>()

const tone = computed(() => {
  switch (props.status) {
    case 'READY':
      return 'ready'
    case 'FAILED':
      return 'failed'
    default:
      return 'busy'
  }
})
</script>

<template>
  <span class="badge" :class="`badge--${tone}`">
    <span class="badge__dot" aria-hidden="true"></span>
    {{ statusLabel(props.status) }}
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 26px;
  padding: 0 10px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.09em;
  text-transform: uppercase;
  border: 1px solid;
  white-space: nowrap;
}

.badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.badge--ready {
  color: var(--color-main);
  border-color: rgba(32, 165, 117, 0.35);
  background: var(--color-primary-soft);
}

.badge--failed {
  color: var(--color-error);
  border-color: rgba(235, 87, 87, 0.35);
  background: rgba(235, 87, 87, 0.07);
}

.badge--busy {
  color: var(--color-gray);
  border-color: var(--color-offwhite);
  background: var(--color-lightgray);
}
</style>
