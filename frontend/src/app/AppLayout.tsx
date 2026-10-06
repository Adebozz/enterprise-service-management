import { LogOut } from 'lucide-react'
import { NavLink, Outlet } from 'react-router'
import { useAuth, useCurrentUser } from '@/auth/AuthContext'
import { ROLE_LABEL } from '@/auth/roles'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { navigationFor } from './navigation'

export function AppLayout() {
  const user = useCurrentUser()
  const { logout } = useAuth()

  return (
    <div className="min-h-svh bg-background">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-background focus:px-3 focus:py-2"
      >
        Skip to main content
      </a>
      <header className="border-b">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-6 px-4">
          <span className="font-semibold">Service Desk</span>
          <nav aria-label="Main" className="flex flex-1 gap-1 overflow-x-auto">
            {navigationFor(user).map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                // "My tickets" is the list only: a ticket an agent opened from the queue isn't theirs.
                end={item.to === '/' || item.to === '/tickets'}
                className={({ isActive }) =>
                  cn(
                    'rounded-md px-3 py-1.5 text-sm whitespace-nowrap hover:bg-muted',
                    isActive && 'bg-muted font-medium',
                  )
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" aria-label={`Account menu for ${user.displayName}`}>
                {user.displayName}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuLabel>
                <div className="font-medium">{user.displayName}</div>
                <div className="text-xs font-normal text-muted-foreground">{user.email}</div>
                <div className="text-xs font-normal text-muted-foreground">{ROLE_LABEL[user.role]}</div>
              </DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuItem onSelect={() => void logout()}>
                <LogOut aria-hidden="true" /> Sign out
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      <main id="main" className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
