import { createContext, useContext } from 'react'
import type { AuthenticatedUser } from './roles'

export type AuthState =
  | { status: 'restoring' }
  | { status: 'anonymous' }
  | { status: 'authenticated'; user: AuthenticatedUser }

export interface AuthContextValue {
  state: AuthState
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return context
}

/** For components rendered only behind RequireAuth. */
export function useCurrentUser(): AuthenticatedUser {
  const { state } = useAuth()
  if (state.status !== 'authenticated') {
    throw new Error('useCurrentUser used outside an authenticated route')
  }
  return state.user
}
