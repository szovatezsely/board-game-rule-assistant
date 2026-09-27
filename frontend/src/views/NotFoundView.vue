<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <section class="container notfound">
    <span class="eyebrow">404</span>
    <h1 class="display notfound__title">Ez az oldal nem található.</h1>
    <p class="lead">
      Elképzelhető, hogy a keresett szabálykönyvet törölték, vagy elírás van a
      hivatkozásban.
    </p>
    <RouterLink to="/" class="btn btn--primary notfound__cta">
      Vissza a könyvtárhoz
    </RouterLink>
  </section>
</template>

<style scoped>
.notfound {
  padding: var(--section-padding) 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 20px;
}

.notfound__title {
  max-width: 22ch;
}

.notfound__cta {
  margin-top: 12px;
}
</style>
