import { useEffect, useState } from 'react'
import { ApiError, api, dateTimeIST, day, num, post, rupees, timeIST } from '../../api'
import { useAuth } from '../../auth'
import { useData } from '../../hooks'
import { Tour, useTour } from '../../tour'
import type { Change, Money, PrepList, Reviews, RouteBatch } from '../../types'
import { BillHistory, BillPanel } from './KitchenBills'
import { Button, Card, ErrorNote, Explainer, Flag, Logo, SectionTitle, Slot, Spinner, StatusBadge } from '../../ui'

type Tab = 'plan' | 'vans' | 'reviews' | 'money'
const TABS: { key: Tab; label: string; icon: string }[] = [
  { key: 'plan', label: 'Cooking plan', icon: '📋' },
  { key: 'vans', label: 'Vans', icon: '🚚' },
  { key: 'reviews', label: 'Reviews', icon: '⭐' },
  { key: 'money', label: 'Money & bill', icon: '₹' },
]

/** Partner App, kitchen manager: what to cook, what changed, where the vans are, what centres said, what gets paid. */
export default function KitchenHome() {
  const { me, logout } = useAuth()
  const days = useData(() => api<{ today: string; nextDay: string }>('/api/kitchen/days'), [])
  const [date, setDate] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('plan')

  useEffect(() => {
    if (days.data && (!date || (date !== days.data.today && date !== days.data.nextDay))) setDate(days.data.today)
  }, [days.data, date])

  if (!days.data || !date) return days.error ? <div className="p-6"><ErrorNote message={days.error} /></div> : <Spinner />

  return (
    <div className="mx-auto max-w-5xl px-4 pt-5">
      <header className="mb-5 flex items-center justify-between">
        <Logo small />
        <Button variant="ghost" onClick={logout}>Sign out</Button>
      </header>

      <div className="mb-5 rounded-3xl bg-gradient-to-br from-orange-500 to-orange-700 p-5 text-white shadow-lg">
        <div className="text-xs font-medium uppercase tracking-wider text-orange-100">Partner App · 👨‍🍳 Ramesh’s kitchen</div>
        <h1 className="mt-1 text-xl font-bold">{me?.vendorName}</h1>
        <div className="text-xs text-orange-100">10 van routes · 100 schools & Anganwadis</div>
        <div className="mt-4 flex flex-wrap gap-2">
          {[days.data.today, days.data.nextDay].map((d) => (
            <button key={d} onClick={() => setDate(d)}
                    className={`rounded-xl px-3 py-1.5 text-sm font-medium transition ${d === date ? 'bg-white text-orange-800 shadow' : 'bg-white/15 text-white hover:bg-white/25'}`}>
              {d === days.data!.today ? 'Today' : 'Next service day'} · {day(d)}
            </button>
          ))}
        </div>
      </div>

      <div className="mb-5 flex flex-wrap gap-1 rounded-2xl border border-stone-200 bg-white p-1 shadow-sm">
        {TABS.map((t) => (
          <Tour key={t.key} id={`kitchen-tab-${t.key}`} tip={`Open ${t.label}`} below className="flex-1">
            <button onClick={() => setTab(t.key)}
                    className={`w-full rounded-xl px-3 py-2 text-sm font-medium transition ${tab === t.key ? 'bg-stone-900 text-white' : 'text-stone-600 hover:bg-stone-50'}`}>
              {t.icon} {t.label}
            </button>
          </Tour>
        ))}
      </div>

      {tab === 'plan' && <Plan date={date} />}
      {tab === 'vans' && <Vans date={date} />}
      {tab === 'reviews' && <ReviewsTab date={date} />}
      {tab === 'money' && <MoneyTab date={date} />}
    </div>
  )
}

// ---------- 1. cooking plan + what changed ----------

