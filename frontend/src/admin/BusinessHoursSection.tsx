import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { businessHoursQuery, queryKeys } from '@/api/queries'
import type { BusinessHours, OpeningDay } from '@/api/types'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDate } from '@/tickets/labels'
import { adminErrorMessage } from './adminErrors'

type Day = OpeningDay['day']
const DAYS: [Day, string][] = [
  ['MONDAY', 'Monday'],
  ['TUESDAY', 'Tuesday'],
  ['WEDNESDAY', 'Wednesday'],
  ['THURSDAY', 'Thursday'],
  ['FRIDAY', 'Friday'],
  ['SATURDAY', 'Saturday'],
  ['SUNDAY', 'Sunday'],
]

/** "09:00:00" → "09:00", for time inputs. */
const hhmm = (time: string) => time.slice(0, 5)

/** The desk's opening hours and closed days, used by targets that count business hours. */
export function BusinessHoursSection() {
  const calendar = useQuery(businessHoursQuery)

  return (
    <section aria-labelledby="business-hours-heading" className="grid gap-4">
      <div className="grid gap-1">
        <h2 id="business-hours-heading" className="text-xl font-semibold">
          Business hours
        </h2>
        <p className="text-muted-foreground">
          When targets that count business hours are running. Changes apply at once: the deadlines of open tickets move with them.
        </p>
      </div>
      {calendar.isPending && <Skeleton className="h-48 w-full" />}
      {calendar.isError && <QueryError error={calendar.error} what="The business hours" />}
      {calendar.isSuccess && (
        <div className="grid gap-6 lg:grid-cols-2">
          <WeekForm calendar={calendar.data} />
          <Holidays calendar={calendar.data} />
        </div>
      )}
    </section>
  )
}

function WeekForm({ calendar }: { calendar: BusinessHours }) {
  const queryClient = useQueryClient()
  const [week, setWeek] = useState(() =>
    DAYS.map(([day]) => {
      const open = calendar.week.find((entry) => entry.day === day)
      return { day, open: Boolean(open), opensAt: open ? hhmm(open.opensAt) : '09:00', closesAt: open ? hhmm(open.closesAt) : '17:30' }
    }),
  )
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const save = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.PUT('/api/admin/business-hours/week', {
          body: { days: week.filter((entry) => entry.open).map(({ day, opensAt, closesAt }) => ({ day, opensAt, closesAt })) },
        }),
      ),
  })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setMessage(null)
    if (!week.some((entry) => entry.open)) {
      setMessage({ ok: false, text: 'The desk must be open on at least one day.' })
      return
    }
    try {
      await save.mutateAsync()
      setMessage({ ok: true, text: 'Opening hours saved.' })
      await queryClient.invalidateQueries({ queryKey: queryKeys.businessHours })
    } catch (error) {
      setMessage({ ok: false, text: adminErrorMessage(error, 'The opening hours') })
    }
  }

  const update = (day: Day, change: Partial<(typeof week)[number]>) => setWeek(week.map((entry) => (entry.day === day ? { ...entry, ...change } : entry)))

  return (
    <form onSubmit={(event) => void onSubmit(event)} className="grid gap-3 rounded-md border p-4">
      <h3 className="font-semibold">Opening hours ({calendar.zone})</h3>
      {message && (
        <Alert variant={message.ok ? 'default' : 'destructive'} role={message.ok ? 'status' : 'alert'}>
          <AlertDescription>{message.text}</AlertDescription>
        </Alert>
      )}
      {DAYS.map(([day, label]) => {
        const entry = week.find((candidate) => candidate.day === day)!
        return (
          <fieldset key={day} className="flex flex-wrap items-center gap-3 text-sm">
            <legend className="sr-only">{label}</legend>
            <label className="flex w-32 items-center gap-2">
              <input type="checkbox" checked={entry.open} onChange={(event) => update(day, { open: event.target.checked })} />
              {label}
            </label>
            {entry.open ? (
              <>
                <Input
                  type="time"
                  aria-label={`${label} opens`}
                  value={entry.opensAt}
                  onChange={(event) => update(day, { opensAt: event.target.value })}
                  className="w-32"
                  required
                />
                <span aria-hidden="true">to</span>
                <Input
                  type="time"
                  aria-label={`${label} closes`}
                  value={entry.closesAt}
                  onChange={(event) => update(day, { closesAt: event.target.value })}
                  className="w-32"
                  required
                />
              </>
            ) : (
              <span className="text-muted-foreground">Closed</span>
            )}
          </fieldset>
        )
      })}
      <div>
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Save opening hours'}
        </Button>
      </div>
    </form>
  )
}

function Holidays({ calendar }: { calendar: BusinessHours }) {
  const queryClient = useQueryClient()
  const [day, setDay] = useState('')
  const [name, setName] = useState('')
  const [failure, setFailure] = useState<string | null>(null)
  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.businessHours })

  const add = useMutation({ mutationFn: async () => unwrap(await api.POST('/api/admin/business-hours/holidays', { body: { day, name: name.trim() } })) })
  const remove = useMutation({
    mutationFn: async (holiday: string) => unwrap(await api.DELETE('/api/admin/business-hours/holidays/{day}', { params: { path: { day: holiday } } })),
  })

  async function onAdd(event: FormEvent) {
    event.preventDefault()
    setFailure(null)
    try {
      await add.mutateAsync()
      setDay('')
      setName('')
      await refresh()
    } catch (error) {
      setFailure(adminErrorMessage(error, 'The holiday'))
    }
  }

  return (
    <div className="grid content-start gap-3 rounded-md border p-4">
      <h3 className="font-semibold">Holidays</h3>
      {failure && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{failure}</AlertDescription>
        </Alert>
      )}
      {calendar.holidays.length === 0 ? (
        <p className="text-sm text-muted-foreground">No holidays: the week above applies every week.</p>
      ) : (
        <ul aria-label="Holidays" className="grid gap-2">
          {calendar.holidays.map((holiday) => (
            <li key={holiday.day} className="flex items-center justify-between gap-2 rounded-md border p-2 text-sm">
              <span>
                <span className="font-medium">{formatDate(holiday.day)}</span> · {holiday.name}
              </span>
              <Button
                size="sm"
                variant="ghost"
                aria-label={`Remove ${holiday.name}`}
                disabled={remove.isPending}
                onClick={async () => {
                  setFailure(null)
                  try {
                    await remove.mutateAsync(holiday.day)
                  } catch (error) {
                    setFailure(adminErrorMessage(error, 'The change'))
                  }
                  await refresh()
                }}
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={(event) => void onAdd(event)} className="flex flex-wrap items-end gap-2">
        <div className="grid gap-1">
          <Label htmlFor="holiday-day">Day</Label>
          <Input id="holiday-day" type="date" required value={day} onChange={(event) => setDay(event.target.value)} className="w-40" />
        </div>
        <div className="grid gap-1">
          <Label htmlFor="holiday-name">Name</Label>
          <Input id="holiday-name" required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} className="w-48" />
        </div>
        <Button type="submit" variant="outline" disabled={add.isPending}>
          Add holiday
        </Button>
      </form>
    </div>
  )
}
