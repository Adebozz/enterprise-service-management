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

  it('says when a reply resumed a waiting ticket', () => {
    expect(
      describe(
        entry({
          action: 'TICKET_STATUS_CHANGED',
          oldValue: { status: 'WAITING_FOR_USER' },
          newValue: { status: 'IN_PROGRESS' },
          metadata: { transition: 'Resume', trigger: 'REQUESTER_REPLY' },
        }),
      ),
    ).toBe('changed the status from waiting for user to in progress by replying')
  })

  it('describes a request held for approval, and the decision', () => {
    expect(
      describe(
        entry({
          action: 'TICKET_STATUS_CHANGED',
          oldValue: { status: 'SUBMITTED' },
          newValue: { status: 'APPROVAL_PENDING' },
          metadata: { transition: 'Request approval', trigger: 'APPROVAL_REQUIRED' },
        }),
      ),
    ).toBe('sent the request for approval')
    expect(
      describe(
        entry({
          action: 'TICKET_STATUS_CHANGED',
          oldValue: { status: 'APPROVAL_PENDING' },
          newValue: { status: 'APPROVED' },
          metadata: { transition: 'Approve', trigger: 'APPROVAL' },
        }),
      ),
    ).toBe('changed the status from approval pending to approved')
  })

  it('names the target an SLA breach was for', () => {
    expect(describe(entry({ action: 'SLA_BREACHED', metadata: { metric: 'RESPONSE' } }))).toBe(
      'flagged the response time target as breached',
    )
    expect(describe(entry({ action: 'SLA_BREACHED', metadata: { metric: 'RESOLUTION' } }))).toBe(
      'flagged the resolution time target as breached',
    )
  })

  it('distinguishes internal notes from messages', () => {
    expect(describe(entry({ action: 'COMMENT_ADDED', metadata: { visibility: 'INTERNAL' } }))).toBe('added an internal note')
    expect(describe(entry({ action: 'COMMENT_ADDED', metadata: { visibility: 'PUBLIC' } }))).toBe('added a message')
  })

  it('describes problem analysis and incident links', () => {
    expect(describe(entry({ action: 'PROBLEM_ANALYSIS_UPDATED', newValue: { rootCause: 'Licence', workaround: 'vpn2' } }))).toBe(
      'updated the root cause and workaround',
    )
    expect(describe(entry({ action: 'PROBLEM_ANALYSIS_UPDATED', newValue: { workaround: null } }))).toBe('updated the workaround')
    expect(describe(entry({ action: 'INCIDENT_PROBLEM_LINKED', newValue: { problemReference: 'PRB-000007' } }))).toBe(
      'linked it to problem PRB-000007',
    )
    expect(describe(entry({ action: 'INCIDENT_PROBLEM_UNLINKED', oldValue: { problemReference: 'PRB-000007' } }))).toBe(
      'removed the link to problem PRB-000007',
    )
  })
})
