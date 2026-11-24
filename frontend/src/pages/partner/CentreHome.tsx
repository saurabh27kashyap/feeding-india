import { useState, type ReactNode } from 'react'
import { ApiError, api, day, post, put, timeIST } from '../../api'
import { useAuth } from '../../auth'
import { useData } from '../../hooks'
import type { CentreHome as Home, CentreOrderLine } from '../../types'
import { Tour, useTour } from '../../tour'
import { Button, Card, ErrorNote, Lifecycle, Logo, Slot, Spinner, StatusBadge, Stepper } from '../../ui'

/** Partner App, centre worker. Phone-sized, one decision per card, plain language. */
export default function CentreHome() {
  const { logout } = useAuth()
  const { data, error, reload } = useData(() => api<Home>('/api/centre/orders'), [], 5000)

  if (!data) return error ? <div className="p-6"><ErrorNote message={error} /></div> : <Spinner />

  const nextOrders = data.orders.filter((o) => o.order.serviceDate === data.nextDay)
  const todayOrders = data.orders.filter((o) => o.order.serviceDate === data.today)

  return (
    <div className="mx-auto max-w-md px-4 pt-5">
      <header className="mb-5 flex items-center justify-between">
        <Logo small />
        <Button variant="ghost" onClick={logout}>Sign out</Button>
      </header>

      <div className="mb-5 overflow-hidden rounded-3xl bg-gradient-to-br from-sky-600 to-sky-800 p-5 text-white shadow-lg">
        <div className="text-xs font-medium uppercase tracking-wider text-sky-100">Partner App · 👩‍🏫 Sunita’s phone</div>
        <h1 className="mt-1 text-xl font-bold">{data.centre.name}</h1>
        <div className="mt-3 grid grid-cols-3 gap-2 text-center">
          <Pill label="Enrolled" value={String(data.centre.enrolled)} />
          <Pill label="Route" value={data.centre.routeName.replace('Route ', '#')} />
          <Pill label="District" value={data.centre.district} />
        </div>
        <div className="mt-3 truncate text-xs text-sky-100">Kitchen: {data.centre.vendorName}</div>
      </div>

      <Section title="Tomorrow" date={data.nextDay}
               hint="Check the suggested count. Change it, or tell us you are closed, before 20:00 tonight."
               empty="Tomorrow's meals will appear here.">
        {nextOrders.map((o) => <PlanCard key={o.order.id} {...o} onChange={reload} />)}
      </Section>

      <Section title="Today" date={data.today}
               hint="When the food arrives, confirm how much came. Then tell us how it was."
               empty="No meals today.">
        {todayOrders.map((o) => <TodayCard key={o.order.id} {...o} onChange={reload} />)}
      </Section>
    </div>
  )
}

function Pill({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl bg-white/15 px-2 py-1.5">
      <div className="text-[10px] uppercase tracking-wide text-sky-100">{label}</div>
      <div className="truncate text-sm font-semibold">{value}</div>
    </div>
  )
}

function Section({ title, date, hint, empty, children }: { title: string; date: string; hint: string; empty: string; children: ReactNode[] }) {
  return (
    <section className="mb-7">
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="text-lg font-bold text-stone-900">{title}</h2>
        <span className="text-sm text-stone-500">{day(date)}</span>
      </div>
      <p className="mb-3 text-xs leading-relaxed text-stone-500">{hint}</p>
      <div className="space-y-3">{children.length ? children : <Card className="text-sm text-stone-500">{empty}</Card>}</div>
    </section>
  )
}

type CardProps = { order: CentreOrderLine; canEdit: boolean; canReceive: boolean; onChange: () => void }

/** The four attendance numbers inside "Avg attendance of last 4 Tuesdays (28, 28, 28, 26) + 5% buffer". */
function history(basis: string): number[] {
  const m = basis.match(/\(([\d,\s]+)\)/)
  return m ? m[1].split(',').map((x) => Number(x.trim())) : []
}

