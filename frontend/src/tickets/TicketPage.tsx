import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { useCurrentUser } from '@/auth/AuthContext'
import { hasRole } from '@/auth/roles'
import { isApiError } from '@/api/errors'
import { ticketQuery, transitionsQuery } from '@/api/queries'
import type { Ticket } from '@/api/types'
import { QueryError } from '@/components/QueryError'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { PriorityBadge, StatusBadge } from './badges'
import { AssignmentPanel } from './AssignmentPanel'
import { Conversation } from './Conversation'
import { History } from './History'
import { TicketActions } from './TicketActions'
import { LEVEL_LABEL, TYPE_LABEL, formatDateTime, isClosed } from './labels'

export function TicketPage() {
  const { id = '' } = useParams()
  const user = useCurrentUser()
  // Staff features are offered by role; the API decides per ticket (e.g. history is support-only).
  const isStaff = hasRole(user, 'AGENT')
  const ticket = useQuery(ticketQuery(id))
  const transitions = useQuery(transitionsQuery(id))

  if (ticket.isPending) {
    return (
      <div className="grid gap-4" aria-busy="true" aria-label="Loading ticket">
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-40 w-full" />
      </div>
    )
  }
  if (ticket.isError) {
    // Includes tickets the user may not see: the API deliberately answers 404 for both.
    return isApiError(ticket.error) && ticket.error.code === 'RESOURCE_NOT_FOUND' ? (
      <section className="py-12 text-center">
        <h1 className="text-xl font-semibold">Ticket not found</h1>
        <p className="mt-2 text-muted-foreground">It may not exist, or you may not have access to it.</p>
        <Button asChild className="mt-6">
          <Link to="/tickets">Back to my tickets</Link>
        </Button>
      </section>
    ) : (
      <QueryError error={ticket.error} what="This ticket" />
    )
  }

  const t = ticket.data
  return (
    <article className="grid gap-8">
      <header className="grid gap-3">
        <Link to={isStaff ? '/queue' : '/tickets'} className="text-sm text-muted-foreground underline-offset-4 hover:underline">
          {isStaff ? '← Queue' : '← My tickets'}
        </Link>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="font-mono text-muted-foreground">{t.reference}</span>
          <StatusBadge status={t.status} />
          <PriorityBadge priority={t.priority} />
        </div>
        <h1 className="text-2xl font-semibold">{t.title}</h1>
        {transitions.isSuccess && <TicketActions ticket={t} transitions={transitions.data} />}
      </header>

      <div className="grid gap-8 lg:grid-cols-[2fr_1fr]">
        <div className="grid content-start gap-8">
          <section aria-labelledby="description-heading">
            <h2 id="description-heading" className="text-lg font-semibold">
              Description
            </h2>
            <p className="mt-2 whitespace-pre-wrap">{t.description}</p>
          </section>
          <Conversation ticketId={t.id} closed={isClosed(t.status)} canWriteInternal={isStaff} />
          {isStaff && <History ticketId={t.id} />}
        </div>
        <div className="grid content-start gap-4">
          {isStaff && <AssignmentPanel ticket={t} />}
          <TicketFacts ticket={t} />
        </div>
      </div>
    </article>
  )
}

function TicketFacts({ ticket: t }: { ticket: Ticket }) {
  const facts: [string, ReactNode][] = [
    ['Type', TYPE_LABEL[t.type]],
    ['Category', t.subcategory ? `${t.category.name} › ${t.subcategory.name}` : t.category.name],
    ['Team', t.assignedTeam.name],
    ['Assigned to', t.assignee?.name ?? 'Not yet assigned'],
    ['Raised by', t.requester.name],
    ['Impact / urgency', `${LEVEL_LABEL[t.impact]} / ${LEVEL_LABEL[t.urgency]}`],
    ['Created', formatDateTime(t.createdAt)],
    ['Last updated', formatDateTime(t.updatedAt)],
  ]
  if (t.resolvedAt) facts.push([t.type === 'INCIDENT' ? 'Resolved' : 'Fulfilled', formatDateTime(t.resolvedAt)])
  if (t.closedAt) facts.push(['Closed', formatDateTime(t.closedAt)])
  if (t.incident?.affectedService) facts.push(['Affected service', t.incident.affectedService])
  if (t.incident?.resolutionNotes) facts.push(['Resolution', t.incident.resolutionNotes])
  if (t.serviceRequest?.fulfilmentNotes) facts.push(['Fulfilment', t.serviceRequest.fulfilmentNotes])

  return (
    <aside aria-label="Ticket details" className="h-fit rounded-md border p-4">
      <dl className="grid gap-3 text-sm">
        {facts.map(([term, value]) => (
          <div key={term} className="grid gap-0.5">
            <dt className="text-muted-foreground">{term}</dt>
            <dd className="whitespace-pre-wrap">{value}</dd>
          </div>
        ))}
      </dl>
    </aside>
  )
}
