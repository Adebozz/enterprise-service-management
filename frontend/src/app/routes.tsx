import type { RouteObject } from 'react-router'
import { RequireAuth } from '@/auth/RequireAuth'
import { LoginPage } from '@/pages/LoginPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { AppLayout } from './AppLayout'

/**
 * Pages behind sign-in are lazy routes: each becomes its own bundle chunk, downloaded only when
 * visited (a requester never downloads the agent queue). Login stays in the main bundle so the
 * first screen renders without an extra round trip.
 *
 * Shared by the browser router and by tests (memory router), so tests exercise real routing.
 */
export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, lazy: async () => ({ Component: (await import('@/pages/HomePage')).HomePage }) },
          { path: 'tickets', lazy: async () => ({ Component: (await import('@/tickets/MyTicketsPage')).MyTicketsPage }) },
          { path: 'tickets/new/incident', lazy: async () => ({ Component: (await import('@/tickets/NewIncidentPage')).NewIncidentPage }) },
          {
            path: 'tickets/new/request',
            lazy: async () => ({ Component: (await import('@/tickets/NewServiceRequestPage')).NewServiceRequestPage }),
          },
          { path: 'tickets/:id', lazy: async () => ({ Component: (await import('@/tickets/TicketPage')).TicketPage }) },
          {
            element: <RequireAuth role="AGENT" />,
            children: [{ path: 'queue', lazy: async () => ({ Component: (await import('@/queue/AgentQueuePage')).AgentQueuePage }) }],
          },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]
