import type {
  ApiErrorBody,
  ChatMessage,
  GameDetail,
  GameSummary,
  ServiceStatus,
} from './types'

/**
 * Same-origin API base. Vite proxies `/api` in development and nginx does the
 * same in Docker, so no environment-specific host ever has to be configured.
 */
const BASE = '/api'

/** Carries the backend's Hungarian message so views can show it verbatim. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: Partial<ApiErrorBody> = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // A proxy timeout or nginx error page is not JSON; fall through to a generic message.
  }
  return new ApiError(
    body.message ?? `A kérés nem sikerült (HTTP ${response.status}).`,
    body.code ?? 'UNKNOWN',
    response.status,
  )
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, init)
  } catch {
    throw new ApiError(
      'A kiszolgáló nem érhető el. Ellenőrizd, hogy fut-e az alkalmazás.',
      'NETWORK_ERROR',
      0,
    )
  }
  if (!response.ok) throw await toApiError(response)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export const api = {
  status: () => request<ServiceStatus>('/status'),

  listGames: () => request<GameSummary[]>('/games'),

  getGame: (id: string) => request<GameDetail>(`/games/${id}`),

  /**
   * Uploads rulebook pages. `title` is optional — when omitted, the backend
   * reads the game's name off the pages itself.
   */
  createGame: (files: File[], title?: string) => {
    const form = new FormData()
    files.forEach((file) => form.append('files', file))
    if (title?.trim()) form.append('title', title.trim())
    return request<GameSummary>('/games', { method: 'POST', body: form })
  },

  retry: (id: string) => request<GameSummary>(`/games/${id}/retry`, { method: "POST" }),

  /** Re-reads every page from its stored image. One Groq call per page. */
  rescan: (id: string) => request<GameSummary>(`/games/${id}/rescan`, { method: "POST" }),

  regenerateSummary: (id: string) =>
    request<GameSummary>(`/games/${id}/summary/regenerate`, { method: 'POST' }),

  deleteGame: (id: string) => request<void>(`/games/${id}`, { method: 'DELETE' }),

  chatHistory: (id: string) => request<ChatMessage[]>(`/games/${id}/chat`),

  ask: (id: string, question: string) =>
    request<ChatMessage>(`/games/${id}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question }),
    }),

  clearChat: (id: string) => request<void>(`/games/${id}/chat`, { method: 'DELETE' }),

  coverUrl: (id: string) => `${BASE}/games/${id}/cover`,

  /**
   * The narration endpoint. The audio element streams from this URL directly;
   * the first request triggers synthesis, later ones hit the server-side cache.
   */
  summaryAudioUrl: (id: string) => `${BASE}/games/${id}/summary/audio`,
}
