# Service Desk web app

React 19 + TypeScript (strict) + Vite, Tailwind CSS 4 with shadcn/ui (Radix primitives),
React Router, TanStack Query, React Hook Form + Zod, and a typed API client generated from the
backend's OpenAPI contract.

## Run

```bash
npm install
npm run dev        # http://localhost:5173 (proxies /api to the backend on :8080)
```

Start the backend first (see the repository README). Sign in with the local development admin
from `.env.example`.

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Vite dev server with hot reload |
| `npm run build` | Type-check, then production build to `dist/` |
| `npm test` | Vitest + React Testing Library + MSW |
| `npm run lint` / `npm run typecheck` | oxlint / `tsc -b` |
| `npm run gen:api` | Regenerate `src/api/schema.d.ts` from `../docs/openapi.json` |
| `npm run check:api` | Fail if the generated types are stale (used in CI) |

## How it fits together

```
src/
  api/        client.ts (typed openapi-fetch client + auth), errors.ts (ApiError), queries.ts, schema.d.ts (generated)
  auth/       tokenStore (in-memory token), session (single-flight refresh), AuthProvider, RequireAuth, roles
  app/        App, routes, AppLayout, navigation, queryClient
  pages/      LoginPage, HomePage, NotFoundPage, ForbiddenPage
  components/ui/  shadcn/ui components (generated, owned by us)
  test/       MSW server, fixtures, renderApp helper
```

- **Access token in memory only.** The refresh token is an HttpOnly cookie the browser sends
  to `/api/auth/*`. On reload, `AuthProvider` restores the session with a silent refresh.
- **One refresh for many 401s.** `authenticatedFetch` refreshes once (shared by concurrent
  callers) and retries each failed request once; if that fails, the app returns to the login page.
- **Server state in TanStack Query**, no global store. 4xx errors aren't retried.
- **Role checks in the UI are cosmetic.** The API enforces every permission.
