import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { myProfileQuery, reportQuery, teamsQuery } from '@/api/queries'
import type { ReportParams, ServiceDeskReport } from '@/api/types'
import { useCurrentUser } from '@/auth/AuthContext'
import { NativeSelect } from '@/components/NativeSelect'
import { QueryError } from '@/components/QueryError'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { OUTCOME_LABEL } from '@/tickets/labels'
import { SLA_METRIC_LABEL, formatMinutes } from '@/tickets/sla'
import { BarList, LineChart } from './charts'

const PERIODS = [7, 30, 90] as const

/** "2027-03-01" → "1 Mar": calendar days, formatted without shifting through a time zone. */
const dayLabel = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', timeZone: 'UTC' })
const shortDay = (day: string) => dayLabel.format(new Date(`${day}T00:00:00Z`))

/** Team leads see their own teams; admins any team or the whole desk. */
export function ReportsPage() {
  const user = useCurrentUser()
  const [days, setDays] = useState<(typeof PERIODS)[number]>(30)
  const [teamId, setTeamId] = useState('')
  const params: ReportParams = { from: daysAgo(days - 1), to: daysAgo(0), teamId: teamId || undefined }
  const report = useQuery(reportQuery(params))
  const allTeams = useQuery({ ...teamsQuery, enabled: user.role === 'ADMIN' })
  const profile = useQuery({ ...myProfileQuery, enabled: user.role !== 'ADMIN' })
  const teamOptions = user.role === 'ADMIN' ? (allTeams.data ?? []) : (profile.data?.teams ?? [])

  return (
    <section className="grid gap-6">
      <div className="grid gap-1">
        <h1 className="text-2xl font-semibold">Reports</h1>
        <p className="text-muted-foreground">How the service desk is doing: incidents and service requests, by the day they happened.</p>
      </div>

      <div className="flex flex-wrap items-end gap-4" role="group" aria-label="Report filters">
        <div className="grid gap-1">
          <span className="text-sm font-medium">Period</span>
          <div className="flex gap-1">
            {PERIODS.map((period) => (
              <Button key={period} size="sm" variant={period === days ? 'default' : 'outline'} aria-pressed={period === days} onClick={() => setDays(period)}>
                {period} days
              </Button>
            ))}
          </div>
        </div>
        <div className="grid gap-1">
          <Label htmlFor="report-team">Team</Label>
          <NativeSelect id="report-team" value={teamId} onChange={(event) => setTeamId(event.target.value)} className="w-56">
            <option value="">{user.role === 'ADMIN' ? 'All teams' : 'All my teams'}</option>
            {teamOptions.map((team) => (
              <option key={team.id} value={team.id}>
                {team.name}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>

      {report.isPending && <Skeleton className="h-96 w-full" />}
      {report.isError && <QueryError error={report.error} what="The report" />}
      {report.isSuccess && <Report report={report.data} />}
    </section>
  )
}

function Report({ report }: { report: ServiceDeskReport }) {
  const [asTable, setAsTable] = useState(false)
  const response = report.sla.find((entry) => entry.metric === 'RESPONSE')
  const resolution = report.sla.find((entry) => entry.metric === 'RESOLUTION')

  return (
    <div className="grid gap-6">
      <dl className="grid gap-3 sm:grid-cols-3 lg:grid-cols-5" aria-label="Headline figures">
        <Tile term="Raised" value={report.created} />
        <Tile term="Resolved" value={report.resolved} />
        <Tile term="Open now" value={report.openNow} />
        <Tile term="Open with a breached target" value={report.breachedOpen} />
        <Tile term="First responses on time" value={percent(response?.percentMet)} />
      </dl>

      <section aria-labelledby="volume-heading" className="grid gap-3 rounded-md border p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 id="volume-heading" className="font-semibold">
            Raised and resolved per day
          </h2>
          <Button size="sm" variant="outline" aria-pressed={asTable} onClick={() => setAsTable(!asTable)}>
            {asTable ? 'Show chart' : 'Show table'}
          </Button>
        </div>
        {asTable ? (
          <div className="max-h-80 overflow-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Day</TableHead>
                  <TableHead className="text-right">Raised</TableHead>
                  <TableHead className="text-right">Resolved</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {report.daily.map((day) => (
                  <TableRow key={day.day}>
                    <TableCell>{shortDay(day.day)}</TableCell>
                    <TableCell className="text-right">{day.created}</TableCell>
                    <TableCell className="text-right">{day.resolved}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ) : (
          <LineChart
            title="Tickets raised and resolved per day"
            labels={report.daily.map((day) => shortDay(day.day))}
            series={[
              { key: 'created', label: 'Raised', color: 'var(--series-1)', values: report.daily.map((day) => day.created) },
              { key: 'resolved', label: 'Resolved', color: 'var(--series-2)', values: report.daily.map((day) => day.resolved) },
            ]}
          />
        )}
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section aria-labelledby="backlog-heading" className="grid content-start gap-3 rounded-md border p-4">
          <h2 id="backlog-heading" className="font-semibold">
            Open now, by priority
          </h2>
          {report.backlogByPriority.length === 0 ? (
            <p className="text-sm text-muted-foreground">Nothing open.</p>
          ) : (
            <BarList title="Open tickets by priority" items={report.backlogByPriority.map((entry) => ({ label: entry.priority, value: entry.count }))} />
          )}
        </section>

        <section aria-labelledby="sla-heading" className="grid content-start gap-3 rounded-md border p-4">
          <h2 id="sla-heading" className="font-semibold">
            Service levels finished in the period
          </h2>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Target</TableHead>
                <TableHead className="text-right">Met</TableHead>
                <TableHead className="text-right">Missed</TableHead>
                <TableHead className="text-right">On time</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[response, resolution].map((entry, i) => (
                <TableRow key={i === 0 ? 'RESPONSE' : 'RESOLUTION'}>
                  <TableCell>{SLA_METRIC_LABEL[i === 0 ? 'RESPONSE' : 'RESOLUTION']}</TableCell>
                  <TableCell className="text-right">{entry?.met ?? 0}</TableCell>
                  <TableCell className="text-right">{entry?.missed ?? 0}</TableCell>
                  <TableCell className="text-right">{percent(entry?.percentMet)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </section>
      </div>

      <section aria-labelledby="times-heading" className="grid gap-3 rounded-md border p-4">
        <h2 id="times-heading" className="font-semibold">
          Typical times, by priority
        </h2>
        <p className="text-sm text-muted-foreground">Medians, in elapsed time (nights and weekends included).</p>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Priority</TableHead>
              <TableHead className="text-right">First response</TableHead>
              <TableHead className="text-right">Resolution</TableHead>
              <TableHead className="text-right">Resolved</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {report.times.map((row) => (
              <TableRow key={row.priority}>
                <TableCell>{row.priority}</TableCell>
                <TableCell className="text-right">{row.firstResponseMinutes === null ? '–' : formatMinutes(row.firstResponseMinutes)}</TableCell>
                <TableCell className="text-right">{row.resolutionMinutes === null ? '–' : formatMinutes(row.resolutionMinutes)}</TableCell>
                <TableCell className="text-right">{row.resolvedCount}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section aria-labelledby="categories-heading" className="grid content-start gap-3 rounded-md border p-4">
          <h2 id="categories-heading" className="font-semibold">
            Most common categories
          </h2>
          {report.topCategories.length === 0 ? (
            <p className="text-sm text-muted-foreground">No tickets raised in the period.</p>
          ) : (
            <ol className="grid gap-1 text-sm" aria-label="Most common categories">
              {report.topCategories.map((category) => (
                <li key={category.categoryId} className="flex justify-between gap-2">
                  <span>{category.name}</span>
                  <span className="text-muted-foreground">{category.count}</span>
                </li>
              ))}
            </ol>
          )}
        </section>
        <section aria-labelledby="changes-heading" className="grid content-start gap-3 rounded-md border p-4">
          <h2 id="changes-heading" className="font-semibold">
            Changes closed, by outcome
          </h2>
          {report.changeOutcomes.length === 0 ? (
            <p className="text-sm text-muted-foreground">No changes closed in the period.</p>
          ) : (
            <ul className="grid gap-1 text-sm" aria-label="Changes by outcome">
              {report.changeOutcomes.map((outcome) => (
                <li key={outcome.outcome} className="flex justify-between gap-2">
                  <span>{OUTCOME_LABEL[outcome.outcome as keyof typeof OUTCOME_LABEL] ?? outcome.outcome}</span>
                  <span className="text-muted-foreground">{outcome.count}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  )
}

function Tile({ term, value }: { term: string; value: number | string }) {
  return (
    <div className="rounded-md border p-4">
      <dt className="text-sm text-muted-foreground">{term}</dt>
      <dd className="mt-1 text-2xl font-semibold">{value}</dd>
    </div>
  )
}

/** "50%", or a dash when nothing finished. */
function percent(value: number | null | undefined): string {
  return value === null || value === undefined ? '–' : `${value}%`
}

/** A local calendar day, n days before today, as YYYY-MM-DD. */
function daysAgo(n: number): string {
  const date = new Date()
  date.setDate(date.getDate() - n)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
