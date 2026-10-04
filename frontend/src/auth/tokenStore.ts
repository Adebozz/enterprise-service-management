/**
 * The access token lives here, in memory only: never localStorage/sessionStorage or a readable
 * cookie, where any injected script could read it. A page reload loses it by design; the app then
 * restores the session through the HttpOnly refresh cookie (see session.ts).
 */
let accessToken: string | null = null

export const tokenStore = {
  get: (): string | null => accessToken,
  set: (token: string): void => {
    accessToken = token
  },
  clear: (): void => {
    accessToken = null
  },
}
