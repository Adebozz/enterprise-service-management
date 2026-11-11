import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { pendingApprovalsQuery } from '@/api/queries'
import { QueryError } from '@/components/QueryError'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { PriorityBadge } from '@/tickets/badges'
import { formatDateTime } from '@/tickets/labels'
import { DecisionForm } from './DecisionForm'

/** Requests waiting for the signed-in team lead's (or admin's) approval, oldest first. */
export function ApprovalsPage() {
  const pending = useQuery(pendingApprovalsQuery)

  return (
    <section className="grid gap-5">
      <div className="grid gap-1">
        <h1 className="text-2xl font-semibold">Approvals</h1>
        <p className="text-muted-foreground">Requests for your teams that need your sign-off before support starts work.</p>
      </div>
      {pending.isPending && <Skeleton className="h-48 w-full" />}
      {pending.isError && <QueryError error={pending.error} what="Your approvals" />}
      {pending.isSuccess &&
        (pending.data.length === 0 ? (
          <p className="rounded-md border border-dashed p-8 text-center text-muted-foreground">Nothing is waiting for your approval.</p>
        ) : (
          <ul className="grid gap-4">
            {pending.data.map((approval) => (
              <li key={approval.id}>
                <Card>
                  <CardHeader>
                    <CardTitle className="flex flex-wrap items-center gap-2">
                      <h2>
                        <Link to={`/tickets/${approval.ticketId}`} className="hover:underline">
                          {approval.title}
                        </Link>
                      </h2>
                      <PriorityBadge priority={approval.priority} />
                    </CardTitle>
                    <p className="text-sm text-muted-foreground">
                      <span className="font-mono">{approval.reference}</span> · {approval.requester.name} · {approval.team.name} · asked{' '}
                      {formatDateTime(approval.requestedAt)}
                    </p>
                  </CardHeader>
                  <CardContent className="grid gap-4">
                    <p className="whitespace-pre-wrap">{approval.description}</p>
                    <DecisionForm approvalId={approval.id} version={approval.version} />
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul>
        ))}
    </section>
  )
}