function Plan({ date }: { date: string }) {
  const prep = useData(() => api<PrepList>(`/api/kitchen/prep-list?date=${date}`), [date], 5000)
  const changes = useData(() => api<Change[]>(`/api/kitchen/changes?date=${date}`), [date], 5000)
  if (!prep.data) return prep.error ? <ErrorNote message={prep.error} /> : <Spinner />
  const p = prep.data

  return (
    <div className="space-y-5">
      <Explainer title="What to cook">
        Centres can change their count until <b>{timeIST(p.cutoffAt)}</b> the evening before. After that the numbers are final and
        this is exactly what you cook. Every change a centre makes shows up below, with who and when.
      </Explainer>

      <div className="grid gap-3 sm:grid-cols-4">
        <Tour id="final-counts" tip="Counts are final after 8 PM">
          <Card className="!p-4 h-full">
            <div className="text-xs text-stone-500">Counts</div>
            <div className={`mt-1 inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-sm font-semibold ${p.finalCounts ? 'bg-brand-50 text-brand-700' : 'bg-amber-50 text-amber-800'}`}>
              {p.finalCounts ? '🔒 Final' : '⏳ Can still change'}
            </div>
            <div className="mt-1 text-[11px] text-stone-500">{p.finalCounts ? `Locked at ${timeIST(p.cutoffAt)}` : `Lock at ${timeIST(p.cutoffAt)}`}</div>
          </Card>
        </Tour>
        <Stat label="☀ Breakfasts to cook" value={num(p.breakfastMeals)} />
        <Stat label="◐ Lunches to cook" value={num(p.lunchMeals)} />
        <Stat label="Total meals" value={num(p.totalMeals)} sub={`${p.routes.length} van runs`} />
      </div>

      <div className="grid gap-5 lg:grid-cols-[1fr_1.1fr]">
        <Card>
          <SectionTitle title="Meals per van run" hint="Cook and pack by route. One van per route." />
          <div className="divide-y divide-stone-100 rounded-xl border border-stone-100">
            {p.routes.map((r) => (
              <div key={`${r.routeId}-${r.mealSlot}`} className="flex items-center justify-between px-3 py-2 text-sm">
                <span className="flex items-center gap-2"><span className="font-medium">{r.routeName}</span><Slot slot={r.mealSlot} /></span>
                <span className="text-stone-500">{r.stops.length - r.closed} centres{r.closed ? ` · ${r.closed} closed` : ''}</span>
                <span className="w-16 text-right font-bold tabular-nums">{r.mealsToCook}</span>
              </div>
            ))}
          </div>
        </Card>

        <Card>
          <SectionTitle title={`What changed (${changes.data?.length ?? 0})`}
                        hint="Every change centres made to your orders for this day, newest first. Comes from the same audit log Ops uses." />
          {!changes.data?.length ? (
            <p className="rounded-xl bg-stone-50 px-3 py-4 text-sm text-stone-500">No changes: every centre accepted the suggested count.</p>
          ) : (
            <ol className="max-h-[26rem] space-y-2 overflow-auto">
              {changes.data.map((c) => (
                <li key={c.eventId} className="flex items-start gap-3 rounded-xl border border-stone-100 p-2.5">
                  <span className="mt-0.5 text-lg">{c.kind === 'CLOSED' ? '⛔' : c.kind === 'OPS_OVERRIDE' ? '🛠️' : c.kind === 'REOPENED' ? '↩️' : '✏️'}</span>
                  <div className="flex-1 text-sm">
                    <div className="flex flex-wrap items-center gap-2">
                      <b>{c.centreName}</b> <Slot slot={c.mealSlot} />
                    </div>
                    <div className="text-stone-700">
                      {c.kind === 'CLOSED' ? <>Closed: “{c.reason}”</>
                        : <>{c.beforeQty} → <b>{c.afterQty}</b> meals{c.kind === 'OPS_OVERRIDE' && c.reason ? ` (${c.reason})` : ''}{c.kind === 'REOPENED' ? ' (reopened)' : ''}</>}
                    </div>
                    <div className="text-[11px] text-stone-500">{c.byWhom} · {dateTimeIST(c.at)}</div>
                  </div>
                </li>
              ))}
            </ol>
          )}
        </Card>
      </div>
    </div>
  )
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <Card className="!p-4">
      <div className="text-xs text-stone-500">{label}</div>
      <div className="text-3xl font-bold tabular-nums">{value}</div>
      {sub && <div className="text-[11px] text-stone-500">{sub}</div>}
    </Card>
  )
}

