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
| `PROBLEM_UPDATE_NOT_PERMITTED` | 403 | Only the team working on a problem (or an admin) records its analysis |
| `INCIDENT_LINK_NOT_PERMITTED` | 403 | Only staff handling the incident or the problem can link them |
| `CHANGE_UPDATE_NOT_PERMITTED` | 403 | Only the team carrying out a change (or an admin) writes its plan or records its outcome |
| `APPROVAL_NOT_PERMITTED` | 403 | Only team leads of the request's team, and admins, decide approvals; never the requester |
| `RESOURCE_NOT_FOUND` | 404 | Doesn't exist, or you may not see it |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for this path |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Send `application/json` |
| `CONCURRENT_MODIFICATION` | 409 | Your `version` is stale: reload |
| `INVALID_STATUS_TRANSITION` | 409 | Not a move in this ticket type's lifecycle |
| `TICKET_NOT_ASSIGNABLE` | 409 | Resolved/closed tickets can't change owner |
| `TICKET_CLOSED` | 409 | Closed tickets accept no comments |
| `PROBLEM_NOT_OPEN` | 409 | The problem is resolved, closed or cancelled: its analysis is final and it takes no more incidents |
| `CHANGE_NOT_EDITABLE` | 409 | The plan is fixed once submitted; the outcome is recorded only once implementation has started |
| `APPROVAL_ALREADY_DECIDED` | 409 | The approval was already approved, rejected or withdrawn |
| `EMAIL_ALREADY_EXISTS` | 409 | Another user has this email (case-insensitive) |
| `TEAM_NAME_ALREADY_EXISTS` | 409 | Another team has this name (case-insensitive) |
| `CATEGORY_CODE_ALREADY_EXISTS` | 409 | Category code taken |
| `CATALOGUE_ITEM_NAME_ALREADY_EXISTS` | 409 | Another catalogue item has this name (case-insensitive) |
| `LAST_ADMIN_REQUIRED` | 409 | Would leave no active administrator |
| `MEMBER_HAS_OPEN_TICKETS` | 409 | Reassign the person's open tickets first |
| `CURRENT_PASSWORD_INCORRECT` | 400 | Password change: current password wrong |
| `TEAM_INACTIVE` | 422 | Target team is deactivated |
| `USER_NOT_ELIGIBLE_FOR_TEAM` | 422 | Only active staff can join teams |
| `ASSIGNEE_NOT_IN_TEAM` | 422 | Assignee must be an active member of the team |
| `INVALID_CATEGORY` | 422 | Category not available for this ticket type / wrong parent |
| `CATEGORY_TEAM_REQUIRED` | 422 | Top-level categories must route to a team |
| `INVALID_CATALOGUE_FORM` | 422 | The item's questions don't fit together (duplicate keys, a choice with fewer than two options...); see `fieldErrors` (`fields[i].…`) |
| `APPROVAL_REASON_REQUIRED` | 422 | Rejecting a request needs a comment saying why |
| `CHANGE_PLAN_INCOMPLETE` | 422 | Submitting needs an implementation plan, a backout plan and a planned window |
| `CHANGE_PLAN_INVALID` | 422 | The plan breaks a rule: a standard change must be low risk and is scheduled, not submitted; the window must end after it starts, and (except in an emergency) not have started yet |
| `CHANGE_REVIEW_INCOMPLETE` | 422 | Record how the change went before closing it |
| `CATALOGUE_ANSWERS_INVALID` | 422 | Answers don't match the item's form; see `fieldErrors` (`answers.<key>`) |
| `PROBLEM_ANALYSIS_INCOMPLETE` | 422 | A known error needs a root cause and workaround (and keeps them); resolving needs the root cause |
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
| GET | `/api/tickets/{id}/sla` | `getTicketSla` | support, admin |
| GET | `/api/categories?type=` | `listCategories` | signed in |

`listTickets` filters: `view=ALL|REQUESTED|MINE|TEAM|UNASSIGNED`, `type`, `status`, `priority`
(repeat or comma-separate), `teamId`, `assigneeId`, `categoryId` (includes subcategories),
`createdFrom` / `createdTo` (inclusive days), `open=true`, `q` (reference such as `inc-42`, full-text
such as `printer -office`, or requester/category name). Sortable: `createdAt, updatedAt, priority,
reference, status, title, slaDueAt` (the next SLA deadline; paused and finished targets last).
For staff, each row's `sla` is the open target (response until support responds, then resolution)
with status `ON_TRACK | AT_RISK | BREACHED | PAUSED`; it is always `null` for requesters.

`getTicketSla` returns both clocks with status `ON_TRACK | AT_RISK | BREACHED | PAUSED | MET |
MISSED | CANCELLED`, target, elapsed and remaining seconds, and due, breach and completion times.

### Service catalogue

| Method | Path | operationId | Who |
|---|---|---|---|
| GET | `/api/catalogue` | `listCatalogue` | signed in; available items only |
| GET | `/api/catalogue/{id}` | `getCatalogueItem` | signed in (withdrawn items: admins only) |
| POST | `/api/catalogue/{id}/requests` | `orderCatalogueItem` | signed in → 201, like `createServiceRequest` |

An item's `fields` are its questions: `key`, `label`, `type` (`TEXT` up to 500 characters,
`LONG_TEXT` up to 4,000, `SELECT` one of `options`, `DATE` as `2026-12-01`), `required` and an
optional `helpText`. `approvalRequired` (default `false`) holds each request for sign-off (see
Approvals). To order, send `answers` keyed by question key, plus optional `notes` and
`urgency`. Every problem is reported at once, one `fieldErrors` entry per question. The new
request is titled with the item's name, routed by the item's category, and its description lists
each answer ("Question: answer"); `serviceRequest.answers` on the ticket keeps the questions as they were worded when
it was raised.

