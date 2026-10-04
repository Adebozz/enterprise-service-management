import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { ticketListQuery } from '@/api/queries'
import type { TicketListParams } from '@/api/types'
import { Pagination } from '@/components/Pagination'
import { QueryError } from '@/components/QueryError'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { PriorityBadge, StatusBadge } from './badges'
import { TYPE_LABEL, formatDateTime } from './labels'

const PAGE_SIZE = 20

/**
 * Tickets the user raised. Page and filter live in the URL (?page=2&show=all) so the back button,
 * refresh and shared links keep the same view.
 */
export function MyTicketsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const showAll = searchParams.get('show') === 'all'
  const page = Math.max(0, Number(searchParams.get('page') ?? '1') - 1) || 0

  const params: TicketListParams = { view: 'REQUESTED', page, size: PAGE_SIZE, ...(showAll ? {} : { open: true }) }
  const tickets = useQuery({ ...ticketListQuery(params), placeholderData: keepPreviousData })

  const update = (next: { page?: number; showAll?: boolean }) => {
    const nextShowAll = next.showAll ?? showAll
    const nextPage = next.page ?? 0
    setSearchParams({ ...(nextShowAll ? { show: 'all' } : {}), ...(nextPage > 0 ? { page: String(nextPage + 1) } : {}) })
  }

  return (
    <section className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">My tickets</h1>
          <p className="mt-1 text-muted-foreground">Problems you reported and things you requested.</p>
        </div>
        <div className="flex gap-2">
          <Button asChild variant="outline">
            <Link to="/tickets/new/request">Request something</Link>
          </Button>
          <Button asChild>
            <Link to="/tickets/new/incident">Report a problem</Link>
          </Button>
        </div>
      </div>

      <div role="group" aria-label="Which tickets to show" className="flex gap-1">
        {[
          { label: 'Open', value: false },
          { label: 'All', value: true },
        ].map((option) => (
          <Button
            key={option.label}
            size="sm"
            variant={showAll === option.value ? 'secondary' : 'ghost'}
            aria-pressed={showAll === option.value}
            onClick={() => update({ showAll: option.value })}
          >
            {option.label}
          </Button>
        ))}
      </div>

      {tickets.isPending && (
        <div className="grid gap-2" aria-busy="true" aria-label="Loading tickets">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      )}
      {tickets.isError && <QueryError error={tickets.error} what="Your tickets" />}
      {tickets.isSuccess && tickets.data.content.length === 0 && (
        <p className="rounded-md border border-dashed p-8 text-center text-muted-foreground">
          {showAll ? 'You haven’t raised any tickets yet.' : 'You have no open tickets.'}
        </p>
      )}
      {tickets.isSuccess && tickets.data.content.length > 0 && (
        <>
          <div className={cn('overflow-x-auto rounded-md border', tickets.isPlaceholderData && 'opacity-60')}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Reference</TableHead>
                  <TableHead>Title</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Priority</TableHead>
                  <TableHead className="hidden md:table-cell">Updated</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tickets.data.content.map((ticket) => (
                  <TableRow key={ticket.id}>
                    <TableCell className="font-mono text-xs">
                      <Link to={`/tickets/${ticket.id}`} className="underline-offset-4 hover:underline">
                        {ticket.reference}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <Link to={`/tickets/${ticket.id}`} className="font-medium underline-offset-4 hover:underline">
                        {ticket.title}
                      </Link>
                      <div className="text-xs text-muted-foreground">
                        {TYPE_LABEL[ticket.type]} · {ticket.category.name}
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={ticket.status} />
                    </TableCell>
                    <TableCell>
                      <PriorityBadge priority={ticket.priority} />
                    </TableCell>
                    <TableCell className="hidden text-sm text-muted-foreground md:table-cell">{formatDateTime(ticket.updatedAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination page={tickets.data.page} totalPages={tickets.data.totalPages} onChange={(p) => update({ page: p })} />
        </>
      )}
    </section>
  )
}
