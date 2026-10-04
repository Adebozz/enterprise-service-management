import { hasRole, type AuthenticatedUser, type Role } from '@/auth/roles'

export interface NavItem {
  to: string
  label: string
  /** Minimum role that sees the link (UX only; the API enforces access). */
  role: Role
}

/** Grows with each portal milestone: requester (M10), agent (M11), admin (Phase 2). */
const NAV_ITEMS: NavItem[] = [{ to: '/', label: 'Home', role: 'REQUESTER' }]

export function navigationFor(user: AuthenticatedUser): NavItem[] {
  return NAV_ITEMS.filter((item) => hasRole(user, item.role))
}
