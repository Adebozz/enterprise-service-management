import createClient from 'openapi-fetch'
import { endSession, refreshSession } from '@/auth/session'
import { tokenStore } from '@/auth/tokenStore'
import type { paths } from './schema'

/** Endpoints authenticated by credentials or the refresh cookie: a 401 there is final. */
const SESSION_ENDPOINTS = ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout']

function withAccessToken(request: Request): Request {
  const token = tokenStore.get()
  if (token) {
    request.headers.set('Authorization', `Bearer ${token}`)
  }
  return request
}

/**
 * fetch with authentication: attaches the in-memory access token, and on a 401 refreshes the
 * session once (shared with concurrent callers) and retries the original request once. No page
 * code ever handles tokens.
 */
export async function authenticatedFetch(request: Request): Promise<Response> {
  const retry = request.clone() // a request body can only be read once; keep a copy for the retry
  const response = await fetch(withAccessToken(request))
  const path = new URL(request.url).pathname
  if (response.status !== 401 || SESSION_ENDPOINTS.includes(path)) {
    return response
  }

  const session = await refreshSession()
  if (!session) {
    endSession()
    return response
  }
  return fetch(withAccessToken(retry))
}

/**
 * Typed client generated from docs/openapi.json: paths, parameters, bodies and responses are all
 * checked by the compiler. The API is same-origin (Vite proxy in development, CloudFront in
 * production), so the base URL is the page's own origin.
 */
export const api = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: authenticatedFetch,
})
