# MealLedger: Final Plan

A daily meal-operations system for a school and Anganwadi feeding program, modelled on Feeding India's published flow.

**One line:** every day, every centre gets exactly the meals its children need. Each delivery is confirmed, and every vendor bill matches what was delivered.

---

## 0. Ground rules

- **Real flow first.** Every step comes from Feeding India's own pages. Anything not public is marked `ASSUMPTION`.
- **Nothing unnecessary.** If a feature does not protect a meal, a rupee or a child, it is not in v1.
- **Boring tech.** One backend, one database, one frontend codebase. No Kafka, Redis, microservices or websockets. Section 8 shows why with numbers.

---

## 1. The real flow (what actually happens)

Sources: FY26 annual report, Daily Feeding Program page, Anganwadi Program page, and the blogs on tech, Nandurbar, Varanasi and Anganwadis.

| # | Step | Who | Source |
|---|---|---|---|
| 1 | Centre tells how many meals it needs tomorrow (extra, fewer, or closed = zero) | Centre (NGO partner) | Tech blog |
| 2 | Central kitchen cooks in the morning; each batch is checked before leaving | Kitchen (vendor partner) | Nandurbar, Varanasi, Anganwadi blogs |
| 3 | Food goes out route-wise to many centres | Kitchen | Anganwadi blog |
| 4 | Children check in by tablet face scan (FRAT, optional per partner) | Centre | DFP page, tech blog |
| 5 | Meal is served (Anganwadis: breakfast + lunch) | Centre | Annual report, Anganwadi blog |
| 6 | Centre gives feedback on quantity, quality, timeliness | Centre | Tech blog |
| 7 | Vendor raises an invoice against the tracked quantities | Kitchen | Tech blog |

**Not public, so assumed:**
- `ASSUMPTION` Cutoff for tomorrow's count is 20:00 the evening before, because the kitchen cooks early morning.
- `ASSUMPTION` Each route covers about 10 centres, and there are about 20 kitchens for about 2,000 centres. Nandurbar runs one central kitchen for 224 Anganwadis.
- `ASSUMPTION` Delivery should be confirmed by 12:00.
- `ASSUMPTION` Vendors are paid a fixed rate per meal.

---

## 2. What we build, and what we don't

**Build (v1):**
1. Tomorrow's order per centre per meal, with a suggested count from past attendance.
2. 20:00 lock, which produces a route-wise prep list for the kitchen.
3. Route dispatch and centre receipt confirmation.
4. Attendance count intake from FRAT (simulated).
5. Ops dashboard: unconfirmed deliveries, shortages, waste %, bill mismatches.
6. Vendor invoice with line-by-line reconciliation, approved by Ops.
7. Feedback with LLM tags, rolled up into a weekly vendor scorecard.

**Not building, and why:**

| Cut | Reason |
|---|---|
| Face recognition | FRAT is theirs. We consume its output (a count), not raw scans. |
| Offline mode in the app | Idempotent retries cover the v1 risk. Offline queue is v2. |
| SMS/WhatsApp alerts | The dashboard shows alerts. Messaging adds a vendor and cost with no new learning. |
| Growth tracking (height, weight) | A real feature, but a separate product. |
| Payments | We produce an approved payable amount. Paying it is the finance system's job. |
| Kafka / Redis / microservices / websockets | Load is a few requests per second (Section 8). Polling every 10 s is enough. |

---

## 3. Business decisions (made before any code)

