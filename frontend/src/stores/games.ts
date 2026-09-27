import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ApiError, api } from '@/api/client'
import type { GameDetail, GameSummary, ServiceStatus } from '@/api/types'
import { isInProgress } from '@/api/types'

/** How often an in-flight ingestion is re-checked. */
const POLL_INTERVAL_MS = 2500

export const useGamesStore = defineStore('games', () => {
  const games = ref<GameSummary[]>([])
  const detail = ref<GameDetail | null>(null)
  const service = ref<ServiceStatus | null>(null)

  const loading = ref(false)
  const error = ref<string | null>(null)

  let libraryTimer: number | null = null
  let detailTimer: number | null = null

  const hasBusyGames = computed(() => games.value.some((g) => isInProgress(g.status)))

  function describe(e: unknown): string {
    if (e instanceof ApiError) return e.message
    return 'Váratlan hiba történt.'
  }

  async function loadServiceStatus() {
    try {
      service.value = await api.status()
    } catch {
      // Non-fatal: the banner simply stays hidden if this probe fails.
      service.value = null
    }
  }

  async function loadGames(options: { quiet?: boolean } = {}) {
    if (!options.quiet) loading.value = true
    try {
      games.value = await api.listGames()
      error.value = null
    } catch (e) {
      error.value = describe(e)
    } finally {
      if (!options.quiet) loading.value = false
    }
  }

  async function loadGame(id: string, options: { quiet?: boolean } = {}) {
    if (!options.quiet) loading.value = true
    try {
      detail.value = await api.getGame(id)
      error.value = null
    } catch (e) {
      error.value = describe(e)
      if (!options.quiet) detail.value = null
    } finally {
      if (!options.quiet) loading.value = false
    }
  }

  /**
   * Polls the library while any rulebook is still being processed, and stops as
   * soon as everything has settled so an idle tab makes no requests.
   */
  function startLibraryPolling() {
    stopLibraryPolling()
    libraryTimer = window.setInterval(async () => {
      if (!hasBusyGames.value) {
        stopLibraryPolling()
        return
      }
      await loadGames({ quiet: true })
    }, POLL_INTERVAL_MS)
  }

  function stopLibraryPolling() {
    if (libraryTimer !== null) {
      window.clearInterval(libraryTimer)
      libraryTimer = null
    }
  }

  function startDetailPolling(id: string) {
    stopDetailPolling()
    detailTimer = window.setInterval(async () => {
      if (!detail.value || !isInProgress(detail.value.status)) {
        stopDetailPolling()
        return
      }
      await loadGame(id, { quiet: true })
    }, POLL_INTERVAL_MS)
  }

  function stopDetailPolling() {
    if (detailTimer !== null) {
      window.clearInterval(detailTimer)
      detailTimer = null
    }
  }

  async function createGame(files: File[], title?: string): Promise<GameSummary> {
    const created = await api.createGame(files, title)
    // Insert immediately so the new card appears with its progress bar rather
    // than after the next poll tick.
    games.value = [created, ...games.value]
    startLibraryPolling()
    return created
  }

  async function retry(id: string) {
    await api.retry(id)
    await Promise.all([loadGames({ quiet: true }), loadGame(id, { quiet: true })])
    startLibraryPolling()
    startDetailPolling(id)
  }

  async function rescan(id: string) {
    await api.rescan(id)
    await Promise.all([loadGames({ quiet: true }), loadGame(id, { quiet: true })])
    startLibraryPolling()
    startDetailPolling(id)
  }

  async function regenerateSummary(id: string) {
    await api.regenerateSummary(id)
    await loadGame(id, { quiet: true })
    startDetailPolling(id)
  }

  async function deleteGame(id: string) {
    await api.deleteGame(id)
    games.value = games.value.filter((g) => g.id !== id)
    if (detail.value?.id === id) detail.value = null
  }

  return {
    games,
    detail,
    service,
    loading,
    error,
    hasBusyGames,
    loadServiceStatus,
    loadGames,
    loadGame,
    startLibraryPolling,
    stopLibraryPolling,
    startDetailPolling,
    stopDetailPolling,
    createGame,
    retry,
    rescan,
    regenerateSummary,
    deleteGame,
  }
})
