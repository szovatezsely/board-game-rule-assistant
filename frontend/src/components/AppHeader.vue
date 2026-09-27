<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <header class="header">
    <div class="container header__inner">
      <RouterLink to="/" class="header__brand" aria-label="Kezdőlap">
        <span class="header__wordmark">Társasjáték - Szabály Asszisztens</span>
      </RouterLink>

      <nav class="header__nav" aria-label="Fő navigáció">
        <RouterLink to="/" class="header__link">Könyvtár</RouterLink>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.header {
  position: fixed;
  inset: 0 0 auto 0;
  z-index: 50;
  height: var(--header-height);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--color-offwhite);
}

.header__inner {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.header__brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.header__wordmark {
  font-family: var(--font-family-serif);
  /* Scales down on narrow screens so the full name keeps its single line
     alongside the nav rather than wrapping or being clipped. */
  font-size: clamp(0.95rem, 3.2vw, 1.45rem);
  font-weight: 500;
  font-variant-caps: small-caps;
  /* Small caps need positive tracking to breathe — the tight negative value
     used for display type closes them up into a solid block. */
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.header__nav {
  display: flex;
  align-items: center;
  gap: 28px;
}

.header__link {
  position: relative;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--text-color-secondary);
  transition: color 0.2s ease;
  padding: 4px 0;
}

.header__link:hover,
.header__link.router-link-exact-active {
  color: var(--text-color-primary);
}

.header__link.router-link-exact-active::after {
  content: '';
  position: absolute;
  inset: auto 0 0 0;
  height: 2px;
  background: var(--color-primary);
}

@media (max-width: 620px) {
  .header__nav {
    gap: 18px;
  }
}

/*
 * Below this width the full product name and the nav compete for the same
 * pixels and collide. The only nav item points at "/", which the brand already
 * links to, so hiding it costs no navigation at all and leaves the name room to
 * stay on one line.
 */
@media (max-width: 470px) {
  .header__nav {
    display: none;
  }
}
</style>
