import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import type { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router'
import { AuthProvider } from '@/auth/AuthProvider'

type Router = ReturnType<typeof createBrowserRouter>

export function App({ router, queryClient }: { router: Router; queryClient: QueryClient }) {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  )
}
