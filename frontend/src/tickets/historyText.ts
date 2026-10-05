import type { HistoryEntry } from '@/api/types'
import { RESOLUTION_LABEL, statusLabel } from './labels'

type Values = Record<string, unknown> | null

export const text = (values: Values, key: string): string | undefined => {
  const value = values?.[key]
  return typeof value === 'string' ? value : undefined
}

/** Turns one audit entry into a sentence, using the names the API resolved for the ids it contains. */
export function describe(entry: HistoryEntry): string {
  const name = (id: string | undefined) => (id ? (entry.names[id] ?? 'someone') : undefined)
  switch (entry.action) {
    case 'TICKET_CREATED': {
      const category = name(text(entry.newValue, 'categoryId'))
      return category ? `raised the ticket in ${category}` : 'raised the ticket'
    }
    case 'TICKET_STATUS_CHANGED': {
      const from = text(entry.oldValue, 'status')
      const to = text(entry.newValue, 'status')
      let sentence = `changed the status from ${statusLabel(from ?? '?').toLowerCase()} to ${statusLabel(to ?? '?').toLowerCase()}`
      const code = text(entry.metadata, 'resolutionCode')
      if (code && code in RESOLUTION_LABEL) sentence += ` (${RESOLUTION_LABEL[code as keyof typeof RESOLUTION_LABEL]})`
      return sentence
    }
    case 'TICKET_ASSIGNMENT_CHANGED': {
      const parts: string[] = []
      const oldTeam = text(entry.oldValue, 'teamId')
      const newTeam = text(entry.newValue, 'teamId')
      if (oldTeam !== newTeam) parts.push(`moved it to ${name(newTeam)}`)
      const oldAssignee = text(entry.oldValue, 'assigneeId')
      const newAssignee = text(entry.newValue, 'assigneeId')
      if (oldAssignee !== newAssignee) parts.push(newAssignee ? `assigned it to ${name(newAssignee)}` : 'unassigned it')
      return parts.join(' and ') || 'updated the assignment'
    }
    case 'COMMENT_ADDED':
      return text(entry.metadata, 'visibility') === 'INTERNAL' ? 'added an internal note' : 'added a message'
    default:
      return statusLabel(entry.action).toLowerCase()
  }
}

