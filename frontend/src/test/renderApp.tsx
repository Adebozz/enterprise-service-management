import { QueryClient } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { App } from '@/app/App'
import { routes } from '@/app/routes'

/** Renders the real application (real routes, providers and API client) at a given URL. */
export function renderApp(initialPath = '/') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(routes, { initialEntries: [initialPath] })
  const user = userEvent.setup()
  render(<App router={router} queryClient={queryClient} />)
  return { router, user, queryClient }
}