// ---------- 2. vans: van sheet + live stop status ----------

function Vans({ date }: { date: string }) {
  const { data, error, reload } = useData(() => api<PrepList>(`/api/kitchen/prep-list?date=${date}`), [date], 5000)
  if (!data) return error ? <ErrorNote message={error} /> : <Spinner />

  return (
    <div className="space-y-4">
      <Explainer title="Vans and stops">
        Breakfast vans must leave by <b>6:30 AM</b>, lunch vans by <b>11:00 AM</b>. Tap “Mark dispatched” when a van leaves.
        Each stop turns ✅ when the centre taps “Food received”. Late vans show on the Ops dashboard too.
      </Explainer>
      <div className="grid gap-3 sm:grid-cols-3">
        <Stat label="Vans left" value={`${data.vansLeft}/${data.vansTotal}`} />
        <Stat label="Late or overdue" value={num(data.vansLate)} sub="left more than 10 min after the deadline" />
        <Stat label="Meals on the road / confirmed" value={`${num(data.routes.reduce((s, r) => s + r.dispatched, 0))} / ${num(data.routes.reduce((s, r) => s + r.received, 0))}`} sub="orders" />
      </div>
      {data.routes.map((r) => <VanCard key={`${r.routeId}-${r.mealSlot}`} route={r} date={date} onChange={reload} />)}
    </div>
  )
}

function VanBadge({ r }: { r: RouteBatch }) {
  switch (r.vanStatus) {
    case 'ON_TIME': return <Flag level="good">Left {timeIST(r.dispatchedAt!)} · on time</Flag>
    case 'LATE': return <Flag level="serious">Left {timeIST(r.dispatchedAt!)} · {r.minutesLate} min late</Flag>
    case 'DUE': return <Flag level="critical">Not left · {r.minutesLate} min overdue</Flag>
    default: return <span className="text-xs text-stone-500">Leave by {timeIST(r.dispatchBy)}</span>
  }
}

