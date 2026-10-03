# API reference

The machine-readable contract is [`openapi.json`](openapi.json) (OpenAPI 3.1). It's generated
from the code, committed, and checked by `ApiContractIT`, so it can't silently drift. When the
backend runs, Swagger UI is at `/swagger-ui.html`.

This page covers the conventions and gives an overview. For every field and type, use the contract.

## Conventions

| Topic | Convention |
|---|---|
| Base path | `/api`. The web app and API share one origin (no CORS) |
| Format | JSON (`application/json`); errors are `application/problem+json` |
| Ids | UUIDs (v7, time-ordered). Tickets also have a human reference such as `INC-000042` |
| Time | ISO-8601 UTC instants, e.g. `2026-10-03T09:15:00Z`. Date filters are whole UTC days |
| Nulls | Response fields are always present; a field that may be empty is `null`, never missing |
| Auth | `Authorization: Bearer <access token>` on every endpoint except login/refresh/logout |
| Lost updates | Mutating ticket/user/team/category endpoints take the `version` you last read. A stale version gets **409 `CONCURRENT_MODIFICATION`**: reload and retry. Responses carry the new `version` |
| Paging | `page` (from 0), `size` (default 20, max 100), `sort=field,asc\|desc` (repeatable). Only listed fields are sortable (400 `INVALID_SORT_PROPERTY`). Body: `{content, page, size, totalElements, totalPages}` |
| Correlation | Send `X-Request-Id` (8–64 of `A-Za-z0-9-`) or get one generated. It comes back as a header and as `correlationId` in errors |

## Authentication

```
POST /api/auth/login   {email, password}
  → 200 {accessToken, tokenType: "Bearer", expiresIn: 900, user}
    + Set-Cookie: esm_refresh=…; HttpOnly; Secure; SameSite=Strict; Path=/api/auth
POST /api/auth/refresh  (cookie)  → 200 new access token + rotated cookie
POST /api/auth/logout   (cookie)  → 204, session revoked, cookie cleared
```

- Keep the access token **in memory**. On 401, call `/refresh` once, then retry; if refresh fails,
  send the user to the login page.
- Refresh tokens rotate on every use. A reused (stolen) one revokes the whole session.
- Browsers: `/refresh` and `/logout` reject a cross-site `Origin` (403 `INVALID_ORIGIN`).

Details and threat model: [security.md](security.md).

## Errors

