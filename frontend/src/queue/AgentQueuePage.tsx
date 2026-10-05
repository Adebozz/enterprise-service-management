import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router'
import { ticketListQuery } from '@/api/queries'
import type { Priority, TicketListParams, TicketType } from '@/api/types'
import { NativeSelect } from '@/components/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { PriorityBadge, StatusBadge } from '@/tickets/badges'
import { PRIORITY_LABEL, TYPE_LABEL, formatAge, formatDateTime } from '@/tickets/labels'

const VIEWS = {
  mine: { label: 'Mine', view: 'MINE', openOnly: true },
  team: { label: 'Team', view: 'TEAM', openOnly: true },
  unassigned: { label: 'Unassigned', view: 'UNASSIGNED', openOnly: false }, // the API already excludes closed
  all: { label: 'All', view: 'ALL', openOnly: false },
} as const satisfies Record<string, { label: string; view: TicketListParams['view']; openOnly: boolean }>
type ViewKey = keyof typeof VIEWS

const SORTS = {
  priority: { label: 'Priority, then oldest', sort: ['priority,asc', 'createdAt,asc'] },
  newest: { label: 'Newest first', sort: ['createdAt,desc'] },
  oldest: { label: 'Oldest first', sort: ['createdAt,asc'] },
  updated: { label: 'Recently updated', sort: ['updatedAt,desc'] },
  relevance: { label: 'Best match', sort: [] },
} as const satisfies Record<string, { label: string; sort: readonly string[] }>
type SortKey = keyof typeof SORTS

const isKey = <T extends object>(map: T, key: string | null): key is Extract<keyof T, string> => key !== null && key in map

/**
 * The support queue. Everything (tab, filters, search, sort, page) lives in the URL, so a queue
 * view can be bookmarked, shared or restored with the back button.
 */
