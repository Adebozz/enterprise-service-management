// k6 load test for the service desk, run against the Docker Compose stack (see run.sh and
// docs/performance.md). Three groups of virtual users act at once:
//   agents     - work their queue: team view, a search, open a ticket with its conversation and SLA
//   requesters - raise an incident, check their tickets, add a comment
//   leads      - open the 30-day report now and then
// Each request is tagged with a name, so the summary has latency per endpoint.
import http from 'k6/http'
import { check, sleep } from 'k6'

const BASE = __ENV.BASE_URL || 'http://web:8080'
const PASSWORD = __ENV.ESM_DEMO_PASSWORD
const DURATION = __ENV.DURATION || '3m'

export const options = {
  scenarios: {
    agents: { executor: 'constant-vus', vus: Number(__ENV.AGENTS || 20), duration: DURATION, exec: 'agent' },
    requesters: { executor: 'constant-vus', vus: Number(__ENV.REQUESTERS || 10), duration: DURATION, exec: 'requester' },
    leads: { executor: 'constant-vus', vus: 2, duration: DURATION, exec: 'lead' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:queue}': ['p(95)<500'],
    'http_req_duration{name:ticket}': ['p(95)<300'],
    'http_req_duration{name:createIncident}': ['p(95)<500'],
    // Generous limits: listed so k6 reports each endpoint's latency separately.
    'http_req_duration{name:search}': ['p(95)<1000'],
    'http_req_duration{name:comments}': ['p(95)<1000'],
    'http_req_duration{name:sla}': ['p(95)<1000'],
    'http_req_duration{name:unreadCount}': ['p(95)<1000'],
    'http_req_duration{name:myTickets}': ['p(95)<1000'],
    'http_req_duration{name:addComment}': ['p(95)<1000'],
    'http_req_duration{name:report}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
}

function login(email) {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'login' },
  })
  check(res, { 'signed in': (r) => r.status === 200 })
  return res.json('accessToken')
}

// One sign-in per account for the whole run (access tokens last 15 minutes).
export function setup() {
  if (!PASSWORD) throw new Error('Set ESM_DEMO_PASSWORD')
  const tokens = {}
  for (const email of ['nina@demo.local', 'hugo@demo.local', 'iris@demo.local', 'lead@demo.local', 'rita@demo.local', 'sam@demo.local']) {
    tokens[email] = login(email)
  }
  const categories = http.get(`${BASE}/api/categories?type=INCIDENT`, auth(tokens['rita@demo.local'])).json()
  return { tokens, categoryId: categories[0].id }
}

function auth(token, name) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, tags: name ? { name } : {} }
}

const AGENTS = ['nina@demo.local', 'hugo@demo.local', 'iris@demo.local']
const WORDS = ['printer', 'vpn', 'laptop', 'email', 'wi-fi', 'monitor']

export function agent(data) {
  const token = data.tokens[AGENTS[__VU % AGENTS.length]]
  const queue = http.get(`${BASE}/api/tickets?view=TEAM&open=true&sort=slaDueAt,asc&size=20&page=${__ITER % 5}`, auth(token, 'queue'))
  check(queue, { 'queue 200': (r) => r.status === 200 })
  sleep(1)
  const search = http.get(`${BASE}/api/tickets?view=ALL&q=${WORDS[__ITER % WORDS.length]}&size=20`, auth(token, 'search'))
  check(search, { 'search 200': (r) => r.status === 200 })
  const rows = queue.status === 200 ? queue.json('content') : []
  if (rows.length > 0) {
    const id = rows[__ITER % rows.length].id
    check(http.get(`${BASE}/api/tickets/${id}`, auth(token, 'ticket')), { 'ticket 200': (r) => r.status === 200 })
    http.get(`${BASE}/api/tickets/${id}/comments`, auth(token, 'comments'))
    http.get(`${BASE}/api/tickets/${id}/sla`, auth(token, 'sla'))
  }
  http.get(`${BASE}/api/notifications/unread-count`, auth(token, 'unreadCount'))
  sleep(2)
}

export function requester(data) {
  const token = data.tokens[__VU % 2 === 0 ? 'rita@demo.local' : 'sam@demo.local']
  const created = http.post(
    `${BASE}/api/incidents`,
    JSON.stringify({ title: `Load test ${__VU}-${__ITER}`, description: 'Raised by k6.', categoryId: data.categoryId, impact: 'LOW', urgency: 'MEDIUM' }),
    auth(token, 'createIncident'),
  )
  check(created, { 'incident 201': (r) => r.status === 201 })
  sleep(1)
  http.get(`${BASE}/api/tickets?view=REQUESTED&size=20`, auth(token, 'myTickets'))
  if (created.status === 201) {
    const comment = http.post(
      `${BASE}/api/tickets/${created.json('id')}/comments`,
      JSON.stringify({ body: 'Any update?', visibility: 'PUBLIC' }),
      auth(token, 'addComment'),
    )
    check(comment, { 'comment 201': (r) => r.status === 201 })
  }
  sleep(3)
}

export function lead(data) {
  const report = http.get(`${BASE}/api/reports/service-desk`, auth(data.tokens['lead@demo.local'], 'report'))
  check(report, { 'report 200': (r) => r.status === 200 })
  sleep(5)
}
