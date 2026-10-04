import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from './AuthContext'
import { hasRole, type Role } from './roles'
import { ForbiddenPage } from '@/pages/ForbiddenPage'

/**
 * Route guard. Unauthenticated users go to the login page (and come back afterwards). A role
 * requirement only decides what the UI shows: the API enforces permissions itself.
 */
export function RequireAuth({ role }: { role?: Role }) {
  const { state } = useAuth()
  const location = useLocation()

  if (state.status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  if (role && !hasRole(state.user, role)) {
    return <ForbiddenPage />
  }
  return <Outlet />
}
