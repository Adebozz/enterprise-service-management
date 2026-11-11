import type { Priority } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { PRIORITY_LABEL, isClosed, statusLabel } from './labels'
import { SLA_STATUS_LABEL, type SlaStatus } from './sla'

export function StatusBadge({ status }: { status: string }) {
  return (
    <Badge variant={isClosed(status) ? 'outline' : 'secondary'} className="whitespace-nowrap">
      {statusLabel(status)}
    </Badge>
  )
}

const PRIORITY_STYLE: Record<Priority, string> = {
  P1: 'bg-red-600 text-white',
  P2: 'bg-orange-500 text-white',
  P3: 'bg-amber-200 text-amber-950',
  P4: 'bg-muted text-foreground',
}

/** Colour reinforces, but never replaces, the text label (WCAG 1.4.1). */
export function PriorityBadge({ priority }: { priority: Priority }) {
  return <Badge className={cn('whitespace-nowrap border-transparent', PRIORITY_STYLE[priority])}>{PRIORITY_LABEL[priority]}</Badge>
}

const SLA_STYLE: Record<SlaStatus, string> = {
  ON_TRACK: 'bg-emerald-100 text-emerald-900',
  AT_RISK: 'bg-amber-200 text-amber-950',
  BREACHED: 'bg-red-600 text-white',
  PAUSED: 'bg-muted text-foreground',
  MET: 'bg-emerald-100 text-emerald-900',
  MISSED: 'bg-red-100 text-red-900',
  CANCELLED: 'bg-muted text-muted-foreground',
}

export function SlaBadge({ status }: { status: SlaStatus }) {
  return <Badge className={cn('whitespace-nowrap border-transparent', SLA_STYLE[status])}>{SLA_STATUS_LABEL[status]}</Badge>
}
