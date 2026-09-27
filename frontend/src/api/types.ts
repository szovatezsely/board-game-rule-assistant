export type GameStatus =
  | 'PENDING'
  | 'TRANSCRIBING'
  | 'SUMMARIZING'
  | 'READY'
  | 'FAILED'

export interface GameSummary {
  id: string
  title: string
  status: GameStatus
  statusDetail: string | null
  errorMessage: string | null
  pageCount: number
  pagesProcessed: number
  progressPercent: number
  hasSummary: boolean
  hasCover: boolean
  createdAt: string
}

export interface PageState {
  pageNumber: number
  transcribed: boolean
  characterCount: number
  /** Page number printed in the book, when it could be read off the photo. */
  printedPageNumber?: number | null
}

export interface GameDetail extends Omit<GameSummary, 'hasSummary'> {
  summary: string | null
  updatedAt: string
  pages: PageState[]
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

export interface ServiceStatus {
  groqConfigured: boolean
  visionModel: string
  textModel: string
  /** False when the configured models were checked at startup and found unusable. */
  modelsHealthy: boolean
  /** Ready-to-display Hungarian description of the misconfiguration, if any. */
  modelProblem: string | null
  /** Model ids on this account that accept image input. */
  imageCapableModels: string[]
}

/** Error shape returned by the backend's exception handler. */
export interface ApiErrorBody {
  message: string
  code: string
}

/** Statuses where the backend is still working and the UI should keep polling. */
export const IN_PROGRESS_STATUSES: GameStatus[] = [
  'PENDING',
  'TRANSCRIBING',
  'SUMMARIZING',
]

export function isInProgress(status: GameStatus): boolean {
  return IN_PROGRESS_STATUSES.includes(status)
}

const STATUS_LABELS: Record<GameStatus, string> = {
  PENDING: 'Várakozik',
  TRANSCRIBING: 'Oldalak beolvasása',
  SUMMARIZING: 'Összefoglaló készítése',
  READY: 'Kész',
  FAILED: 'Hiba',
}

export function statusLabel(status: GameStatus): string {
  return STATUS_LABELS[status] ?? status
}
