<script setup lang="ts">
const props = defineProps<{
  percent: number
  label?: string | null
}>()
</script>

<template>
  <div class="progress">
    <div
      class="progress__track"
      role="progressbar"
      :aria-valuenow="props.percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="props.label ?? 'Feldolgozás állapota'"
    >
      <div class="progress__fill" :style="{ width: `${props.percent}%` }"></div>
    </div>
    <div v-if="props.label" class="progress__label">
      <span class="spinner" aria-hidden="true"></span>
      <span>{{ props.label }}</span>
      <span class="progress__percent">{{ props.percent }}%</span>
    </div>
  </div>
</template>

<style scoped>
.progress__track {
  height: 3px;
  background: var(--color-offwhite);
  overflow: hidden;
}

.progress__fill {
  height: 100%;
  background: var(--color-primary);
  transition: width 0.5s ease;
}

.progress__label {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  font-size: 0.82rem;
  color: var(--text-color-secondary);
}

.progress__percent {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
  color: var(--text-color-primary);
  font-weight: 600;
}
</style>
