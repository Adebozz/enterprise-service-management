import { useQuery } from '@tanstack/react-query'
import { Bell } from 'lucide-react'
import { Link } from 'react-router'
import { unreadNotificationsQuery } from '@/api/queries'

/** The header's way in to notifications, with the unread count (refreshed every 30 seconds). */
export function NotificationBell() {
  const unread = useQuery(unreadNotificationsQuery)
  const count = unread.data?.count ?? 0

  return (
    <Link
      to="/notifications"
      aria-label={count > 0 ? `Notifications, ${count} unread` : 'Notifications'}
      className="relative rounded-md p-2 hover:bg-muted focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      <Bell aria-hidden="true" className="size-5" />
      {count > 0 && (
        <span
          aria-hidden="true"
          className="absolute -top-0.5 -right-0.5 min-w-5 rounded-full bg-destructive px-1 text-center text-xs leading-5 font-medium text-white"
        >
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Link>
  )
}