| # | Decision | Why |
|---|---|---|
| B1 | An order = **centre + date + meal slot** (BREAKFAST / LUNCH) | Anganwadis get two meals. One row per real-world meal event. |
| B2 | Default tomorrow count = **suggested** count; the centre can change it until 20:00 | Saves didi effort and cuts waste. |
| B3 | Suggested count = average attendance of the **last 4 same weekdays**, rounded up | Simple and explainable. Monday ≠ Saturday. Rounding up means we never under-feed. |
| B4 | No attendance history (FRAT not used) → suggestion = last locked count | FRAT is optional for partners (real). |
| B5 | After 20:00 the count is **locked**. A centre can still report a **closure** after lock, with a reason. | Kitchen needs a final number, but closures happen (sudden holiday). Recorded as planned waste, not hidden. |
| B6 | Ops can override a locked count, always with a reason | Real operations need an escape hatch. Every override is audited. |
| B7 | Kitchen dispatches **per route**. Each centre confirms receipt **per order**. | Matches route-wise delivery (real). |
| B8 | At 12:00, dispatched-but-unconfirmed orders show as **UNCONFIRMED**, not "missing" | The system cannot tell "food didn't arrive" from "didi forgot to tap". Ops calls to find out. Honest labels. |
| B9 | Received count above 1.2 × locked count needs a second confirmation | Catches typos (700 instead of 70). |
| B10 | **Shortage** (received < attendance) is the highest-priority alert | A shortage means a child may not have eaten. |
| B11 | Waste % = (received − attendance) ÷ received, shown per centre per week | This is Feeding India's own stated goal: "every meal prepared is one that's truly needed". |
| B12 | Payable per line = min(billed, received) × rate. **Ops approves**; the system never pays automatically. | Money needs a human in the loop. The rule is simple enough to audit. |
| B13 | The LLM only tags feedback. It never touches counts or money. | LLMs can be wrong; counts and money must be exact. |

---

## 4. What can go wrong, and how we handle it

| # | Failure | Handling |
|---|---|---|
| F1 | Bad network, so didi taps "Received" twice | `Idempotency-Key` header. A repeated key returns the first response and creates no new row. |
| F2 | Centre edits at 19:59:59 while the lock job runs at 20:00 | Edit is a conditional update: `UPDATE ... WHERE status='REQUESTED'`. If it updates 0 rows, the edit is rejected with "cutoff passed". One winner, always. |
| F3 | Lock job runs twice (restart, or two instances) | The lock is itself a conditional update, so a second run changes nothing. **Jobs are idempotent instead of adding distributed locks.** |
| F4 | Status jumps backwards (RECEIVED → DISPATCHED) | A state machine allows forward moves only. Invalid moves return 409. |
| F5 | Duplicate order for the same centre/date/meal | `UNIQUE(centre_id, service_date, meal_slot)`. The DB is the last guard even if code has a bug. |
| F6 | Typo in a count | Bound check (B9) and non-negative constraints. |
| F7 | Vendor bills more than delivered | Line flagged, and payable capped at received (B12). |
| F8 | FRAT sends the same attendance twice, or late | Upsert keyed on (centre, date, meal). The latest value wins, and the change is logged. |
| F9 | LLM is down, slow, or returns junk | Feedback is saved first with tags = `UNTAGGED`. A nightly retry runs, and output is validated against the allowed tag list. |
| F10 | Timezone bugs around midnight and cutoff | Store UTC; compute cutoff and "tomorrow" in `Asia/Kolkata`. Time comes from an injected `Clock`. |
| F11 | Who changed this? | Every state change writes `order_event` (from, to, actor, reason, time). |
| F12 | Wrong user sees wrong data | Each user is tied to one centre or one vendor. Every query is scoped by that id on the server, never trusted from the client. |

---

## 5. Architecture

```
  Partner App (mobile-first)            Ops Console (desktop)
  ├─ Centre screens                     ├─ Dashboard
  └─ Kitchen screens                    ├─ Order timeline (audit)
            │                           └─ Invoice review
            └──────────────┬───────────────┘
                           │  HTTPS, same origin
              ┌────────────▼─────────────┐
              │  Spring Boot (modular     │   Scheduled jobs (IST):
              │  monolith)                │   20:00 lock orders
              │  ├ order                  │   12:00 flag unconfirmed
              │  ├ delivery               │   18:00 reconcile invoices
              │  ├ attendance + forecast  │   02:00 tag feedback (LLM)
              │  ├ billing                │
              │  ├ feedback               │◄── FRAT simulator
              │  └ auth                   │    (machine API key)
              └──────┬──────────────┬─────┘
                     ▼              ▼
                 PostgreSQL      Groq LLM
```

**Two apps, one frontend codebase.**
- Partner App is used by the people outside the company: centres and kitchens. This matches the real "Feeding India App" for NGO and vendor partners.
- Ops Console is internal.
- They have different users and layouts, so they are separate apps. They share one React project and one build, and are served by Spring Boot from the same origin. That means one deploy, no CORS, and simple session cookies.

---

## 6. Data model