Every error has the same shape (`ApiProblem`, [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)):

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Incident cannot transition from CLOSED to IN_PROGRESS",
  "instance": "/api/tickets/0199b2c4-6a1e-7c3e-9f00-1a2b3c4d5e6f/transitions",
  "code": "INVALID_STATUS_TRANSITION",
  "timestamp": "2026-10-03T09:15:00Z",
  "correlationId": "4f1c2a9e-0b7d-4e57-a3a5-9d1f3c2b8e10",
  "fieldErrors": null
}
```

**Branch on `code`, never on `detail`.** `detail` is for humans and may change wording. A ticket
you may not see is reported as `RESOURCE_NOT_FOUND`, exactly like one that doesn't exist.

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Invalid input; see `fieldErrors` when field-level |
| `MALFORMED_REQUEST` | 400 | Unreadable body or parameter (bad JSON, bad UUID) |
| `INVALID_SORT_PROPERTY` | 400 | Sorting by a field that isn't allowed |
| `REQUEST_FAILED` | 4xx | Other request error raised by the framework |
| `AUTHENTICATION_REQUIRED` | 401 | Missing, invalid or expired access token |
| `INVALID_CREDENTIALS` | 401 | Wrong email/password, or inactive account (deliberately indistinguishable) |
| `REFRESH_TOKEN_INVALID` | 401 | Session expired, revoked or reused: sign in again |
| `ACCESS_DENIED` | 403 | Your role can't do this |
| `INVALID_ORIGIN` | 403 | Cookie endpoint called from a foreign site |
| `TRANSITION_NOT_PERMITTED` | 403 | Valid status change, but not for you (e.g. a requester resolving) |
| `ASSIGNMENT_NOT_PERMITTED` | 403 | You can't make this ownership change |
| `COMMENT_NOT_PERMITTED` | 403 | Internal notes are for support on this ticket |
| `RESOURCE_NOT_FOUND` | 404 | Doesn't exist, or you may not see it |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for this path |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Send `application/json` |
| `CONCURRENT_MODIFICATION` | 409 | Your `version` is stale: reload |
| `INVALID_STATUS_TRANSITION` | 409 | Not a move in this ticket type's lifecycle |
| `TICKET_NOT_ASSIGNABLE` | 409 | Resolved/closed tickets can't change owner |
| `TICKET_CLOSED` | 409 | Closed tickets accept no comments |
| `EMAIL_ALREADY_EXISTS` | 409 | Another user has this email (case-insensitive) |
| `TEAM_NAME_ALREADY_EXISTS` | 409 | Another team has this name (case-insensitive) |
| `CATEGORY_CODE_ALREADY_EXISTS` | 409 | Category code taken |
| `LAST_ADMIN_REQUIRED` | 409 | Would leave no active administrator |
| `MEMBER_HAS_OPEN_TICKETS` | 409 | Reassign the person's open tickets first |
| `CURRENT_PASSWORD_INCORRECT` | 400 | Password change: current password wrong |
| `TEAM_INACTIVE` | 422 | Target team is deactivated |
| `USER_NOT_ELIGIBLE_FOR_TEAM` | 422 | Only active staff can join teams |
| `ASSIGNEE_NOT_IN_TEAM` | 422 | Assignee must be an active member of the team |
| `INVALID_CATEGORY` | 422 | Category not available for this ticket type / wrong parent |
| `CATEGORY_TEAM_REQUIRED` | 422 | Top-level categories must route to a team |
| `TRANSITION_REQUIREMENT_MISSING` | 422 | E.g. resolving without resolution code + notes |
| `TICKET_NOT_ASSIGNED` | 422 | Work can't start before someone owns the ticket |
| `INTERNAL_ERROR` | 500 | Unexpected; quote `correlationId` when reporting |

`ApiDocumentationTest` fails the build if a code exists in the code but not in this table.

## Endpoints

Who may call what is enforced by the server ([security.md](security.md)). "Support" means staff
handling that ticket (its assignee or a member of its team). Operation ids are the function names
in the generated client.

### Session & profile

| Method | Path | operationId | Who |
|---|---|---|---|
| POST | `/api/auth/login` | `login` | anyone |
| POST | `/api/auth/refresh` | `refreshSession` | refresh cookie |
| POST | `/api/auth/logout` | `logout` | refresh cookie |
| GET | `/api/users/me` | `getMyProfile` | signed in |
| POST | `/api/users/me/password` | `changeMyPassword` | signed in (ends all sessions) |

### Tickets

| Method | Path | operationId | Who |
|---|---|---|---|
| POST | `/api/incidents` | `createIncident` | signed in → 201 |
| POST | `/api/service-requests` | `createServiceRequest` | signed in → 201 |
| GET | `/api/tickets` | `listTickets` | signed in; only tickets you may see |
| GET | `/api/tickets/{id}` | `getTicket` | requester, support, admin |
| GET | `/api/tickets/{id}/transitions` | `listAvailableTransitions` | anyone who can see it; returns *your* moves |
| POST | `/api/tickets/{id}/transitions` | `transitionTicket` | depends on the move |
| PUT | `/api/tickets/{id}/assignment` | `assignTicket` | agents and above, per assignment rules |
| GET | `/api/tickets/{id}/comments` | `listComments` | anyone who can see it (internal notes: support/admin) |
| POST | `/api/tickets/{id}/comments` | `addComment` | anyone who can see it → 201 |
| GET | `/api/tickets/{id}/history` | `getTicketHistory` | support, admin |
| GET | `/api/categories?type=` | `listCategories` | signed in |

`listTickets` filters: `view=ALL|REQUESTED|MINE|TEAM|UNASSIGNED`, `type`, `status`, `priority`
(repeat or comma-separate), `teamId`, `assigneeId`, `categoryId` (includes subcategories),
`createdFrom` / `createdTo` (inclusive days), `open=true`, `q` (reference such as `inc-42`, full-text
such as `printer -office`, or requester/category name). Sortable: `createdAt, updatedAt, priority,
reference, status, title`.

### Teams

| Method | Path | operationId | Who |
|---|---|---|---|
| GET | `/api/teams` | `listTeams` | agents and above |
| GET | `/api/teams/{id}` | `getTeam` | agents and above |
| GET | `/api/teams/{id}/members` | `listTeamMembers` | agents and above |

### Administration (ADMIN)

| Method | Path | operationId |
|---|---|---|
| GET / POST | `/api/admin/users` | `searchUsers` / `createUser` (201) |
| GET / PATCH | `/api/admin/users/{id}` | `getUser` / `updateUser` |
| GET / POST | `/api/admin/teams` | `listAllTeams` / `createTeam` (201) |
| PATCH | `/api/admin/teams/{id}` | `updateTeam` |
| PUT / DELETE | `/api/admin/teams/{id}/members/{userId}` | `addTeamMember` / `removeTeamMember` (idempotent, 204) |
| GET / POST | `/api/admin/categories` | `listAllCategories` / `createCategory` (201) |
| PATCH | `/api/admin/categories/{id}` | `updateCategory` |

## Walkthrough: an incident from report to closure

```bash
API=http://localhost:8080
login() { curl -s -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"$2\"}" $API/api/auth/login | jq -r .accessToken; }

