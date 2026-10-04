import { api } from './client'
import { unwrap } from './errors'

/** Query definitions shared by pages, so cache keys stay consistent across the app. */
export const myProfileQuery = {
  queryKey: ['me'] as const,
  queryFn: async () => unwrap(await api.GET('/api/users/me')),
}
