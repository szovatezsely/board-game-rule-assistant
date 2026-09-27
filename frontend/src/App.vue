<script setup lang="ts">
import { onMounted } from 'vue'
import AppFooter from '@/components/AppFooter.vue'
import AppHeader from '@/components/AppHeader.vue'
import { useGamesStore } from '@/stores/games'

const store = useGamesStore()

// Probed once at boot so a missing GROQ_API_KEY is announced before the user
// spends time photographing and uploading a rulebook.
onMounted(() => store.loadServiceStatus())
</script>

<template>
  <div class="app">
    <AppHeader />

    <main class="app__main">
      <div
        v-if="store.service && !store.service.groqConfigured"
        class="app__banner"
      >
        <div class="container">
          <div class="alert alert--warning">
            <strong>A Groq API kulcs nincs beállítva.</strong>
            Új szabálykönyv feldolgozása addig nem indul el. Állítsd be a
            <code>GROQ_API_KEY</code> értékét a <code>.env</code> fájlban, majd
            indítsd újra a szolgáltatást. Ingyenes kulcs igényelhető a
            <a
              class="app__link"
              href="https://console.groq.com/keys"
              target="_blank"
              rel="noopener noreferrer"
              >console.groq.com/keys</a
            >
            oldalon.
          </div>
        </div>
      </div>

      <!--
        Shown when the configured models were checked at startup and found
        unusable. Catching this here means the user is warned before uploading a
        rulebook, instead of after — Groq retires models and a stale id would
        otherwise surface only as a failed ingestion.
      -->
      <div
        v-else-if="store.service && !store.service.modelsHealthy"
        class="app__banner"
      >
        <div class="container">
          <div class="alert alert--error">
            <strong>A beállított Groq modell nem használható.</strong>
            {{ store.service.modelProblem }}
            <template v-if="store.service.imageCapableModels.length">
              Állítsd a <code>GROQ_VISION_MODEL</code> értékét a
              <code>.env</code> fájlban erre:
              <code>{{ store.service.imageCapableModels[0] }}</code
              >, majd indítsd újra a szolgáltatást.
            </template>
          </div>
        </div>
      </div>

      <RouterView />
    </main>

    <AppFooter />
  </div>
</template>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.app__main {
  flex: 1;
  padding-top: var(--header-height);
}

.app__banner {
  padding-top: 28px;
}

.app__link {
  color: var(--color-main);
  text-decoration: underline;
}
</style>
