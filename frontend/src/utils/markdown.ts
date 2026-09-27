import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({ gfm: true, breaks: true })

/**
 * Renders model-generated Markdown to HTML.
 *
 * Both the summary and the chat answers come from an LLM reading user-supplied
 * rulebook photos, so the output is untrusted by construction and is sanitised
 * before it ever reaches `v-html`.
 */
export function renderMarkdown(source: string | null | undefined): string {
  if (!source) return ''
  const html = marked.parse(source, { async: false })
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'p', 'br', 'strong', 'em', 'del', 'code', 'pre', 'blockquote',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'ul', 'ol', 'li', 'hr',
      'table', 'thead', 'tbody', 'tr', 'th', 'td',
    ],
    ALLOWED_ATTR: [],
  })
}

/** Formats an ISO timestamp for Hungarian readers. */
export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat('hu-HU', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso))
}

export function formatDate(iso: string): string {
  return new Intl.DateTimeFormat('hu-HU', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(new Date(iso))
}
