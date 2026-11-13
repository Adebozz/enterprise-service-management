/** Converts between the API's ISO instants and the browser's datetime-local input, in local time. */
export function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return ''
  const date = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function fromLocalInput(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined
}

const day = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
const time = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' })

/** "Sat 16 Nov, 07:00–09:00", or both dates when the window spans midnight. */
export function formatWindow(startIso: string, endIso: string): string {
  const start = new Date(startIso)
  const end = new Date(endIso)
  const sameDay = start.toDateString() === end.toDateString()
  return sameDay
    ? `${day.format(start)}, ${time.format(start)}–${time.format(end)}`
    : `${day.format(start)}, ${time.format(start)} – ${day.format(end)}, ${time.format(end)}`
}

/** Whether a change's plan can still be edited (mirrors the server's Change.isPlanEditable). */
export function isPlanEditable(status: string): boolean {
  return status === 'NEW' || status === 'ASSIGNED'
}
