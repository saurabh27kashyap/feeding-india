// k6 load test: the evening rush before the 20:00 cutoff, plus Ops dashboards polling.
//
// Real peak (Section 8 of PLAN.md): ~3,000 orders; worst case all edited in the last
// 15 minutes = ~3.3 req/s, plus ~5 req/s of dashboard polling. Under 10 req/s in total.
// RATE=10 tests that peak; RATE=100 tests 10x growth (20,000 centres).
//
//   docker compose --profile loadtest run --rm k6 run /scripts/peak.js
//   docker compose --profile loadtest run --rm -e RATE=100 k6 run /scripts/peak.js
//
// Run it with the demo clock before 20:00 (the "19:55" preset), or edits will get 409 CUTOFF_PASSED.

import http from 'k6/http'
import { check } from 'k6'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const RATE = Number(__ENV.RATE || 10)
const CENTRES = 2000

export const options = {
  // Keep the session cookie between iterations, like a real phone that stays logged in.
  noCookiesReset: true,
  scenarios: {
    centres_editing: {
      executor: 'constant-arrival-rate', exec: 'centre', rate: Math.ceil(RATE * 0.6), timeUnit: '1s',
      duration: __ENV.DURATION || '2m', preAllocatedVUs: 50, maxVUs: 400,
    },
    ops_polling: {
      executor: 'constant-arrival-rate', exec: 'ops', rate: Math.max(1, Math.floor(RATE * 0.4)), timeUnit: '1s',
      duration: __ENV.DURATION || '2m', preAllocatedVUs: 10, maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:edit}': [RATE > 50 ? 'p(95)<500' : 'p(95)<300'],
    'http_req_duration{kind:read}': [RATE > 50 ? 'p(95)<500' : 'p(95)<300'],
  },
}

// One logged-in session per VU, reused across iterations (like a real phone).
const sessions = {}

function login(phone) {
  const jar = http.cookieJar()
  http.get(`${BASE}/api/auth/me`)
  const token = jar.cookiesForURL(BASE)['XSRF-TOKEN'][0]
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ phone, password: 'demo1234' }), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
  })
  check(res, { 'login ok': (r) => r.status === 200 })
  return token
}

export function centre() {
  const id = (__VU % CENTRES) + 1
  if (!sessions.token) sessions.token = login(String(9000000000 + id))
  const home = http.get(`${BASE}/api/centre/orders`, { tags: { kind: 'read' } })
  check(home, { 'home 200': (r) => r.status === 200 })
  const next = home.json('nextDay')
  const orders = (home.json('orders') || []).filter((o) => o.order.serviceDate === next && o.canEdit)
  if (!orders.length) return
  const o = orders[Math.floor(Math.random() * orders.length)].order
  const qty = Math.max(1, o.suggestedQty + Math.floor(Math.random() * 7) - 3)
  const res = http.put(`${BASE}/api/centre/orders/${o.id}`, JSON.stringify({ qty }), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': sessions.token },
    tags: { kind: 'edit' },
  })
  check(res, { 'edit 200': (r) => r.status === 200 })
}

export function ops() {
  if (!sessions.token) sessions.token = login('9999999999')
  const res = http.get(`${BASE}/api/ops/dashboard`, { tags: { kind: 'read' } })
  check(res, { 'dashboard 200': (r) => r.status === 200 })
}