### Approvals

| Method | Path | operationId | Who |
|---|---|---|---|
| GET | `/api/approvals` | `listMyPendingApprovals` | team leads and admins; only requests and changes they may decide (`type` says which) |
| GET | `/api/tickets/{id}/approval` | `getTicketApproval` | anyone who can see the ticket; 404 if it needs none |
| POST | `/api/approvals/{id}/decision` | `decideApproval` | team leads of the request's team, admins; never the requester |

Ordering an item with `approvalRequired` raises the request straight into `APPROVAL_PENDING`,
with its SLA clocks paused. A decision is `APPROVE` (comment optional) or `REJECT` (comment
required), with the approval's `version`. It moves the request to `APPROVED`, where support can
start work, or to `REJECTED`, which ends it. The comment is posted to the conversation. A request
cancelled while waiting has its approval `WITHDRAWN`. `canDecide` in the response tells the caller
whether they may decide now.

### Problems

| Method | Path | operationId | Who |
|---|---|---|---|
| POST | `/api/problems` | `createProblem` | agents and above → 201 |
| PUT | `/api/problems/{id}/analysis` | `updateProblemAnalysis` | the problem's team, admins → 204 |
| PUT | `/api/incidents/{id}/problem` | `linkIncidentToProblem` | staff handling the incident or the problem → 204 |
| DELETE | `/api/incidents/{id}/problem?version=` | `unlinkIncidentFromProblem` | as for linking → 204 |

A problem is a ticket of type `PROBLEM` (`PRB-000001`): the cause behind one or more incidents.
Support staff raise it, optionally from the incident that prompted it (`incidentId`), which is
then linked. It uses incident categories and is routed the same way, and it has no SLA clocks.
Every agent can see every problem, and requesters see none; only its team changes it.

Lifecycle: `NEW → ASSIGNED → INVESTIGATING → KNOWN_ERROR → RESOLVED → CLOSED`, through the usual
transition endpoint. `KNOWN_ERROR` needs the analysis's root cause and workaround saved first;
`RESOLVED` needs the root cause and the move's `notes` (the permanent fix, requirement
`FIX_NOTES`). `Reopen` goes back to `INVESTIGATING` and keeps the analysis. Resolving posts an
internal note on each linked incident that is still open.

The analysis is replaced whole (`rootCause`, `workaround`, both optional, plus the problem's
`version`). Linking takes the problem's id and the *incident's* `version`; only an open problem
takes incidents, but any incident may be linked. Neither call returns a body: reload the ticket.
On a problem, `problem` in the ticket holds the analysis, the linked incidents and the changes
raised to fix it; on an incident seen by staff, `incident.problem` names its problem with the
workaround.

### Changes

| Method | Path | operationId | Who |
|---|---|---|---|
| POST | `/api/changes` | `createChange` | agents and above → 201 |
| PUT | `/api/changes/{id}/plan` | `updateChangePlan` | the change's team, admins → 204 |
| PUT | `/api/changes/{id}/outcome` | `recordChangeOutcome` | the change's team, admins → 204 |
| GET | `/api/changes/schedule` | `getChangeSchedule` | agents and above |

A change is a ticket of type `CHANGE` (`CHG-000001`): a planned change to a live service. Support
staff raise it with a `changeType` (`STANDARD`, `NORMAL`, `EMERGENCY`), a `risk` (`LOW`, `MEDIUM`,
`HIGH`) and, now or later, the plan: `implementationPlan`, `backoutPlan`, `plannedStart` and
`plannedEnd`. `problemId` optionally names the open problem it fixes. Like a problem it uses
incident categories, has no SLA clocks, is visible to every agent and to no requester, and only its
team changes it.

Lifecycle, through the usual transition endpoint: `NEW → ASSIGNED`, then either `Submit for
approval` (`APPROVAL_PENDING`) or, for a standard change only, `Schedule standard change`
(`APPROVED`); the transitions list offers whichever applies. Both need the whole plan, and the
window must not have started yet unless it is an emergency. Submitting opens an approval in the
approvals inbox (see Approvals), decided by a team lead of the change's team or an admin, never by
whoever raised it: approving moves it to `APPROVED`, rejecting to `REJECTED`, which ends it. Then
`IMPLEMENTING → REVIEW → CLOSED`; closing needs the outcome. A change cancelled while waiting has its
approval withdrawn.

The plan is replaced whole (same fields plus the change's `version`) and only while `NEW` or
`ASSIGNED`. The outcome (`SUCCESSFUL`, `SUCCESSFUL_WITH_ISSUES`, `FAILED`, `BACKED_OUT`, with
required `notes`) is recorded while `IMPLEMENTING` or in `REVIEW`. Neither call returns a body:
reload the ticket. On a change, `change` in the ticket holds the plan, the review, the problem it
fixes and its `clashes`: other changes awaiting approval, approved or under way whose windows overlap
its own. The schedule lists those same changes whose window hasn't ended, soonest first.

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
| GET | `/api/admin/sla-policies` | `listSlaPolicies` |
| PUT | `/api/admin/sla-policies/{id}` | `updateSlaPolicy` (needs `version`; applies to tickets raised afterwards) |
| GET / POST | `/api/admin/catalogue` | `listAllCatalogueItems` / `createCatalogueItem` (201) |
| PUT | `/api/admin/catalogue/{id}` | `updateCatalogueItem` (whole item incl. `active`; needs `version`) |

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
