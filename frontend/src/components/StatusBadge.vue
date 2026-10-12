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
  padding: 4px 11px;
  border-radius: 999px;
  border: 1px solid;
  font-size: 0.66rem;
  font-weight: 600;
  letter-spacing: 0.13em;
  text-transform: uppercase;
  white-space: nowrap;
  /* Legible on top of a cover photo as well as on a plain surface. */
  backdrop-filter: blur(8px);
}

.badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.badge--ready {
  color: var(--green-bright);
  border-color: var(--green-line);
  background: rgba(0, 46, 29, 0.72);
}

.badge--failed {
  color: var(--red);
  border-color: var(--red-line);
  background: rgba(46, 10, 10, 0.72);
}

.badge--busy {
  color: var(--amber);
  border-color: var(--amber-line);
  background: rgba(40, 28, 6, 0.72);
}

/* A pulsing dot says "still working" without another spinner. */
.badge--busy .badge__dot {
  animation: pulse 1.4s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.25;
  }
}
</style>
