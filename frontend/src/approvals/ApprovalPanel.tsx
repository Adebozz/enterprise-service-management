import { useQuery } from '@tanstack/react-query'
import { ticketApprovalQuery } from '@/api/queries'
import type { Approval } from '@/api/types'
import { QueryError } from '@/components/QueryError'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { formatDateTime } from '@/tickets/labels'
import { DecisionForm } from './DecisionForm'

const OUTCOME: Record<Approval['status'], { label: string; variant: 'secondary' | 'destructive' | 'outline' }> = {
  PENDING: { label: 'Waiting', variant: 'outline' },
  APPROVED: { label: 'Approved', variant: 'secondary' },
  REJECTED: { label: 'Rejected', variant: 'destructive' },
  WITHDRAWN: { label: 'Withdrawn', variant: 'outline' },
}

/** The ticket's sign-off: where it stands for everyone, and the decision buttons for whoever may decide. */
export function ApprovalPanel({ ticketId, subject = 'request' }: { ticketId: string; subject?: 'request' | 'change' }) {
  const approval = useQuery(ticketApprovalQuery(ticketId))
  if (approval.isPending || approval.data === null) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Approval</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="grid gap-3 text-sm">
        {approval.isError && <QueryError error={approval.error} what="The approval" />}
        {approval.isSuccess && approval.data && (
          <>
            <div className="flex items-center gap-2">
              <Badge variant={OUTCOME[approval.data.status].variant}>{OUTCOME[approval.data.status].label}</Badge>
              <span className="text-muted-foreground">{summary(approval.data, subject)}</span>
            </div>
            {approval.data.comment && <p className="whitespace-pre-wrap">“{approval.data.comment}”</p>}
            {approval.data.canDecide && <DecisionForm approvalId={approval.data.id} version={approval.data.version} />}
          </>
        )}
      </CardContent>
    </Card>
  )
}

function summary(a: Approval, subject: 'request' | 'change'): string {
  switch (a.status) {
    case 'PENDING':
      return `A team lead must approve this ${subject} before work starts.`
    case 'APPROVED':
    case 'REJECTED':
      return `By ${a.decidedBy?.name ?? 'someone'}${a.decidedAt ? `, ${formatDateTime(a.decidedAt)}` : ''}.`
    case 'WITHDRAWN':
      return `The ${subject} was cancelled before anyone decided.`
  }
}
