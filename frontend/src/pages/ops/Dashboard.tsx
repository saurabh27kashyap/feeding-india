import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { api, day, num, timeIST } from '../../api'
import { useData } from '../../hooks'
import type { Dashboard as Data } from '../../types'
import { Tour } from '../../tour'
import { Card, ErrorNote, Explainer, Flag, SectionTitle, Slot, Spinner } from '../../ui'

/** Today at a glance. Polls every 10 s; every number comes straight from what partners recorded. */
export default function Dashboard() {
  const { data, error } = useData(() => api<Data>('/api/ops/dashboard'), [], 5000)
  if (!data) return error ? <ErrorNote message={error} /> : <Spinner />

  const s = data.summary
  const b = data.balance
  const n = data.nextDay
  const savedVsEnrolment = n.enrolledChildren > 0 ? Math.round(100 * (1 - n.mealsRequested / n.enrolledChildren)) : 0
  const notConfirmed = s.unconfirmed - s.notDispatched

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="text-sm text-stone-500">Service day</div>
          <h1 className="text-2xl font-bold tracking-tight">{day(data.date)}</h1>
        </div>
        <div className="flex items-center gap-2 rounded-full bg-white px-3 py-1.5 text-xs text-stone-600 shadow-sm ring-1 ring-stone-200">
          <span className="relative flex h-2 w-2"><span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand-500 opacity-60" /><span className="relative inline-flex h-2 w-2 rounded-full bg-brand-500" /></span>
          Live · updated {timeIST(data.now)} IST
        </div>
      </div>

      <Explainer title="How to read this page">
        Every centre gets one order per meal. Work top to bottom: first make sure every meal <b>reached</b> its centre
        (unconfirmed), then that every child <b>ate</b> (shortages), then how much food was <b>wasted</b>.
      </Explainer>

      <Card>
        <SectionTitle title="Where today's meals are" hint={`${num(s.orders)} orders across all centres, by stage`} />
        <Pipeline steps={[
          { label: 'Open', hint: 'count can still change', value: s.open, tone: 'bg-sky-50 text-sky-900' },
          { label: 'Locked', hint: 'kitchen cooking', value: s.locked, tone: 'bg-amber-50 text-amber-900' },
          { label: 'On the way', hint: 'route dispatched', value: s.dispatched, tone: 'bg-violet-50 text-violet-900' },
          { label: 'Received', hint: 'centre confirmed', value: s.received, tone: 'bg-brand-50 text-brand-900' },
          { label: 'Closed', hint: 'centre shut', value: s.closed, tone: 'bg-stone-100 text-stone-700' },
        ]} />
      </Card>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Tour id="unconfirmed" tip="Start here: which centres may have no food">
        <Tile label="Unconfirmed deliveries" value={num(s.unconfirmed)}
              meaning="Past the confirm-by time and the centre has not said the food arrived."
              detail={`${num(s.notDispatched)} never left the kitchen · ${num(notConfirmed)} not confirmed`}
              flag={s.unconfirmed > 0 ? <Flag level="serious">Call kitchen or centre</Flag> : <Flag level="good">All confirmed</Flag>} />
        </Tour>
        <Tile label="Shortages" value={num(b.shortages)}
              meaning="More children came than meals arrived. A child may not have eaten."
              detail={`${num(b.shortMeals)} children without a meal`}
              flag={b.shortages > 0 ? <Flag level="critical">Highest priority</Flag> : <Flag level="good">None</Flag>} />
        <Tile label="Waste" value={b.wastePercent != null ? `${b.wastePercent}%` : '—'}
              meaning="Meals received above the number of children present."
              detail={`${num(b.wasteMeals)} meals${b.plannedWasteFromClosures ? ` · +${num(b.plannedWasteFromClosures)} from late closures` : ''}`} />
        <Tile label="Children fed" value={num(b.childrenPresent)}
              meaning="Counted by FRAT face scans, or by hand where FRAT is not used. Meals with no head count cannot be checked for waste or shortage."
              flag={s.receivedWithoutHeadcount > 0 ? <Flag level="warning">{num(s.receivedWithoutHeadcount)} meals not counted</Flag> : undefined}
              detail={`${num(s.mealsReceived)} meals confirmed · ${data.invoicesApproved}/${data.invoicesSubmitted + data.invoicesApproved} invoices approved`} />
      </div>

      <Card className="bg-gradient-to-r from-white to-sky-50/60">
        <div className="flex flex-wrap items-center gap-6">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-sky-600 text-xl text-white">📅</div>
          <div className="flex-1">
            <div className="text-sm font-semibold">Next service day · {day(n.serviceDate)}</div>
            <div className="text-xs text-stone-500">Counts lock at {timeIST(n.cutoffAt)} IST the evening before</div>
          </div>
          <Metric label="Meals planned" value={num(n.mealsRequested)} />
          <Metric label="Children enrolled" value={num(n.enrolledChildren)} />
          <Metric label="Less than full enrolment" value={`${savedVsEnrolment}%`} accent />
          <Metric label="Centres closed" value={num(n.closed)} />
        </div>
        <p className="mt-3 text-xs text-stone-500">
          The plan comes from each centre's attendance on the last 4 same weekdays + 5% buffer, not from enrolment, so kitchens cook for children who actually come.
        </p>
      </Card>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card>
          <SectionTitle title={`Unconfirmed deliveries (${data.unconfirmed.length})`}
                        hint="“Not dispatched”: call the kitchen. “Not confirmed”: call the centre." />
          <Table empty="Every delivery is confirmed."
                 head={['Centre', 'Route', 'Kitchen', 'Meal', 'Stage']}
                 rows={data.unconfirmed.map((u) => [
                   <Link className="font-medium text-stone-900 hover:underline" to={`/ops/orders/${u.orderId}`}>{u.centreName}</Link>,
                   u.routeName, u.vendorName, <Slot slot={u.mealSlot} />,
                   u.stage === 'NOT_DISPATCHED' ? <Flag level="critical">Not dispatched</Flag> : <Flag level="serious">Not confirmed</Flag>,
                 ])} />
        </Card>
        <Card>
          <SectionTitle title={`Shortages (${data.shortages.length})`} hint="Children present minus meals received, biggest gap first." />
          <Table empty="No shortages. Every child present got a meal."
                 head={['Centre', 'Kitchen', 'Meal', 'Received', 'Present', 'Short']}
                 rows={data.shortages.map((x) => [
                   <Link className="font-medium text-stone-900 hover:underline" to={`/ops/orders/${x.orderId}`}>{x.centreName}</Link>,
                   x.vendorName, <Slot slot={x.mealSlot} />, x.receivedQty, x.present, <b className="text-red-700">−{x.gap}</b>,
                 ])} />
        </Card>
      </div>

      <Card>
        <SectionTitle title={`Late vans (${data.lateVans.length})`}
                      hint="Vans that left more than 10 minutes after their deadline (6:30 AM breakfast, 11:00 AM lunch), or have not left at all. Measured from timestamps, not opinions." />
        <Table empty="Every van left on time."
               head={['Route', 'Kitchen', 'Meal', 'Leave by', 'Left at', 'Late by']}
               rows={data.lateVans.map((v) => [
                 <b>{v.routeName}</b>, v.vendorName, <Slot slot={v.mealSlot} />, timeIST(v.dispatchBy),
                 v.leftAt ? timeIST(v.leftAt) : <Flag level="critical">Not left</Flag>,
                 <span className="font-semibold text-red-700">{v.minutesLate} min</span>,
               ])} />
      </Card>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card>
          <SectionTitle title="Waste % by service day" hint="Meals received above children present, as a share of meals received. Saturdays run high: fewer children come." />
          <WasteTrend points={data.trend} />
        </Card>
        <Card>
          <SectionTitle title="Most waste today" hint="Centres whose count is far above attendance: candidates to lower their count." />
          <Table empty="Nothing notable yet."
                 head={['Centre', 'Meal', 'Received', 'Present', 'Waste']}
                 rows={data.topWaste.map((w) => [
                   <Link className="font-medium text-stone-900 hover:underline" to={`/ops/orders/${w.orderId}`}>{w.centreName}</Link>,
                   <Slot slot={w.mealSlot} />, w.receivedQty, w.present, `${w.waste} (${w.wastePercent}%)`,
                 ])} />
        </Card>
      </div>
    </div>
  )
}