function VanCard({ route: r, date, onChange }: { route: RouteBatch; date: string; onChange: () => void }) {
  const isFeatured = r.routeName === 'Route 001'
  const [open, setOpen] = useState(isFeatured)
  const [error, setError] = useState<string | null>(null)
  const { narrate } = useTour()
  const active = r.stops.length - r.closed
  const isDone = r.locked === 0 && r.dispatched + r.received > 0

  const dispatch = async () => {
    setError(null)
    try {
      const res = await post<{ dispatched: number }>(`/api/kitchen/routes/${r.routeId}/dispatch`, { date, mealSlot: r.mealSlot })
      narrate(res.dispatched > 0
        ? `The van for ${r.routeName} left with ${r.mealsToCook} meals for ${active} centres. Their phones now say “On the way”, and Saurabh’s dashboard moves them from Locked to On the way.`
        : `${r.routeName} was already dispatched. Tapping again changed nothing.`)
      onChange()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <Card className="!p-4">
      <div className="flex flex-wrap items-center gap-4">
        <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-orange-50 text-lg">🚚</div>
        <div className="min-w-36">
          <div className="font-semibold">{r.routeName}</div>
          <Slot slot={r.mealSlot} />
        </div>
        <div>
          <div className="text-2xl font-bold tabular-nums">{r.mealsToCook}</div>
          <div className="text-[11px] text-stone-500">meals · {active} stops</div>
        </div>
        <div className="min-w-40 flex-1 space-y-1">
          <VanBadge r={r} />
          <div className="flex h-1.5 overflow-hidden rounded-full bg-stone-100">
            <div className="h-full bg-brand-500" style={{ width: `${active ? (r.received / active) * 100 : 0}%` }} />
            <div className="h-full bg-violet-400" style={{ width: `${active ? (r.dispatched / active) * 100 : 0}%` }} />
          </div>
          <div className="text-[11px] text-stone-500">{r.received}/{active} stops confirmed food received</div>
        </div>
        <Button variant="ghost" onClick={() => setOpen(!open)}>{open ? 'Hide stops' : 'Van sheet'}</Button>
        <Tour id={isFeatured && r.mealSlot === 'BREAKFAST' ? 'dispatch' : `dispatch-${r.routeId}-${r.mealSlot}`} tip="Tap when the van leaves">
          <Button variant={isDone ? 'secondary' : 'brand'} onClick={dispatch} disabled={!r.canDispatch}>
            {isDone ? '✓ Dispatched' : 'Mark dispatched'}
          </Button>
        </Tour>
      </div>
      {!r.canDispatch && !isDone && (
        <p className="mt-2 text-[11px] text-stone-500">{r.open > 0 ? 'Counts are not final yet (they lock at 8 PM the evening before).' : 'Dispatch opens on the service day.'}</p>
      )}
      <ErrorNote message={error} />
      {open && (
        <div className="mt-3 overflow-auto rounded-xl border border-stone-100">
          <table className="w-full text-sm">
            <thead className="bg-stone-50 text-left text-xs text-stone-500">
              <tr><th className="px-3 py-2">Stop</th><th>Centre</th><th>Hand over to</th><th className="text-right">Meals</th><th className="px-3">Status</th></tr>
            </thead>
            <tbody>
              {r.stops.map((st) => (
                <tr key={st.orderId} className="border-t border-stone-100">
                  <td className="px-3 py-2 font-mono text-stone-500">{st.stop}</td>
                  <td className="font-medium">{st.centreName}</td>
                  <td className="text-xs text-stone-600">{st.workerName?.replace(/ \(.*\)$/, '')}<br /><span className="font-mono">📞 {st.workerPhone}</span></td>
                  <td className="text-right font-bold tabular-nums">{st.status === 'CLOSED' ? '—' : st.lockedQty ?? st.requestedQty}</td>
                  <td className="px-3">
                    {st.status === 'RECEIVED' ? <Flag level="good">Received {st.receivedQty} · {timeIST(st.receivedAt!)}</Flag>
                      : st.status === 'CLOSED' ? <span className="text-xs text-stone-500">⛔ Closed: {st.closedReason}</span>
                      : st.status === 'DISPATCHED' ? <span className="text-xs text-violet-800">⏳ On the way · confirm by {timeIST(st.confirmBy)}</span>
                      : <StatusBadge status={st.status} />}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

// ---------- 3. reviews from centres, day by day ----------

const TAG_LABEL: Record<string, string> = {
  LATE: '⏰ Late', QUALITY: '🍲 Quality', QUANTITY: '⚖️ Quantity', HYGIENE: '🧼 Hygiene', TASTE: '😋 Taste', NO_ISSUE: '👍 Praise', OTHER: '• Other',
}

function ReviewsTab({ date }: { date: string }) {
  const { data, error } = useData(() => api<Reviews>(`/api/kitchen/reviews?date=${date}`), [date], 30000)
  if (!data) return error ? <ErrorNote message={error} /> : <Spinner />

  return (
    <div className="space-y-5">
      <Explainer title="What centres said about your food">
        After every meal, centre workers like Sunita tap three answers (enough food? good quality? on time?) and can write a note in any language.
        AI turns each note into simple labels. It never changes counts or money.
      </Explainer>

      <Card>
        <SectionTitle title="Last 7 days" hint="Share of reviews that said yes. Below 90% needs attention." />
        {!data.days.length ? <p className="text-sm text-stone-500">No reviews yet.</p> : (
          <div className="overflow-auto rounded-xl border border-stone-100">
            <table className="w-full text-sm">
              <thead className="bg-stone-50 text-left text-xs text-stone-500">
                <tr><th className="px-3 py-2">Day</th><th className="text-right">Reviews</th><th className="text-right">On time</th>
                  <th className="text-right">Good quality</th><th className="text-right">Enough food</th><th className="px-3 text-right">Notes</th></tr>
              </thead>
              <tbody>
                {data.days.map((d) => (
                  <tr key={d.serviceDate} className="border-t border-stone-100 tabular-nums">
                    <td className="px-3 py-2 font-medium">{day(d.serviceDate)}</td>
                    <td className="text-right">{d.reviews}</td>
                    <Pct v={d.onTimePercent} /><Pct v={d.qualityOkPercent} /><Pct v={d.quantityOkPercent} />
                    <td className="px-3 text-right">{d.notes}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card>
        <SectionTitle title={`Notes from centres (${data.notes.length})`} hint="Newest first, with the labels AI gave each note." />
        <ol className="space-y-2">
          {data.notes.map((n) => (
            <li key={n.feedbackId} className="rounded-xl border border-stone-100 p-3">
              <div className="flex flex-wrap items-center gap-2 text-xs text-stone-500">
                <span className="font-semibold text-stone-800">{n.centreName}</span>
                <Slot slot={n.mealSlot} />
                <span>{day(n.serviceDate)}</span>
                <span className="flex-1" />
                <span>{n.quantityOk ? '👍' : '👎'} food · {n.qualityOk ? '👍' : '👎'} quality · {n.onTime ? '👍' : '👎'} time</span>
              </div>
              <p className="mt-1.5 text-sm text-stone-800">“{n.note}”</p>
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                {n.tagStatus === 'TAGGED' && n.tags
                  ? n.tags.split(',').map((t) => <span key={t} className="rounded-full bg-stone-100 px-2 py-0.5 text-[11px] font-medium text-stone-700">{TAG_LABEL[t] ?? t}</span>)
                  : <span className="text-[11px] text-stone-400">🤖 {n.tagStatus === 'FAILED' ? 'AI could not label this note' : 'AI labelling pending'}</span>}
              </div>
            </li>
          ))}
        </ol>
      </Card>
    </div>
  )
}

function Pct({ v }: { v: number }) {
  return <td className={`text-right ${v < 90 ? 'font-semibold text-red-700' : ''}`}>{v < 90 ? '▼ ' : ''}{v}%</td>
}

// ---------- 4. money preview + bill ----------

function MoneyTab({ date }: { date: string }) {
  const money = useData(() => api<Money>(`/api/kitchen/money?date=${date}`), [date], 5000)
  if (!money.data) return money.error ? <ErrorNote message={money.error} /> : <Spinner />
  const m = money.data

  return (
    <div className="space-y-5">
      <Explainer title="What you will be paid">
        You are paid for meals the centres <b>confirmed they received</b>, at {rupees(m.ratePaise)} per meal. This preview uses the same rule
        as the Ops team’s approval, so the numbers will match. Call the centres below to get their confirmations in.
      </Explainer>

      <div className="grid gap-3 sm:grid-cols-4">
        <Stat label="Meals cooked" value={num(m.mealsCooked)} sub={`would be ${rupees(m.expectedPaise)}`} />
        <Card className="!p-4 bg-brand-50/60">
          <div className="text-xs text-brand-800">Confirmed: will be paid</div>
          <div className="text-3xl font-bold tabular-nums text-brand-900">{rupees(m.confirmedPaise)}</div>
          <div className="text-[11px] text-brand-800">{num(m.mealsConfirmed)} meals</div>
        </Card>
        <Card className="!p-4 bg-amber-50/60">
          <div className="text-xs text-amber-800">Waiting for centres</div>
          <div className="text-3xl font-bold tabular-nums text-amber-900">{rupees(m.pendingPaise)}</div>
          <div className="text-[11px] text-amber-800">{num(m.mealsPending)} meals not confirmed yet</div>
        </Card>
        <Stat label="Not payable" value={num(m.mealsNotPayable)} sub="closed centres or short deliveries" />
      </div>

      {m.pending.length > 0 && (
        <Card>
          <SectionTitle title={`Centres to call (${m.pending.length})`} hint="Food was sent but these centres have not confirmed it arrived." />
          <div className="max-h-64 overflow-auto rounded-xl border border-stone-100">
            <table className="w-full text-sm">
              <tbody>
                {m.pending.map((x) => (
                  <tr key={x.orderId} className="border-t border-stone-100 first:border-t-0">
                    <td className="px-3 py-2 font-medium">{x.centreName}</td>
                    <td className="text-stone-600">{x.routeName}</td>
                    <td><Slot slot={x.mealSlot} /></td>
                    <td className="text-right tabular-nums">{x.lockedQty} meals</td>
                    <td className="px-3 text-right font-mono text-xs">📞 {x.workerPhone}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <BillPanel date={date} onChanged={money.reload} />
      <BillHistory />
    </div>
  )
}