```
vendor        (id, name, rate_per_meal_paise)
route         (id, vendor_id, name)
centre        (id, route_id, name, type[SCHOOL|ANGANWADI], meal_slots)
app_user      (id, phone, role[CENTRE|KITCHEN|OPS], centre_id?, vendor_id?)

meal_order    (id, centre_id, service_date, meal_slot,
               suggested, requested, locked_qty, received_qty,
               status, version)
               UNIQUE(centre_id, service_date, meal_slot)
order_event   (id, order_id, from_status, to_status, actor_id, reason, at)

attendance    (centre_id, service_date, meal_slot, present, source[FRAT|MANUAL])
               PK(centre_id, service_date, meal_slot)

invoice       (id, vendor_id, service_date, status[SUBMITTED|APPROVED])
invoice_line  (invoice_id, order_id, billed_qty, payable_qty, flag)

feedback      (id, order_id, text, tags, tag_status[UNTAGGED|TAGGED])
idempotency   (key, user_id, response_hash, created_at)
```

**Order states:**
```
REQUESTED ─20:00─► LOCKED ─route dispatch─► DISPATCHED ─centre─► RECEIVED
    │                 │
    └──────┬──────────┘
           ▼
         CLOSED  (count = 0, reason required)
```
At 12:00, a DISPATCHED order shows as UNCONFIRMED. That is a computed view, not a new state.

Money is stored as integer paise. Counts are non-negative integers.

---

## 7. APIs

**Partner App: Centre**

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/centre/orders?date=` | Tomorrow's orders with suggested counts |
| PUT | `/api/centre/orders/{id}` | Change count or close (before cutoff) |
| POST | `/api/centre/orders/{id}/close` | Post-cutoff closure with reason |
| POST | `/api/centre/orders/{id}/receive` | Confirm receipt (idempotent) |
| POST | `/api/centre/orders/{id}/feedback` | Feedback text |

**Partner App: Kitchen**

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/kitchen/prep-list?date=` | Locked counts grouped by route |
| POST | `/api/kitchen/routes/{id}/dispatch` | Mark route dispatched (idempotent) |
| POST | `/api/kitchen/invoices` | Submit invoice for a date |

**Integration**

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/integrations/frat/attendance` | Attendance counts (API-key auth, upsert) |

**Ops Console**

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/ops/dashboard?date=` | Counts for unconfirmed, shortages, waste, bill flags |
| GET | `/api/ops/orders/{id}/timeline` | Audit trail |
| PUT | `/api/ops/orders/{id}/override` | Override a locked count with reason |
| GET | `/api/ops/invoices?status=` | Invoices to review |
| POST | `/api/ops/invoices/{id}/approve` | Approve payable |
| GET | `/api/ops/vendors/{id}/scorecard?week=` | Feedback tags + on-time rate |

