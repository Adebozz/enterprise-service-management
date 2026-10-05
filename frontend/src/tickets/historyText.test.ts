import { describe as suite, expect, it } from 'vitest'
import type { HistoryEntry } from '@/api/types'
import { describe } from './historyText'

const entry = (overrides: Partial<HistoryEntry>): HistoryEntry => ({
  occurredAt: '2026-10-05T09:00:00Z',
  actor: null,
  action: 'TICKET_CREATED',
  oldValue: null,
  newValue: null,
  metadata: null,
  names: {},
  ...overrides,
})

suite('history sentences', () => {
  it('names the category a ticket was raised in', () => {
    expect(describe(entry({ newValue: { categoryId: 'c1' }, names: { c1: 'Network' } }))).toBe('raised the ticket in Network')
  })

  it('describes status changes including the resolution', () => {
    expect(
      describe(
        entry({
          action: 'TICKET_STATUS_CHANGED',
          oldValue: { status: 'IN_PROGRESS' },
          newValue: { status: 'RESOLVED' },
          metadata: { resolutionCode: 'FIXED' },
        }),
      ),
    ).toBe('changed the status from in progress to resolved (Fixed)')
  })

  it('describes transfers and unassignment', () => {
    expect(
      describe(
        entry({
          action: 'TICKET_ASSIGNMENT_CHANGED',
          oldValue: { teamId: 't1', assigneeId: 'u1' },
          newValue: { teamId: 't2', assigneeId: null },
          names: { t2: 'Hardware Team' },
        }),
      ),
    ).toBe('moved it to Hardware Team and unassigned it')
  })

  it('distinguishes internal notes from messages', () => {
    expect(describe(entry({ action: 'COMMENT_ADDED', metadata: { visibility: 'INTERNAL' } }))).toBe('added an internal note')
    expect(describe(entry({ action: 'COMMENT_ADDED', metadata: { visibility: 'PUBLIC' } }))).toBe('added a message')
  })
})
