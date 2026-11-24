import { useState, type ReactNode } from 'react'
import { useParams } from 'react-router'
import { ApiError, api, dateTimeIST, day, put } from '../../api'
import { useData } from '../../hooks'
import { useTour } from '../../tour'
import type { OrderTimeline } from '../../types'
import { Button, Card, ErrorNote, Slot, StatusBadge , Spinner } from '../../ui'

/** One meal, end to end, with its full audit trail. */
export default function OrderDetail() {
  const { id } = useParams()
  const { data, error, reload } = useData(() => api<OrderTimeline>(`/api/ops/orders/${id}`), [id])
  const [qty, setQty] = useState('')
  const [reason, setReason] = useState('')
  const [cQty, setCQty] = useState('')
  const [cReason, setCReason] = useState('')
  const [overrideError, setOverrideError] = useState<string | null>(null)
  const { narrate } = useTour()
  if (!data) return error ? <ErrorNote message={error} /> : <Spinner />
  const o = data.order

  const override = async () => {
    setOverrideError(null)
    try {
      await put(`/api/ops/orders/${id}/override`, { qty: Number(qty), reason })
      narrate(`Saurabh changed the locked count to ${qty}. The reason is saved in the audit trail, so anyone can see who changed it and why.`)
      setQty('')
      setReason('')
      reload()
    } catch (e) {
      setOverrideError(e instanceof ApiError ? e.message : String(e))
    }
  }

  const confirm = async () => {
    setOverrideError(null)
    try {
      await put(`/api/ops/orders/${id}/confirm`, { qty: Number(cQty), reason: cReason })
      narrate(Number(cQty) === 0
        ? 'Saurabh recorded that no food reached this centre. The kitchen will not be paid for it, and the reason is in the audit trail.'
        : `Saurabh confirmed ${cQty} meals on the centre’s behalf after a call. The kitchen’s bill can now be approved.`)
      setCQty('')
      setCReason('')
      reload()
    } catch (e) {
      setOverrideError(e instanceof ApiError ? e.message : String(e))
    }
  }

  const row = (label: string, value: ReactNode) => (
    <div className="flex justify-between border-t border-stone-100 py-1.5 text-sm"><span className="text-stone-500">{label}</span><span>{value}</span></div>
  )

  return (
    <div className="grid gap-5 lg:grid-cols-2">
      <Card>
        <div className="mb-2 flex items-center justify-between">
          <div>
            <h1 className="text-lg font-bold">{o.centreName}</h1>
            <div className="text-xs text-stone-500">{o.district} · {o.routeName} · {o.vendorName}</div>
          </div>
          <StatusBadge status={o.status} />
        </div>
        {row('Service', <>{day(o.serviceDate)} · <Slot slot={o.mealSlot} /></>)}
        {row('Enrolled', o.enrolled)}
        {row('Suggested', <span title={o.suggestionBasis}>{o.suggestedQty}</span>)}
        {row('Basis', <span className="max-w-xs text-right text-xs">{o.suggestionBasis}</span>)}
        {row('Requested', o.requestedQty)}
        {row('Locked', o.lockedQty ?? '—')}
        {row('Received', o.receivedQty ?? '—')}
        {row('Children present', o.present != null ? `${o.present} (${o.attendanceSource})` : '—')}
        {o.closedReason && row('Closed because', o.closedReason)}
        {o.onTime != null && row('Feedback', `${o.quantityOk ? '👍' : '👎'} quantity · ${o.qualityOk ? '👍' : '👎'} quality · ${o.onTime ? '👍' : '👎'} on time`)}
        {o.feedbackNote && row('Note', <span className="max-w-xs text-right">“{o.feedbackNote}”</span>)}
        {o.feedbackNote && row('AI tags', o.tagStatus === 'TAGGED' ? o.feedbackTags : o.tagStatus)}

        {(o.status === 'LOCKED' || o.status === 'DISPATCHED') && (
          <div className="mt-4 rounded-xl border border-orange-200 bg-orange-50/60 p-3">
            <div className="mb-1 text-xs font-semibold">Centre has not confirmed? Call them and confirm on their behalf</div>
            <div className="mb-2 text-[11px] text-stone-600">Enter how many meals they say arrived (0 = food never came). Allowed after the confirm-by time; the reason is kept in the audit trail.</div>
            <div className="flex flex-wrap gap-2">
              <input type="number" placeholder="meals" value={cQty} onChange={(e) => setCQty(e.target.value)}
                     className="w-20 rounded-lg border border-stone-300 px-2 py-1" />
              <input placeholder="e.g. Called Sunita: 30 arrived" value={cReason} onChange={(e) => setCReason(e.target.value)}
                     className="flex-1 rounded-lg border border-stone-300 px-2 py-1" />
              <Button variant="brand" onClick={confirm} disabled={cQty === '' || !cReason}>Confirm</Button>
            </div>
          </div>
        )}

        {o.status === 'LOCKED' && (
          <div className="mt-4 rounded-lg bg-stone-50 p-3">
            <div className="mb-2 text-xs font-semibold">Override locked count (before dispatch, reason required)</div>
            <div className="flex flex-wrap gap-2">
              <input type="number" placeholder="qty" value={qty} onChange={(e) => setQty(e.target.value)}
                     className="w-20 rounded border border-stone-300 px-2 py-1" />
              <input placeholder="reason" value={reason} onChange={(e) => setReason(e.target.value)}
                     className="flex-1 rounded border border-stone-300 px-2 py-1" />
              <Button onClick={override} disabled={!qty || !reason}>Override</Button>
            </div>
            <ErrorNote message={overrideError} />
          </div>
        )}
      </Card>

      <Card>
        <h2 className="mb-3 font-semibold">Audit trail</h2>
        {!data.events.length && <p className="text-sm text-stone-500">Imported history: no events recorded.</p>}
        <ol className="space-y-3">
          {data.events.map((e) => (
            <li key={e.id} className="border-l-2 border-stone-300 pl-3">
              <div className="text-sm">
                <b>{e.fromStatus ?? '—'} → {e.toStatus}</b>{e.qty != null && <> · {e.qty} meals</>}
              </div>
              <div className="text-xs text-stone-500">{dateTimeIST(e.at)} · {e.actorLabel}</div>
              {e.reason && <div className="text-xs text-stone-700">{e.reason}</div>}
            </li>
          ))}
        </ol>
      </Card>
    </div>
  )
}
