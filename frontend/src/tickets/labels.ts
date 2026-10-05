import type { Impact, Priority, ResolutionCode, TicketType, Urgency } from '@/api/types'

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

/**
 * Typed as Record<ResolutionCode, …>: if the backend adds a code, the generated type changes and this
 * file stops compiling until the new code has a label. Exhaustive by construction, no copied list.
 */
export const RESOLUTION_LABEL: Record<ResolutionCode, string> = {
  FIXED: 'Fixed',
  WORKAROUND: 'Workaround provided',
  NO_FAULT_FOUND: 'No fault found',
  DUPLICATE: 'Duplicate of another ticket',
  USER_ERROR: 'User error / how-to',
  NOT_REPRODUCIBLE: 'Could not reproduce',
}

export const RESOLUTION_CODES = Object.keys(RESOLUTION_LABEL) as [ResolutionCode, ...ResolutionCode[]]

/** Statuses where ownership can't change (server rule; used here only to hide controls). */
export function isAssignable(status: string): boolean {
  return !isClosed(status) && status !== 'RESOLVED' && status !== 'FULFILLED'
}

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

/** "3 hours ago" style age, for queues where recency matters more than the exact time. */
export function formatAge(iso: string, now = Date.now()): string {
  const minutes = Math.round((new Date(iso).getTime() - now) / 60_000)
  if (Math.abs(minutes) < 60) return relative.format(minutes, 'minute')
  const hours = Math.round(minutes / 60)
  if (Math.abs(hours) < 48) return relative.format(hours, 'hour')
  return relative.format(Math.round(hours / 24), 'day')
}