function ForecastWhy({ order: o }: { order: CentreOrderLine }) {
  const past = history(o.suggestionBasis)
  if (!past.length) return <p className="text-xs text-stone-500">{o.suggestionBasis}</p>
  const max = Math.max(...past, o.suggestedQty)
  return (
    <div className="rounded-xl bg-stone-50 p-3">
      <div className="mb-2 text-xs text-stone-600">
        Why <b>{o.suggestedQty}</b>? Children who came on the last {past.length} same weekdays, plus a 5% safety buffer:
      </div>
      <div className="flex h-16 items-end gap-2">
        {past.map((v, i) => (
          <div key={i} className="flex flex-1 flex-col items-center gap-1">
            <span className="text-[10px] font-medium tabular-nums text-stone-600">{v}</span>
            <div className="w-full rounded-t bg-stone-300" style={{ height: `${(v / max) * 40}px` }} />
          </div>
        ))}
        <div className="flex flex-1 flex-col items-center gap-1">
          <span className="text-[10px] font-bold tabular-nums text-sky-800">{o.suggestedQty}</span>
          <div className="w-full rounded-t bg-sky-600" style={{ height: `${(o.suggestedQty / max) * 40}px` }} />
        </div>
      </div>
      <div className="mt-1 flex gap-2 text-[10px] text-stone-400">
        {past.map((_, i) => <span key={i} className="flex-1 text-center">{past.length - i}w ago</span>)}
        <span className="flex-1 text-center font-semibold text-sky-800">Suggested</span>
      </div>
    </div>
  )
}

function useAction(onChange: () => void) {
  const { narrate } = useTour()
  const [error, setError] = useState<string | null>(null)
  const act = async (fn: () => Promise<unknown>, says?: string) => {
    setError(null)
    try {
      await fn()
      if (says) narrate(says)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
      if (e instanceof ApiError && e.code === 'CUTOFF_PASSED') {
        narrate('The 8 PM lock is working. Once the kitchen has the final list, a centre cannot change its count, so the kitchen never cooks the wrong amount.', false)
      }
    } finally {
      onChange()
    }
  }
  return { error, setError, act, narrate }
}

const meal = (slot: string) => (slot === 'BREAKFAST' ? 'breakfasts' : 'lunches')

/** Tomorrow: confirm or change the suggested count, or report a closure, before cutoff. */
function PlanCard({ order: o, canEdit, onChange }: CardProps) {
  const [qty, setQty] = useState(o.status === 'CLOSED' ? o.suggestedQty : o.requestedQty)
  const { error, act } = useAction(onChange)

  const close = () => {
    const reason = window.prompt('Why will the centre be closed?', 'Holiday announced')
    if (reason) act(() => post(`/api/centre/orders/${o.id}/close`, { reason }),
      `Sunita said the centre is closed tomorrow ("${reason}"). The kitchen will cook zero ${meal(o.mealSlot)} for it: no food wasted.`)
  }

  return (
    <Card className="space-y-4">
      <div className="flex items-center justify-between">
        <Slot slot={o.mealSlot} />
        <StatusBadge status={o.status} />
      </div>
      <Lifecycle status={o.status} />

      {o.status === 'CLOSED' ? (
        <div className="rounded-xl bg-stone-50 p-3 text-sm text-stone-600">Closed: “{o.closedReason}”. No food will be cooked.</div>
      ) : canEdit ? (
        <>
          <div className="flex items-center gap-3">
            <Stepper value={qty} onChange={setQty} />
            <span className="text-sm text-stone-500">meals</span>
          </div>
          <ForecastWhy order={o} />
        </>
      ) : (
        <div className="rounded-xl bg-amber-50 p-3">
          <div className="text-3xl font-bold tabular-nums text-amber-900">{o.lockedQty ?? o.requestedQty}</div>
          <div className="text-xs text-amber-900/80">Final count. The kitchen is cooking exactly this.</div>
        </div>
      )}

      <div className={`flex items-center gap-2 rounded-xl px-3 py-2 text-xs ${canEdit ? 'bg-sky-50 text-sky-900' : 'bg-stone-50 text-stone-600'}`}>
        <span>{canEdit ? '⏳' : '🔒'}</span>
        {canEdit ? <>You can change this until <b>{timeIST(o.cutoffAt)}</b> tonight. After that the kitchen gets the final list.</>
          : <>Locked at {timeIST(o.cutoffAt)} the evening before.</>}
      </div>
      <ErrorNote message={error} />

      <div className="flex gap-2">
        {canEdit && (
          <Tour id={o.mealSlot === 'BREAKFAST' ? 'save-count' : 'save-count-lunch'} tip="Tap to save tomorrow's count" className="flex-1">
            <Button variant="brand" className="w-full" disabled={qty < 1}
                    onClick={() => act(() => put(`/api/centre/orders/${o.id}`, { qty }),
                      `Sunita asked for ${qty} ${meal(o.mealSlot)} for tomorrow (suggested was ${o.suggestedQty}). Ramesh's kitchen gets the final number when orders lock at ${timeIST(o.cutoffAt)}.`)}>
              {o.status === 'CLOSED' ? 'Reopen with this count' : qty === o.requestedQty ? '✓ Count is correct' : 'Save new count'}
            </Button>
          </Tour>
        )}
        {o.status !== 'CLOSED' && <Button variant="danger" onClick={close}>We are closed</Button>}
      </div>
    </Card>
  )
}