function Pipeline({ steps }: { steps: { label: string; hint: string; value: number; tone: string }[] }) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
      {steps.map((st, i) => (
        <div key={st.label} className="relative">
          <div className={`rounded-xl p-3 ${st.tone}`}>
            <div className="text-[11px] font-semibold uppercase tracking-wide opacity-80">{i + 1}. {st.label}</div>
            <div className="text-2xl font-bold tabular-nums">{num(st.value)}</div>
            <div className="text-[11px] opacity-70">{st.hint}</div>
          </div>
          {i < steps.length - 2 && <span className="absolute -right-2 top-1/2 z-10 hidden -translate-y-1/2 text-stone-300 sm:block">▶</span>}
        </div>
      ))}
    </div>
  )
}

function Tile({ label, value, meaning, detail, flag }: { label: string; value: string; meaning: string; detail: string; flag?: ReactNode }) {
  return (
    <Card className="!p-4">
      <div className="flex items-start justify-between gap-2">
        <div className="text-sm font-medium text-stone-600">{label}</div>
        {flag}
      </div>
      <div className="mt-1 text-3xl font-bold tracking-tight tabular-nums">{value}</div>
      <div className="mt-1 text-xs text-stone-700">{detail}</div>
      <div className="mt-2 border-t border-stone-100 pt-2 text-[11px] leading-snug text-stone-500">{meaning}</div>
    </Card>
  )
}

