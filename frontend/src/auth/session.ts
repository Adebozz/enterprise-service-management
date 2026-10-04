import type { components } from '@/api/schema'
import { tokenStore } from './tokenStore'

export type TokenResponse = components['schemas']['TokenResponse']

let refreshInFlight: Promise<TokenResponse | null> | null = null
const sessionEndedListeners = new Set<() => void>()

/**
 * Exchanges the HttpOnly refresh cookie for a new access token.
 *
 * <p><b>Single-flight:</b> while a refresh is running, every caller receives the same promise. When
 * the token expires, several requests fail with 401 at once; without this they would each present
 * the same refresh token, and the server's reuse detection would rightly treat that as theft.
 *
 * Resolves to null when there is no valid session (no cookie, expired, revoked).
 */
export function refreshSession(): Promise<TokenResponse | null> {
  refreshInFlight ??= requestRefresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

async function requestRefresh(): Promise<TokenResponse | null> {
  try {
    // Plain fetch, not the API client: the client calls refreshSession() on 401, and this
    // request must never trigger itself.
    const response = await fetch(`${window.location.origin}/api/auth/refresh`, {
      method: 'POST',
      credentials: 'same-origin',
    })
    if (!response.ok) {
      tokenStore.clear()
      return null
    }
    const session = (await response.json()) as TokenResponse
    tokenStore.set(session.accessToken)
    return session
  } catch {
    tokenStore.clear()
    return null
  }
}

/** Notified when the session can't be renewed, so the UI can return to the login page. */
export function onSessionEnded(listener: () => void): () => void {
  sessionEndedListeners.add(listener)
  return () => sessionEndedListeners.delete(listener)
}

export function endSession(): void {
  tokenStore.clear()
  sessionEndedListeners.forEach((listener) => listener())
}
