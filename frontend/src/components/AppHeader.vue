<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <header class="header">
    <div class="container header__inner">
      <RouterLink to="/" class="header__brand" aria-label="Társasjáték-szabály asszisztens, kezdőlap">
        <img class="header__mark" src="/favicon.svg" alt="" width="28" height="28" />
        <span class="header__wordmark">
          <span class="header__wordmark-long">társasjáték-</span>szabály asszisztens<span
            class="header__dot"
            >.</span
          >
        </span>
      </RouterLink>

      <nav class="header__nav" aria-label="Fő navigáció">
        <RouterLink to="/" class="header__link">Könyvtár</RouterLink>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 50;
  height: var(--header-height);
  background: rgba(10, 10, 11, 0.86);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--line);
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

.header__mark {
  flex: none;
  transition: transform var(--transition);
}

.header__brand:hover .header__mark {
  transform: rotate(-8deg);
}

.header__wordmark {
  font-family: var(--font-display);
  font-size: 1.25rem;
  font-weight: 400;
  letter-spacing: -0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header__dot {
  color: var(--green);
}

.header__nav {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header__link {
  padding: 7px 16px;
  border-radius: 999px;
  border: 1px solid transparent;
  font-size: 0.86rem;
  font-weight: 500;
  color: var(--text-dim);
  transition:
    color var(--transition),
    background var(--transition),
    border-color var(--transition);
}

.header__link:hover {
  color: var(--text);
  background: var(--surface-hover);
}

.header__link.router-link-exact-active {
  color: var(--text);
  border-color: var(--line);
  background: var(--surface);
}

/*
 * The full name and the nav compete for the same pixels on a phone. The
 * "társasjáték-" prefix goes first, then the nav (its only item points at "/",
 * which the brand already links to), so the name never wraps or overflows.
 */
@media (max-width: 640px) {
  .header__wordmark-long {
    display: none;
  }

  .header__wordmark {
    font-size: 1.12rem;
  }
}

@media (max-width: 400px) {
  .header__nav {
    display: none;
  }
}
</style>