function Metric({ label, value, accent = false }: { label: string; value: string; accent?: boolean }) {
  return (
    <div>
      <div className="text-[11px] text-stone-500">{label}</div>
      <div className={`text-xl font-bold tabular-nums ${accent ? 'text-sky-700' : ''}`}>{value}</div>
    </div>
  )
}

function Table({ head, rows, empty }: { head: string[]; rows: ReactNode[][]; empty: string }) {
  if (!rows.length) {
    return <div className="flex items-center gap-2 rounded-xl bg-brand-50/60 px-3 py-4 text-sm text-brand-900"><Flag level="good">{empty}</Flag></div>
  }
  return (
    <div className="max-h-80 overflow-auto rounded-xl border border-stone-100">
      <table className="w-full text-sm">
        <thead className="sticky top-0 bg-stone-50 text-left text-xs text-stone-500">
          <tr>{head.map((h) => <th key={h} className="px-3 py-2 font-medium">{h}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className="border-t border-stone-100 hover:bg-stone-50/60">
              {r.map((cell, j) => <td key={j} className="px-3 py-2 tabular-nums">{cell}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * Single-series bar chart: one hue, thin bars with rounded tops anchored to the
 * baseline, 2px gaps, recessive grid, per-bar hover readout, and a table view.
 */
function WasteTrend({ points }: { points: Data['trend'] }) {
  const [hover, setHover] = useState<number | null>(null)
  const [asTable, setAsTable] = useState(false)
  if (!points.length) return <p className="text-sm text-stone-500">No attendance data yet.</p>

  const max = Math.max(20, ...points.map((p) => p.wastePercent ?? 0))
  const top = Math.ceil(max / 10) * 10
  const h = 170

  if (asTable) {
    return (
      <div>
        <button className="mb-2 text-xs font-medium text-stone-600 underline" onClick={() => setAsTable(false)}>Show chart</button>
        <Table empty="" head={['Day', 'Meals', 'Waste meals', 'Waste %']}
               rows={points.map((p) => [day(p.serviceDate), num(p.mealsWithAttendance), num(p.wasteMeals), `${p.wastePercent ?? '—'}%`])} />
      </div>
    )
  }

  const active = hover != null ? points[hover] : points[points.length - 1]
  return (
    <div>
      <div className="mb-3 flex items-center justify-between text-xs">
        <span className="text-stone-700">
          <b>{day(active.serviceDate)}</b>: <span className="text-base font-bold tabular-nums">{active.wastePercent ?? '—'}%</span>{' '}
          <span className="text-stone-500">({num(active.wasteMeals)} of {num(active.mealsWithAttendance)} meals)</span>
        </span>
        <button className="font-medium text-stone-500 underline" onClick={() => setAsTable(true)}>Table view</button>
      </div>
      <div className="relative ml-8" style={{ height: h }}>
        {[0, 0.5, 1].map((f) => (
          <div key={f} className="absolute inset-x-0 border-t border-dashed border-stone-200" style={{ bottom: f * h }}>
            <span className="absolute -left-8 -top-2 w-7 text-right text-[10px] tabular-nums text-stone-400">{Math.round(f * top)}%</span>
          </div>
        ))}
        <div className="absolute inset-0 flex items-end gap-[2px]">
          {points.map((p, i) => (
            <div key={p.serviceDate} className="flex h-full flex-1 cursor-default items-end px-[1px]"
                 onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
              <div className="w-full rounded-t-[4px] bg-series transition-opacity"
                   style={{ height: `${((p.wastePercent ?? 0) / top) * 100}%`, opacity: hover == null || hover === i ? 1 : 0.45 }} />
            </div>
          ))}
        </div>
      </div>
      <div className="ml-8 mt-1.5 flex justify-between text-[10px] text-stone-500">
        <span>{day(points[0].serviceDate)}</span>
        <span>{day(points[points.length - 1].serviceDate)}</span>
      </div>
    </div>
  )
}
