import { useState, type PointerEvent } from 'react'

export interface Series {
  key: string
  label: string
  /** A CSS colour, e.g. var(--series-1). Marks only: text never wears it. */
  color: string
  values: number[]
}

const WIDTH = 720
const HEIGHT = 220
const PAD = { top: 12, right: 72, bottom: 28, left: 36 }

/** Clean y-axis steps: the top tick is the smallest of 1/2/5 × 10ⁿ at or above the maximum. */
function niceMax(max: number): number {
  if (max <= 0) return 1
  const power = 10 ** Math.floor(Math.log10(max))
  return [1, 2, 5, 10].map((m) => m * power).find((candidate) => candidate >= max) ?? max
}

/**
 * Lines over days, with one shared axis. Hovering shows a crosshair and every series' value for
 * that day; the same numbers are in the table view, for keyboards, screen readers and print.
 */
export function LineChart({ labels, series, title }: { labels: string[]; series: Series[]; title: string }) {
  const [hover, setHover] = useState<number | null>(null)
  const top = niceMax(Math.max(0, ...series.flatMap((s) => s.values)))
  const plotWidth = WIDTH - PAD.left - PAD.right
  const plotHeight = HEIGHT - PAD.top - PAD.bottom
  const x = (i: number) => PAD.left + (labels.length <= 1 ? plotWidth / 2 : (i * plotWidth) / (labels.length - 1))
  const y = (v: number) => PAD.top + plotHeight - (v / top) * plotHeight
  const ticks = [0, top / 2, top]
  const last = labels.length - 1

  function onMove(event: PointerEvent<SVGSVGElement>) {
    const box = event.currentTarget.getBoundingClientRect()
    const svgX = ((event.clientX - box.left) / box.width) * WIDTH
    const index = Math.round(((svgX - PAD.left) / plotWidth) * last)
    setHover(Math.min(last, Math.max(0, index)))
  }

  return (
    <figure className="grid gap-2">
      <figcaption className="sr-only">{title}</figcaption>
      <ul className="flex flex-wrap gap-4 text-sm text-muted-foreground" aria-label="Legend">
        {series.map((s) => (
          <li key={s.key} className="flex items-center gap-2">
            <span aria-hidden="true" className="inline-block h-0.5 w-4 rounded" style={{ background: s.color }} />
            {s.label}
          </li>
        ))}
      </ul>
      <div className="relative">
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          className="h-auto w-full touch-none"
          role="img"
          aria-label={title}
          onPointerMove={onMove}
          onPointerLeave={() => setHover(null)}
        >
          {ticks.map((tick) => (
            <g key={tick}>
              <line x1={PAD.left} x2={WIDTH - PAD.right} y1={y(tick)} y2={y(tick)} stroke="var(--border)" strokeWidth={1} />
              <text x={PAD.left - 8} y={y(tick)} dy="0.32em" textAnchor="end" className="fill-muted-foreground text-[11px]">
                {Number.isInteger(tick) ? tick : tick.toFixed(1)}
              </text>
            </g>
          ))}
          {[0, Math.floor(last / 2), last]
            .filter((i, n, all) => all.indexOf(i) === n && labels[i])
            .map((i) => (
              <text
                key={i}
                x={x(i)}
                y={HEIGHT - 8}
                textAnchor={i === 0 ? 'start' : i === last ? 'end' : 'middle'}
                className="fill-muted-foreground text-[11px]"
              >
                {labels[i]}
              </text>
            ))}
          {hover !== null && <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotHeight} stroke="var(--muted-foreground)" strokeWidth={1} />}
          {series.map((s) => (
            <g key={s.key}>
              <polyline
                points={s.values.map((v, i) => `${x(i)},${y(v)}`).join(' ')}
                fill="none"
                stroke={s.color}
                strokeWidth={2}
                strokeLinejoin="round"
                strokeLinecap="round"
              />
              {/* End marker with a surface ring, and the series named at the line's end. */}
              <circle cx={x(last)} cy={y(s.values[last] ?? 0)} r={4} fill={s.color} stroke="var(--background)" strokeWidth={2} />
              <text x={x(last) + 8} y={y(s.values[last] ?? 0)} dy="0.32em" className="fill-foreground text-[11px]">
                {s.label}
              </text>
              {hover !== null && <circle cx={x(hover)} cy={y(s.values[hover] ?? 0)} r={4} fill={s.color} stroke="var(--background)" strokeWidth={2} />}
            </g>
          ))}
        </svg>
        {hover !== null && (
          <div
            role="tooltip"
            className="pointer-events-none absolute top-0 rounded-md border bg-popover px-3 py-2 text-xs shadow-sm"
            style={{ left: `${(x(hover) / WIDTH) * 100}%`, transform: hover > last / 2 ? 'translateX(-105%)' : 'translateX(5%)' }}
          >
            <div className="font-medium">{labels[hover]}</div>
            {series.map((s) => (
              <div key={s.key} className="flex items-center gap-2">
                <span aria-hidden="true" className="inline-block size-2 rounded-full" style={{ background: s.color }} />
                {s.label}: {s.values[hover]}
              </div>
            ))}
          </div>
        )}
      </div>
    </figure>
  )
}

/** Horizontal bars from one baseline, one series: the value sits at each bar's tip. */
export function BarList({ items, title, color = 'var(--series-1)' }: { items: { label: string; value: number }[]; title: string; color?: string }) {
  const max = Math.max(1, ...items.map((item) => item.value))
  return (
    <figure className="grid gap-2">
      <figcaption className="sr-only">{title}</figcaption>
      <ul className="grid gap-2" aria-label={title}>
        {items.map((item) => (
          <li key={item.label} className="grid grid-cols-[3rem_1fr] items-center gap-2 text-sm" title={`${item.label}: ${item.value}`}>
            <span className="text-muted-foreground">{item.label}</span>
            <span className="flex items-center gap-2">
              <span
                aria-hidden="true"
                className="h-4 rounded-r-[4px]"
                style={{ width: `${(item.value / max) * 85}%`, minWidth: item.value > 0 ? 4 : 0, background: color }}
              />
              <span>{item.value}</span>
            </span>
          </li>
        ))}
      </ul>
    </figure>
  )
}