export function AgentQueuePage() {
  const location = useLocation()
  const notice = (location.state as { notice?: string } | null)?.notice
  const [searchParams, setSearchParams] = useSearchParams()
  const get = (key: string) => searchParams.get(key)

  const view: ViewKey = isKey(VIEWS, get('view')) ? get('view') as ViewKey : 'mine'
  const q = get('q') ?? ''
  const type = get('type') as TicketType | null
  const priority = get('priority') as Priority | null
  const sort: SortKey = isKey(SORTS, get('sort')) ? get('sort') as SortKey : q ? 'relevance' : 'priority'
  const page = Math.max(0, Number(get('page') ?? '1') - 1) || 0

  const params: TicketListParams = {
    view: VIEWS[view].view,
    page,
    size: 25,
    ...(VIEWS[view].openOnly ? { open: true } : {}),
    ...(q ? { q } : {}),
    ...(type ? { type: [type] } : {}),
    ...(priority ? { priority: [priority] } : {}),
    ...(SORTS[sort].sort.length > 0 ? { sort: [...SORTS[sort].sort] } : {}),
  }
  const tickets = useQuery({ ...ticketListQuery(params), placeholderData: keepPreviousData })

  /** Updates some URL parameters; any filter change returns to the first page. */
  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(searchParams)
    Object.entries(changes).forEach(([key, value]) => (value ? next.set(key, value) : next.delete(key)))
    if (!('page' in changes)) next.delete('page')
    setSearchParams(next)
  }

  const [draft, setDraft] = useState(q)
  const onSearch = (event: FormEvent) => {
    event.preventDefault()
    update({ q: draft.trim() || null, sort: null })
  }

  return (
    <section className="grid gap-5">
      <h1 className="text-2xl font-semibold">Queue</h1>
      {notice && (
        <Alert role="status">
          <AlertDescription>{notice}</AlertDescription>
        </Alert>
      )}

      <div role="group" aria-label="Queue" className="flex flex-wrap gap-1">
        {(Object.keys(VIEWS) as ViewKey[]).map((key) => (
          <Button key={key} size="sm" variant={view === key ? 'secondary' : 'ghost'} aria-pressed={view === key} onClick={() => update({ view: key })}>
            {VIEWS[key].label}
          </Button>
        ))}
      </div>

      <div className="grid gap-3 md:grid-cols-[2fr_1fr_1fr_1fr] md:items-end">
        <form role="search" onSubmit={onSearch} className="grid gap-2">
          <Label htmlFor="q">Search</Label>
          <div className="flex gap-2">
            <Input id="q" value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="INC-000042, words, or a person" />
            <Button type="submit" variant="outline">
              Search
            </Button>
          </div>
        </form>
        <div className="grid gap-2">
          <Label htmlFor="type">Type</Label>
          <NativeSelect id="type" value={type ?? ''} onChange={(e) => update({ type: e.target.value || null })}>
            <option value="">All types</option>
            {(Object.keys(TYPE_LABEL) as TicketType[]).map((t) => (
              <option key={t} value={t}>
                {TYPE_LABEL[t]}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="grid gap-2">
          <Label htmlFor="priority">Priority</Label>
          <NativeSelect id="priority" value={priority ?? ''} onChange={(e) => update({ priority: e.target.value || null })}>
            <option value="">Any priority</option>
            {(Object.keys(PRIORITY_LABEL) as Priority[]).map((p) => (
              <option key={p} value={p}>
                {PRIORITY_LABEL[p]}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="grid gap-2">
          <Label htmlFor="sort">Sort</Label>
          <NativeSelect id="sort" value={sort} onChange={(e) => update({ sort: e.target.value })}>
            {(Object.keys(SORTS) as SortKey[])
              .filter((key) => key !== 'relevance' || q)
              .map((key) => (
                <option key={key} value={key}>
                  {SORTS[key].label}
                </option>
              ))}
          </NativeSelect>
        </div>
      </div>

      {tickets.isPending && (
        <div className="grid gap-2" aria-busy="true" aria-label="Loading queue">
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      )}
      {tickets.isError && <QueryError error={tickets.error} what="The queue" />}
      {tickets.isSuccess && tickets.data.content.length === 0 && (
        <p className="rounded-md border border-dashed p-8 text-center text-muted-foreground">Nothing here. {q ? 'Try a different search.' : 'Nice work.'}</p>
      )}
      {tickets.isSuccess && tickets.data.content.length > 0 && (
        <>
          <p className="text-sm text-muted-foreground" aria-live="polite">
            {tickets.data.totalElements} ticket{tickets.data.totalElements === 1 ? '' : 's'}
          </p>
          <div className={cn('overflow-x-auto rounded-md border', tickets.isPlaceholderData && 'opacity-60')}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Reference</TableHead>
                  <TableHead>Title</TableHead>
                  <TableHead>Priority</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Assignee</TableHead>
                  <TableHead className="hidden lg:table-cell">Team</TableHead>
                  <TableHead className="hidden md:table-cell">Age</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tickets.data.content.map((t) => (
                  <TableRow key={t.id}>
                    <TableCell className="font-mono text-xs">
                      <Link to={`/tickets/${t.id}`} className="underline-offset-4 hover:underline">
                        {t.reference}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <Link to={`/tickets/${t.id}`} className="font-medium underline-offset-4 hover:underline">
                        {t.title}
                      </Link>
                      <div className="text-xs text-muted-foreground">
                        {TYPE_LABEL[t.type]} · {t.requester.name}
                      </div>
                    </TableCell>
                    <TableCell>
                      <PriorityBadge priority={t.priority} />
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={t.status} />
                    </TableCell>
                    <TableCell className="text-sm">{t.assignee?.name ?? <span className="text-muted-foreground">Unassigned</span>}</TableCell>
                    <TableCell className="hidden text-sm lg:table-cell">{t.assignedTeam.name}</TableCell>
                    <TableCell className="hidden text-sm text-muted-foreground md:table-cell">
                      <time dateTime={t.createdAt} title={formatDateTime(t.createdAt)}>
                        {formatAge(t.createdAt)}
                      </time>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination page={tickets.data.page} totalPages={tickets.data.totalPages} onChange={(p) => update({ page: String(p + 1) })} />
        </>
      )}
    </section>
  )
}
