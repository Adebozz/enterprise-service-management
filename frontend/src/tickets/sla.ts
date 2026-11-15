import type { SlaClock } from '@/api/types'

export type SlaStatus = SlaClock['status']
export type SlaMetric = SlaClock['metric']

export const SLA_STATUS_LABEL: Record<SlaStatus, string> = {
  ON_TRACK: 'On track',
  AT_RISK: 'At risk',
  BREACHED: 'Breached',
  PAUSED: 'Paused',
  MET: 'Met',
  MISSED: 'Missed',
  CANCELLED: 'Not counted',
}

export const SLA_METRIC_LABEL: Record<SlaMetric, string> = {
  RESPONSE: 'First response',
  RESOLUTION: 'Resolution',
}

/** 15 → "15 min", 90 → "1 h 30 min", 4320 → "3 days". */
export function formatMinutes(total: number): string {
  const minutes = Math.abs(Math.round(total))
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const rest = minutes % 60
  const parts = [days > 0 && `${days} day${days === 1 ? '' : 's'}`, hours > 0 && `${hours} h`, rest > 0 && `${rest} min`].filter(Boolean)
  return parts.length > 0 ? parts.join(' ') : '0 min'
}

/**
 * Working time is never shown in days: 3 × 24 hours of business hours is about two working weeks,
 * so "3 days" would mislead. 4320 → "72 working hours".
 */
export function formatWorkingMinutes(total: number): string {
  const minutes = Math.abs(Math.round(total))
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  const parts = [hours > 0 && `${hours} h`, rest > 0 && `${rest} min`].filter(Boolean)
  return `${parts.length > 0 ? parts.join(' ') : '0 min'} of working time`
}
