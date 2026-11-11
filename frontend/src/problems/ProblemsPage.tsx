import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { ticketListQuery } from '@/api/queries'
import { Pagination } from '@/components/Pagination'
import { QueryError } from '@/components/QueryError'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { PriorityBadge, StatusBadge } from '@/tickets/badges'
import { formatAge } from '@/tickets/labels'

/**
 * Every problem, for all support staff: a known error found by one team helps whoever meets the
 * same incidents next. Open problems first; "All" includes closed and cancelled ones.
 */
export function ProblemsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const showAll = searchParams.get('show') === 'all'
  const page = Number(searchParams.get('page') ?? '0')
  const problems = useQuery(
    ticketListQuery({ view: 'ALL', type: ['PROBLEM'], open: showAll ? undefined : true, page, size: 20, sort: ['updatedAt,desc'] }),
  )

  return (
    <section className="grid gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid gap-1">
          <h1 className="text-2xl font-semibold">Problems</h1>
          <p className="text-muted-foreground">The causes behind recurring incidents, and their workarounds.</p>
        </div>
        <Button asChild>
          <Link to="/problems/new">Raise a problem</Link>
        </Button>
      </div>
      <div className="flex gap-2" role="group" aria-label="Which problems">
        <Button variant={showAll ? 'outline' : 'secondary'} size="sm" aria-pressed={!showAll} onClick={() => setSearchParams({})}>
          Open
        </Button>
        <Button variant={showAll ? 'secondary' : 'outline'} size="sm" aria-pressed={showAll} onClick={() => setSearchParams({ show: 'all' })}>
          All
        </Button>
      </div>
      {problems.isPending && <Skeleton className="h-48 w-full" />}
      {problems.isError && <QueryError error={problems.error} what="Problems" />}
      {problems.isSuccess &&
        (problems.data.content.length === 0 ? (
          <p className="rounded-md border border-dashed p-8 text-center text-muted-foreground">
            {showAll ? 'No problems have been raised yet.' : 'No open problems.'}
          </p>
        ) : (
          <>
            <ul className="divide-y rounded-md border">
              {problems.data.content.map((p) => (
                <li key={p.id} className="grid gap-1 p-4">
                  <div className="flex flex-wrap items-center gap-2 text-sm">
                    <span className="font-mono text-muted-foreground">{p.reference}</span>
                    <StatusBadge status={p.status} />
                    <PriorityBadge priority={p.priority} />
                  </div>
                  <Link to={`/tickets/${p.id}`} className="font-medium underline-offset-4 hover:underline">
                    {p.title}
                  </Link>
                  <p className="text-sm text-muted-foreground">
                    {p.assignedTeam.name} · {p.assignee?.name ?? 'Unassigned'} · updated {formatAge(p.updatedAt)}
                  </p>
                </li>
              ))}
            </ul>
            <Pagination
              page={problems.data.page}
              totalPages={problems.data.totalPages}
              onChange={(next) => setSearchParams({ ...(showAll ? { show: 'all' } : {}), page: String(next) })}
            />
          </>
        ))}
    </section>
  )
}