**Demo only** (disabled in prod profile)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/demo/clock` | Move the app clock (e.g. to 20:00, 12:00) |
| POST | `/api/demo/seed` | Load 2,000 centres + 4 weeks of attendance |

---

## 8. Load: numbers and test plan

Base: **~2,000 centres** (1,150+ schools + 900+ Anganwadis, FY26 report).

| Traffic | Per day | Peak | How |
|---|---|---|---|
| Orders | ~3,000 | — | 1,150 × 1 meal + 900 × 2 meals |
| Count edits before cutoff | ≤ 3,000 | **~3.3/s** | Worst case: all edited in the last 15 min (3,000 ÷ 900 s) |
| Receipt confirmations | ~3,000 | **~1/s** | Per meal window, roughly 1,500 in 30 min |
| FRAT counts | ~3,000 | — | One count per centre per meal, not per child |
| Ops dashboard polling | — | **~5/s** | 50 ops users × 1 poll every 10 s |

**Correction from earlier:** we ingest FRAT's *count per centre per meal*, not 1.7 lakh raw scans. So the hot path is about 3,000 rows a day, and no partitioning is needed. (If FRAT ever pushed raw scans, 1.7L in an hour is about 47/s. That is still fine for one Postgres instance; we would just add a daily summary table.)

Peak total load is **under 10 req/s**. A single Spring Boot instance and Postgres handle this with a wide margin, which is why the architecture stays boring.

**Load tests (k6):**

| Test | Setup | Pass criteria |
|---|---|---|
| L1 Normal peak | 10 req/s mixed traffic, 5 min | p95 < 300 ms, 0 errors |
| L2 10x growth | 100 req/s (20,000 centres), 5 min | p95 < 500 ms, 0 errors |
| L3 Cutoff race | 500 count edits fired across the 20:00 lock | No order changed after its LOCKED event (checked by SQL on `order_event`) |
| L4 Double tap | Same `Idempotency-Key` sent 20× in parallel | Exactly 1 RECEIVED event per order |
| L5 Lock job at 10x | 30,000 orders | Completes in < 5 s; rerun changes 0 rows |

---

## 9. Design principles in this project

1. **Model the real world first.** Order = centre + date + meal, dispatch by route, unconfirmed ≠ missing.
2. **Make the database the last guard.** Unique keys, non-negative checks, integer paise.
3. **Design for retries.** Idempotent APIs and idempotent jobs, instead of distributed locks.
4. **Forward-only state + audit trail.** Every change is explainable later.
5. **Size it with numbers, then choose the simplest thing.** Under 10 req/s means a monolith.
6. **AI only where text needs understanding, with a fallback and an eval.**
7. **Time is an input.** An injected `Clock` makes cutoffs testable and the demo possible.

---

## 10. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Backend | Spring Boot 4, Java 21 | Strong fundamentals; the stack he is learning |
| DB | PostgreSQL + Flyway | Constraints, transactions, versioned schema |
| Auth | Spring Security session login, seeded users per role | Simplest correct option with a same-origin SPA. Prod would use phone OTP. |
| Frontend | React + Vite + Tailwind, two route trees (`/partner`, `/ops`) | One build, served by Spring Boot |
| Live updates | Poll every 10 s | Enough at this scale |
| LLM | Groq (free key), fixed tag list | Fast, free, with a fallback |
| Tests | JUnit + Testcontainers (Postgres) | Tests run against the real DB |
| Load | k6 | Single binary, scripted scenarios |
| Deploy | Render (app) + Neon (Postgres) | Free, one service |

---

## 11. Demo (5 minutes)

Use two browser windows side by side: the Partner App at phone width and the Ops Console at desktop width.

| # | Input | Output | Shows |
|---|---|---|---|
| 1 | Seed | 2,000 centres, 200 routes, 4 weeks of attendance | Real scale |
| 2 | Centre opens tomorrow | Suggested 59 (default 70), with the "why" (last 4 Mondays) | Forecast → less waste |
| 3 | Centre B taps "Closed tomorrow" | Order = 0 | Requirement change |
| 4 | Clock → 20:00; Centre A edits | "Cutoff passed" | Lock + race safety |
| 5 | Kitchen prep list | Route 7: 6 centres, 412 meals; closed centre excluded | Kitchen's real job |
| 6 | Dispatch Route 7; 5 of 6 centres confirm; tap "Received" twice on one | One receipt; Ops shows 1 UNCONFIRMED | Idempotency + "without fail" |
| 7 | FRAT sends attendance 52 for a 70-meal centre | Waste 26% on dashboard | Waste visibility |
| 8 | FRAT sends 75 for a 70-meal centre | 🔴 Shortage alert | Child-first priority |
| 9 | Vendor bills 412, received 350 | Line flagged, payable capped, Ops approves | Money correctness |
| 10 | Feedback "dal patli thi, late aaya" | Tags [QUALITY, LATE]; vendor scorecard | AI, used narrowly |
| 11 | Order timeline | Every change with actor and time | Audit |

---

## 12. Build order

| Day | Build | Demo steps working |
|---|---|---|
| 1 | Project setup, Flyway schema, seed, order APIs, cutoff lock, Clock | 1, 3, 4 |
| 2 | Forecast, prep list, route dispatch, receive (idempotent) | 2, 5, 6 |
| 3 | FRAT intake, dashboard API (unconfirmed, shortage, waste) | 7, 8 |
| 4 | Invoices + reconciliation + approve; audit timeline | 9, 11 |
| 5 | Feedback + LLM tagging + 30-case eval | 10 |
| 6 | Frontend: Partner App + Ops Console | Full demo |
| 7 | Tests, k6 load tests, deploy, README, 60 s video | Ship |

---

## 13. Interview talking points

1. **"I built your flow, not a generic app."** Every step comes from your blog and annual report, and the assumptions are labelled.
2. **Failure handling.** Double taps, cutoff race, job reruns, LLM outages.
3. **Data correctness.** DB constraints, integer paise, forward-only states, audit.
4. **Numbers before architecture.** Under 10 req/s means a monolith; load tests prove it at 10x.
5. **AI with judgment.** The LLM only tags text, is measured on 30 labelled cases, and falls back safely.
