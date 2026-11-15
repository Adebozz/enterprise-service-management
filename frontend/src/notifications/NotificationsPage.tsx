import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { notificationsQuery, queryKeys } from '@/api/queries'
import type { Notification } from '@/api/types'
import { QueryError } from '@/components/QueryError'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import { formatDateTime } from '@/tickets/labels'

/** Your latest notifications. Opening one marks it read and goes to its ticket. */
export function NotificationsPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const inbox = useQuery(notificationsQuery)
  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.notifications })

  const markRead = useMutation({
    mutationFn: async (id: string) => unwrap(await api.POST('/api/notifications/{id}/read', { params: { path: { id } } })),
    onSuccess: refresh,
  })
  const markAllRead = useMutation({
    mutationFn: async () => unwrap(await api.POST('/api/notifications/read-all')),
    onSuccess: refresh,
  })

  async function open(notification: Notification) {
    if (!notification.read) {
      // Going to the ticket matters more than the read mark; a failure here shouldn't stop it.
      await markRead.mutateAsync(notification.id).catch(() => undefined)
    }
    await navigate(`/tickets/${notification.ticketId}`)
  }

  return (
    <section className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold">Notifications</h1>
          {inbox.isSuccess && <p className="text-muted-foreground">{inbox.data.unreadCount === 0 ? 'All caught up.' : `${inbox.data.unreadCount} unread`}</p>}
        </div>
        {inbox.isSuccess && inbox.data.unreadCount > 0 && (
          <Button variant="outline" onClick={() => markAllRead.mutate()} disabled={markAllRead.isPending}>
            Mark all as read
          </Button>
        )}
      </div>
      {inbox.isPending && <Skeleton className="h-24 w-full" />}
      {inbox.isError && <QueryError error={inbox.error} what="Your notifications" />}
      {inbox.isSuccess &&
        (inbox.data.items.length === 0 ? (
          <p className="text-muted-foreground">Nothing yet. You’ll hear here when something happens on your tickets.</p>
        ) : (
          <ul className="grid gap-2" aria-label="Notifications">
            {inbox.data.items.map((notification) => (
              <li key={notification.id}>
                <button
                  type="button"
                  onClick={() => void open(notification)}
                  className={cn(
                    'grid w-full gap-1 rounded-md border p-3 text-left hover:bg-muted focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none',
                    !notification.read && 'border-primary/40 bg-primary/5',
                  )}
                >
                  <span className={cn('text-sm', !notification.read && 'font-medium')}>
                    {!notification.read && <span className="sr-only">Unread: </span>}
                    {notification.message}
                  </span>
                  <time dateTime={notification.createdAt} className="text-xs text-muted-foreground">
                    {formatDateTime(notification.createdAt)}
                  </time>
                </button>
              </li>
            ))}
          </ul>
        ))}
    </section>
  )
}
