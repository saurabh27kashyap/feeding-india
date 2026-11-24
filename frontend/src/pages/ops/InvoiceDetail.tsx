import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, api, dateTimeIST, day, num, post, rupees, timeIST } from '../../api'
import { useData } from '../../hooks'
import { Tour, useTour } from '../../tour'
import type { InvoiceDetail as Detail } from '../../types'
import { Button, Card, ErrorNote, Flag, Slot, Spinner } from '../../ui'
import { EVENT_ICON, FLAG_VIEW, STATUS_CHIP } from '../partner/KitchenBills'

const OPS_STATUS: Record<string, string> = {
  SUBMITTED: 'Waiting for your review',
  REJECTED: 'Rejected · waiting for the kitchen to resend',
  APPROVED: 'Approved · to pay',
  PAID: 'Paid',
}

/** One kitchen's bill for one day: check each line against proof, then approve or reject, then pay. */
export default function InvoiceDetail() {
  const { id } = useParams()
  const { data, error, reload } = useData(() => api<Detail>(`/api/ops/invoices/${id}`), [id], 8000)
  const [actionError, setActionError] = useState<string | null>(null)
  const [onlyFlagged, setOnlyFlagged] = useState(true)
  const [utr, setUtr] = useState('')
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const { narrate } = useTour()
  if (!data) return error ? <ErrorNote message={error} /> : <Spinner />

  const inv = data.invoice
  const act = async (fn: () => Promise<unknown>, says: string) => {
    setActionError(null)
    try {
      await fn()
      narrate(says)
      reload()
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : String(e))
      if (e instanceof ApiError && e.code === 'UNCONFIRMED_DELIVERIES') {
        narrate('Approval is blocked on purpose: some centres have not confirmed their food. Open a “Centre has not confirmed” line, call the centre, and confirm on their behalf. Or reject the bill with a reason.', false)
      }
    }
  }
  const approve = () => act(() => post(`/api/ops/invoices/${id}/approve`),
    `Saurabh approved ${rupees(inv.payablePaise)} for ${inv.vendorName} (billed ${rupees(inv.billedPaise)}). Only confirmed meals, never more than ordered. Ramesh sees the decision and every cut on his screen.`)
  const reject = () => act(async () => {
    await post(`/api/ops/invoices/${id}/reject`, { reason })
    setRejecting(false)
    setReason('')
  }, `Saurabh sent the bill back: “${reason}”. Ramesh sees the reason, fixes the lines and resends.`)
  const pay = () => act(() => post(`/api/ops/invoices/${id}/pay`, { paymentRef: utr }),
    `Payment of ${rupees(inv.payablePaise)} to ${inv.vendorName} recorded with bank reference ${utr.toUpperCase()}. Ramesh sees it as paid.`)

  const lines = onlyFlagged ? data.lines.filter((l) => l.flag !== 'OK') : data.lines

  return (
    <div className="space-y-4">
      <Link to="/ops/invoices" className="text-sm font-medium text-stone-600 hover:text-stone-900">← Invoices</Link>
      <Card>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="text-lg font-bold">{inv.vendorName} · {day(inv.serviceDate)}</h1>
            <span className={`mt-1 inline-block rounded-full px-2.5 py-0.5 text-sm font-semibold ${STATUS_CHIP[inv.status]}`}>{OPS_STATUS[inv.status]}</span>
          </div>
          <div className="grid grid-cols-3 gap-5 text-right">
            <div><div className="text-[11px] text-stone-500">Billed</div><div className="text-lg font-bold tabular-nums">{rupees(inv.billedPaise)}</div><div className="text-[11px] text-stone-500">{num(inv.billedMeals)} meals</div></div>
            <div><div className="text-[11px] text-stone-500">Payable</div><div className="text-lg font-bold tabular-nums text-brand-700">{rupees(inv.payablePaise)}</div><div className="text-[11px] text-stone-500">{num(inv.payableMeals)} meals</div></div>
            <div><div className="text-[11px] text-stone-500">Lines to check</div><div className="text-lg font-bold tabular-nums">{inv.flaggedLines}<span className="text-sm text-stone-400">/{inv.lines}</span></div>
              {inv.unconfirmedLines > 0 && <div className="text-[11px] text-orange-700">{inv.unconfirmedLines} not confirmed</div>}</div>
          </div>
        </div>

        {inv.status === 'SUBMITTED' && (
          <div className="mt-4 flex flex-wrap items-center gap-2">
            <Tour id="approve" tip="Approve: pays the Payable amount">
              <Button variant="brand" onClick={approve}>✓ Approve {rupees(inv.payablePaise)}</Button>
            </Tour>
            <Button variant="danger" onClick={() => setRejecting(!rejecting)}>↩ Reject</Button>
            <span className="text-xs text-stone-500">Approve pays only the Payable column. Reject sends it back with your reason.</span>
          </div>
        )}
        {rejecting && (
          <div className="mt-3 flex flex-wrap gap-2 rounded-xl border border-red-200 bg-red-50/60 p-3">
            <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Reason, e.g. Lines 3 and 7 billed above the locked count"
                   className="flex-1 rounded-lg border border-stone-300 px-3 py-1.5 text-sm" />
            <Button variant="danger" onClick={reject} disabled={reason.trim().length < 5}>Send back to kitchen</Button>
          </div>
        )}
        {inv.status === 'APPROVED' && (
          <div className="mt-4 flex flex-wrap items-center gap-2">
            <input value={utr} onChange={(e) => setUtr(e.target.value)} placeholder="Bank UTR, e.g. UTR2611240001"
                   className="w-64 rounded-xl border border-stone-300 px-3 py-2 font-mono text-sm outline-none focus:border-brand-500" />
            <Tour id="pay" tip="Record the bank payment">
              <Button variant="brand" onClick={pay} disabled={utr.trim().length < 6}>💸 Mark as paid</Button>
            </Tour>
            <span className="text-xs text-stone-500">One bank reference can pay only one bill.</span>
          </div>
        )}
        {inv.status === 'REJECTED' && inv.rejectReason && <div className="mt-3 rounded-xl bg-red-50 p-3 text-sm text-red-900">Sent back: “{inv.rejectReason}”</div>}
        {inv.status === 'PAID' && <div className="mt-3"><Flag level="good">Paid {rupees(inv.payablePaise)} · {dateTimeIST(inv.paidAt!)} · {inv.paymentRef}</Flag></div>}
        <ErrorNote message={actionError} />

        <ol className="mt-4 flex flex-wrap gap-x-4 gap-y-1 border-t border-stone-100 pt-3 text-xs text-stone-600">
          {data.events.map((e) => (
            <li key={e.id}>{EVENT_ICON[e.kind]} <b>{e.kind.charAt(0) + e.kind.slice(1).toLowerCase()}</b> · {e.actorLabel} · {dateTimeIST(e.at)}{e.note ? ` · ${e.note}` : ''}</li>
          ))}
        </ol>
      </Card>

      <Card>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={onlyFlagged} onChange={(e) => setOnlyFlagged(e.target.checked)} />
            Show only lines that need checking
          </label>
          <span className="text-xs text-stone-500">Proof: who confirmed the food, when, and how many children FRAT counted.</span>
        </div>
        <div className="max-h-[32rem] overflow-auto rounded-xl border border-stone-100">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-stone-50 text-left text-xs text-stone-500">
              <tr><th className="px-3 py-2">Centre</th><th>Meal</th><th className="text-right">Ordered</th><th className="text-right">Billed</th>
                <th className="text-right">Received</th><th className="text-right">Pay</th><th className="pl-4">Proof</th><th className="px-3">Check</th></tr>
            </thead>
            <tbody>
              {lines.map((l) => (
                <tr key={l.lineId} className="border-t border-stone-100 align-top">
                  <td className="px-3 py-2"><Link className="font-medium hover:underline" to={`/ops/orders/${l.orderId}`}>{l.centreName}</Link><div className="text-[11px] text-stone-500">{l.routeName}</div></td>
                  <td className="py-2"><Slot slot={l.mealSlot} /></td>
                  <td className="py-2 text-right tabular-nums">{l.lockedQty ?? '—'}</td>
                  <td className="py-2 text-right tabular-nums">{l.billedQty}</td>
                  <td className="py-2 text-right tabular-nums">{l.receivedQty ?? '—'}</td>
                  <td className="py-2 text-right font-semibold tabular-nums">{l.payableQty}</td>
                  <td className="py-2 pl-4 text-[11px] text-stone-600">
                    {l.receivedBy ? <>✍ {l.receivedBy}{l.receivedAt && ` · ${timeIST(l.receivedAt)}`}</> : <span className="text-orange-700">No receipt yet</span>}
                    {l.childrenPresent != null && <div>👧 {l.childrenPresent} children present</div>}
                  </td>
                  <td className="px-3 py-2">{FLAG_VIEW[l.flag] ?? l.flag}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!lines.length && <p className="px-3 py-3 text-sm text-stone-500">Every line matches.</p>}
        </div>
      </Card>
    </div>
  )
}
