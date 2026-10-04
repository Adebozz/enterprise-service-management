import type { Impact, Priority, TicketType, Urgency } from '@/api/types'

/** Human-readable labels for codes from the API. Unknown values fall back to a readable form. */
export function statusLabel(status: string): string {
  const text = status.toLowerCase().replaceAll('_', ' ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}

export const PRIORITY_LABEL: Record<Priority, string> = {
  P1: 'P1 · Critical',
  P2: 'P2 · High',
  P3: 'P3 · Medium',
  P4: 'P4 · Low',
}

export const TYPE_LABEL: Record<TicketType, string> = { INCIDENT: 'Incident', SERVICE_REQUEST: 'Service request' }

export const LEVEL_LABEL: Record<Impact & Urgency, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' }

const TERMINAL = new Set(['CLOSED', 'CANCELLED', 'REJECTED'])

/** UX hint only (hide the reply box); the server refuses comments on closed tickets regardless. */
export function isClosed(status: string): boolean {
  return TERMINAL.has(status)
}

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso))
}