/** Today: confirm the food arrived, children present (if no FRAT), and feedback. */
function TodayCard({ order: o, canReceive, onChange }: CardProps) {
  const [qty, setQty] = useState(o.lockedQty ?? 0)
  const [present, setPresent] = useState(0)
  const { error, setError, act, narrate } = useAction(onChange)

  const receive = async (confirmLarge = false) => {
    setError(null)
    try {
      const r = await post<{ replayed: boolean }>(`/api/centre/orders/${o.id}/receive`, { qty, confirmLarge })
      narrate(r.replayed
        ? 'Already recorded. The second tap did not create a second receipt (safe on a bad network).'
        : `Sunita confirmed ${qty} ${meal(o.mealSlot)} arrived. Saurabh's dashboard now shows this centre as received, and the kitchen will be paid for ${qty}.`)
      onChange()
    } catch (e) {
      if (e instanceof ApiError && e.code === 'LARGE_QTY_NEEDS_CONFIRM') {
        if (window.confirm(`${e.message}\n\nConfirm ${qty} meals?`)) return receive(true)
        return
      }
      setError(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <Card className="space-y-4">
      <div className="flex items-center justify-between">
        <Slot slot={o.mealSlot} />
        <StatusBadge status={o.status} />
      </div>
      <Lifecycle status={o.status} />

      {o.status === 'CLOSED' && <div className="rounded-xl bg-stone-50 p-3 text-sm text-stone-600">Closed: “{o.closedReason}”.</div>}

      {(o.status === 'LOCKED' || o.status === 'DISPATCHED') && (
        <>
          <div className="rounded-xl bg-violet-50 p-3 text-sm text-violet-900">
            <b>{o.lockedQty} meals</b> {o.status === 'DISPATCHED' ? 'are on the way to you.' : 'are being prepared.'}
            <div className="text-xs text-violet-900/70">Please confirm by {timeIST(o.confirmBy)}, so no centre is missed.</div>
          </div>
          {canReceive ? (
            <div className="space-y-2">
              <div className="text-xs font-medium text-stone-600">How many meals arrived?</div>
              <div className="flex items-center gap-2">
                <Stepper value={qty} onChange={setQty} />
                <Tour id={o.mealSlot === 'BREAKFAST' ? 'receive' : 'receive-lunch'} tip="Tap when the food arrives" className="flex-1">
                  <Button variant="brand" className="w-full" onClick={() => receive()}>Food received</Button>
                </Tour>
              </div>
            </div>
          ) : (
            <p className="text-xs text-stone-500">You can confirm once the service day starts.</p>
          )}
        </>
      )}

      {o.status === 'RECEIVED' && (
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-2">
            <Box label="Meals received" value={`${o.receivedQty}`} sub={`of ${o.lockedQty} sent`} />
            {o.present != null
              ? <Box label="Children present" value={`${o.present}`} sub={o.attendanceSource === 'FRAT' ? 'face scan (FRAT)' : 'counted by hand'} />
              : (
                <div className="rounded-xl border border-dashed border-stone-300 p-2.5">
                  <div className="text-[11px] text-stone-500">Children present</div>
                  <div className="mt-1 flex gap-1">
                    <input type="number" min={0} value={present} onChange={(e) => setPresent(Number(e.target.value))}
                           className="w-16 rounded-lg border border-stone-300 px-2 py-1 text-center tabular-nums" />
                    <Button variant="secondary" className="!px-2 !py-1 text-xs" onClick={() => act(() => post(`/api/centre/orders/${o.id}/attendance`, { present }),
                      `Sunita counted ${present} children by hand (this centre has no FRAT tablet). Now meals can be matched with children.`)}>Save</Button>
                  </div>
                </div>
              )}
          </div>
          {!o.hasFeedback ? <FeedbackForm orderId={o.id} onDone={onChange} />
            : <div className="rounded-xl bg-brand-50 px-3 py-2 text-xs text-brand-900">✓ Feedback sent. Thank you.</div>}
        </div>
      )}
      <ErrorNote message={error} />
    </Card>
  )
}

function Box({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div className="rounded-xl bg-stone-50 p-2.5">
      <div className="text-[11px] text-stone-500">{label}</div>
      <div className="text-2xl font-bold tabular-nums">{value}</div>
      <div className="text-[11px] text-stone-500">{sub}</div>
    </div>
  )
}

function FeedbackForm({ orderId, onDone }: { orderId: number; onDone: () => void }) {
  const [answers, setAnswers] = useState({ quantityOk: true, qualityOk: true, onTime: true })
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const { narrate } = useTour()

  const toggle = (key: keyof typeof answers, label: string) => (
    <button
      onClick={() => setAnswers({ ...answers, [key]: !answers[key] })}
      className={`flex-1 rounded-xl border px-2 py-2.5 text-xs font-medium transition ${answers[key] ? 'border-brand-200 bg-brand-50 text-brand-900' : 'border-red-200 bg-red-50 text-red-900'}`}
    >
      <div className="text-lg">{answers[key] ? '👍' : '👎'}</div>
      {label}
    </button>
  )

  const send = async () => {
    try {
      await post(`/api/centre/orders/${orderId}/feedback`, { ...answers, note })
      narrate(note.trim()
        ? 'Feedback saved. In the background, AI reads the note and tags it (late, quality, hygiene…) for the kitchen scorecard Saurabh sees.'
        : 'Feedback saved. It counts towards this kitchen’s weekly scorecard.')
      onDone()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <div className="rounded-2xl border border-stone-200 p-3">
      <div className="mb-2 text-sm font-semibold">How was the meal?</div>
      <div className="flex gap-2">
        {toggle('quantityOk', 'Enough food')}
        {toggle('qualityOk', 'Good quality')}
        {toggle('onTime', 'On time')}
      </div>
      <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={500}
                placeholder="Anything else? Write in any language, e.g. “dal bahut patli thi”"
                className="mt-2 w-full rounded-xl border border-stone-300 px-3 py-2 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-100" />
      <p className="mb-2 text-[11px] text-stone-400">AI reads the note and tags it (late, quality, hygiene…) for the kitchen scorecard.</p>
      <ErrorNote message={error} />
      <Tour id="feedback" tip="Send feedback about the meal">
        <Button variant="secondary" className="w-full" onClick={send}>Send feedback</Button>
      </Tour>
    </div>
  )
}
