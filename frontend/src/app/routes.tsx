import type { RouteObject } from 'react-router'
import { RequireAuth } from '@/auth/RequireAuth'
import { HomePage } from '@/pages/HomePage'
import { LoginPage } from '@/pages/LoginPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { MyTicketsPage } from '@/tickets/MyTicketsPage'
import { NewIncidentPage } from '@/tickets/NewIncidentPage'
import { NewServiceRequestPage } from '@/tickets/NewServiceRequestPage'
import { TicketPage } from '@/tickets/TicketPage'
import { AppLayout } from './AppLayout'

/** Shared by the browser router and by tests (memory router), so tests exercise real routing. */
export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <HomePage /> },
          { path: 'tickets', element: <MyTicketsPage /> },
          { path: 'tickets/new/incident', element: <NewIncidentPage /> },
          { path: 'tickets/new/request', element: <NewServiceRequestPage /> },
          { path: 'tickets/:id', element: <TicketPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]