REQ=$(login requester@example.com '…')   # tokens for three roles
AGENT=$(login agent@example.com '…')

# 1. Requester reports it. Priority is calculated and the ticket is routed to a team.
ID=$(curl -s -H "Authorization: Bearer $REQ" -H 'Content-Type: application/json' \
  -d '{"title":"Wi-Fi down","description":"Floor 3","categoryId":"<category id>","impact":"HIGH","urgency":"HIGH"}' \
  $API/api/incidents | jq -r .id)                    # → INC-000001, P1, status NEW

# 2. Agent takes it from the team's unassigned queue (status → ASSIGNED).
curl -s -X PUT -H "Authorization: Bearer $AGENT" -H 'Content-Type: application/json' \
  -d '{"teamId":"<team id>","assigneeId":"<agent id>","version":0}' $API/api/tickets/$ID/assignment

# 3. Agent asks which moves they have, then makes them.
curl -s -H "Authorization: Bearer $AGENT" $API/api/tickets/$ID/transitions
curl -s -H "Authorization: Bearer $AGENT" -H 'Content-Type: application/json' \
  -d '{"targetStatus":"IN_PROGRESS","version":1}' $API/api/tickets/$ID/transitions
curl -s -H "Authorization: Bearer $AGENT" -H 'Content-Type: application/json' \
  -d '{"targetStatus":"RESOLVED","version":2,"resolutionCode":"FIXED","notes":"Rebooted AP"}' \
  $API/api/tickets/$ID/transitions

# 4. Requester confirms. Every step is in the audit timeline (/history, support only).
curl -s -H "Authorization: Bearer $REQ" -H 'Content-Type: application/json' \
  -d '{"targetStatus":"CLOSED","version":3}' $API/api/tickets/$ID/transitions
```

## Changing the API

1. Change the code.
2. Run `./mvnw verify`. `ApiContractIT` fails and writes the new contract to
   `backend/target/openapi.actual.json`.
3. Review the difference, then accept it:
   `./mvnw verify -Dit.test=ApiContractIT -Dopenapi.update=true`.
4. Commit `docs/openapi.json` with the code. The frontend regenerates its types from it.
