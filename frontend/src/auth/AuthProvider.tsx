import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { AuthContext, type AuthContextValue, type AuthState } from './AuthContext'
import { onSessionEnded, refreshSession } from './session'
import { tokenStore } from './tokenStore'

/**
 * Owns "who is signed in". On startup it tries a silent refresh (the browser sends the HttpOnly
 * cookie), so a page reload keeps the user signed in without ever persisting a token in JavaScript.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<AuthState>({ status: 'restoring' })

  const signOutLocally = useCallback(() => {
    tokenStore.clear()
    queryClient.clear() // never show the previous user's cached data to the next one
    setState({ status: 'anonymous' })
  }, [queryClient])

  useEffect(() => {
    let active = true
    void refreshSession().then((session) => {
      if (active) {
        setState(session ? { status: 'authenticated', user: session.user } : { status: 'anonymous' })
      }
    })
    const unsubscribe = onSessionEnded(signOutLocally)
    return () => {
      active = false
      unsubscribe()
    }
  }, [signOutLocally])

  const login = useCallback(async (email: string, password: string) => {
    const session = unwrap(await api.POST('/api/auth/login', { body: { email, password } }))
    tokenStore.set(session.accessToken)
    setState({ status: 'authenticated', user: session.user })
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.POST('/api/auth/logout')
    } finally {
      signOutLocally()
    }
  }, [signOutLocally])

  const value = useMemo<AuthContextValue>(() => ({ state, login, logout }), [state, login, logout])

  if (state.status === 'restoring') {
    return (
      <div className="flex min-h-svh items-center justify-center text-muted-foreground" role="status" aria-live="polite">
        Loading…
      </div>
    )
  }
  return <AuthContext value={value}>{children}</AuthContext>
}
