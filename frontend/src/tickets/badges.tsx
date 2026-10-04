import type { Priority } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { PRIORITY_LABEL, isClosed, statusLabel } from './labels'

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
