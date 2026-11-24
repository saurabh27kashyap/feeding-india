import { useEffect, useState, type ReactNode } from 'react'
import { ApiError, api, dateTimeIST, day, num, post, rupees } from '../../api'
import { useData } from '../../hooks'
import { Tour, useTour } from '../../tour'
import type { InvoiceDetail, InvoiceDraft, InvoiceSummary } from '../../types'
import { Button, Card, ErrorNote, Flag, SectionTitle, Slot, Spinner } from '../../ui'

/**
 * The kitchen's side of billing: send the day's bill (every line editable), see Ops' decision
 * with the reason for every rupee cut, fix and resend a rejected bill, and see payments.
 */

export const STATUS_CHIP: Record<InvoiceSummary['status'], string> = {
  SUBMITTED: 'bg-amber-50 text-amber-800',
  REJECTED: 'bg-red-50 text-red-800',
  APPROVED: 'bg-sky-50 text-sky-800',
  PAID: 'bg-brand-50 text-brand-700',
}
export const STATUS_TEXT: Record<InvoiceSummary['status'], string> = {
  SUBMITTED: 'Waiting for Ops',
  REJECTED: 'Rejected · fix and resend',
  APPROVED: 'Approved · payment pending',
  PAID: 'Paid',
}

export const FLAG_VIEW: Record<string, ReactNode> = {
  OK: <Flag level="good">OK</Flag>,
  OVER_BILLED: <Flag level="critical">Billed more than received</Flag>,
  UNDER_BILLED: <Flag level="warning">Billed less than received</Flag>,
  NOT_CONFIRMED: <Flag level="serious">Centre has not confirmed</Flag>,
  LATE_CLOSURE: <Flag level="warning">Closed late (paid)</Flag>,
}

export const EVENT_ICON: Record<string, string> = { SUBMITTED: '📤', REJECTED: '↩️', RESUBMITTED: '📤', APPROVED: '✅', PAID: '💸' }

/** The bill for one day: editor if none or rejected, status otherwise. */
export function BillPanel({ date, onChanged }: { date: string; onChanged: () => void }) {
  const draft = useData(() => api<InvoiceDraft>(`/api/kitchen/invoice-draft?date=${date}`), [date])
  const billId = draft.data?.existingInvoiceId
  const bill = useData(() => (billId ? api<InvoiceDetail>(`/api/kitchen/invoices/${billId}`) : Promise.resolve(null)), [billId], 8000)

  if (!draft.data) return draft.error ? <ErrorNote message={draft.error} /> : <Spinner />
  const reload = () => {
    draft.reload()
    bill.reload()
    onChanged()
  }

  if (!billId) return <BillEditor date={date} draft={draft.data} onDone={reload} />
  if (!bill.data) return <Spinner />
  if (bill.data.invoice.status === 'REJECTED') {
    return (
      <div className="space-y-4">
        <BillStatus d={bill.data} />
        <BillEditor date={date} draft={draft.data} rejected={bill.data} onDone={reload} />
      </div>
    )
  }
  return <BillStatus d={bill.data} />
}

function BillEditor({ date, draft, rejected, onDone }: { date: string; draft: InvoiceDraft; rejected?: InvoiceDetail; onDone: () => void }) {
  const [billed, setBilled] = useState<Record<number, number>>({})
  const [error, setError] = useState<string | null>(null)
  const { narrate } = useTour()

  useEffect(() => {
    // New bill: the locked counts. Resend: what was billed last time, so the kitchen fixes only the bad lines.
    const previous = new Map(rejected?.lines.map((l) => [l.orderId, l.billedQty]) ?? [])
    setBilled(Object.fromEntries(draft.lines.map((l) => [l.orderId, previous.get(l.orderId) ?? l.lockedQty ?? 0])))
  }, [draft, rejected])

  const total = draft.lines.reduce((s, l) => s + (billed[l.orderId] ?? 0), 0)
  const lines = draft.lines.map((l) => ({ orderId: l.orderId, billedQty: billed[l.orderId] ?? 0 }))
  const send = async () => {
    setError(null)
    try {
      if (rejected) {
        await post(`/api/kitchen/invoices/${rejected.invoice.id}/resubmit`, { lines })
        narrate(`Ramesh fixed the bill and sent it again (${num(total)} meals). It is back in Saurabh’s review queue.`)
      } else {
        await post('/api/kitchen/invoices', { serviceDate: date, lines })
        narrate(`Ramesh sent the bill for ${num(total)} meals. It appears in Saurabh’s Invoices at once; he pays only what centres confirmed.`)
      }
      onDone()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <Card>
      <SectionTitle
        title={rejected ? 'Fix the bill and send it again' : `Bill for ${day(date)}`}
        hint={rejected ? 'Change only the lines Ops questioned, then resend.' : 'Prefilled with the locked counts. Change a line if you sent a different amount.'}
        right={
          <Tour id="submit-bill" tip="Send today's bill">
            <Button variant="brand" onClick={send} disabled={!draft.lines.length}>{rejected ? 'Resend' : 'Send bill'} · {num(total)} meals</Button>
          </Tour>
        } />
      <ErrorNote message={error} />
      {!draft.lines.length ? <p className="text-sm text-stone-500">Nothing to bill yet: counts lock at 8 PM the evening before.</p> : (
        <div className="max-h-[24rem] overflow-auto rounded-xl border border-stone-100">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-stone-50 text-left text-xs text-stone-500">
              <tr><th className="px-3 py-2">Centre</th><th>Route</th><th>Meal</th><th className="text-right">Ordered</th>
                <th className="text-right">Received</th><th className="px-3 text-right">Bill</th></tr>
            </thead>
            <tbody>
              {draft.lines.map((l) => {
                const changed = (billed[l.orderId] ?? 0) !== (l.lockedQty ?? 0)
                return (
                  <tr key={l.orderId} className="border-t border-stone-100">
                    <td className="px-3 py-1.5">{l.centreName}</td>
                    <td className="text-stone-600">{l.routeName}</td>
                    <td><Slot slot={l.mealSlot} /></td>
                    <td className="text-right tabular-nums">{l.lockedQty}</td>
                    <td className="text-right tabular-nums text-stone-500">{l.receivedQty ?? '—'}</td>
                    <td className="px-3 text-right">
                      <input type="number" min={0} value={billed[l.orderId] ?? 0}
                             onChange={(e) => setBilled({ ...billed, [l.orderId]: Number(e.target.value) })}
                             className={`w-20 rounded-lg border px-2 py-0.5 text-right font-mono tabular-nums ${changed ? 'border-amber-400 bg-amber-50' : 'border-stone-300'}`} />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

/** Where a bill stands, what Ops decided, and why each rupee was cut. */
export function BillStatus({ d }: { d: InvoiceDetail }) {
  const inv = d.invoice
  const cut = inv.billedPaise - inv.payablePaise
  const flagged = d.lines.filter((l) => l.flag !== 'OK')
  return (
    <Card>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="text-xs text-stone-500">Bill #{inv.id} · {day(inv.serviceDate)}</div>
          <div className="mt-1 flex items-center gap-2">
            <span className={`rounded-full px-2.5 py-0.5 text-sm font-semibold ${STATUS_CHIP[inv.status]}`}>{STATUS_TEXT[inv.status]}</span>
          </div>
        </div>
        <div className="grid grid-cols-3 gap-4 text-right">
          <div><div className="text-[11px] text-stone-500">Billed</div><div className="font-bold tabular-nums">{rupees(inv.billedPaise)}</div></div>
          <div><div className="text-[11px] text-stone-500">{inv.status === 'PAID' ? 'Paid' : 'Payable'}</div><div className="font-bold tabular-nums text-brand-700">{rupees(inv.payablePaise)}</div></div>
          <div><div className="text-[11px] text-stone-500">Cut</div><div className={`font-bold tabular-nums ${cut > 0 ? 'text-red-700' : ''}`}>{rupees(Math.max(cut, 0))}</div></div>
        </div>
      </div>

      {inv.status === 'REJECTED' && inv.rejectReason && (
        <div className="mt-3 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-900">
          <b>Ops rejected this bill:</b> “{inv.rejectReason}”
        </div>
      )}
      {inv.paymentRef && (
        <div className="mt-3 rounded-xl bg-brand-50 p-3 text-sm text-brand-900">
          💸 Paid {rupees(inv.payablePaise)} on {dateTimeIST(inv.paidAt!)} · bank reference <span className="font-mono font-semibold">{inv.paymentRef}</span>
        </div>
      )}

      <ol className="mt-4 space-y-2 border-l-2 border-stone-200 pl-4">
        {d.events.map((e) => (
          <li key={e.id} className="text-sm">
            <span className="mr-1">{EVENT_ICON[e.kind]}</span><b>{e.kind.charAt(0) + e.kind.slice(1).toLowerCase()}</b>
            {e.amountPaise != null && <> · {rupees(e.amountPaise)}</>}
            <span className="text-stone-500"> · {e.actorLabel} · {dateTimeIST(e.at)}</span>
            {e.note && <div className="text-xs text-stone-600">{e.note}</div>}
          </li>
        ))}
      </ol>

      {flagged.length > 0 && (
        <div className="mt-4">
          <div className="mb-1 text-xs font-semibold text-stone-600">Lines that differ ({flagged.length})</div>
          <div className="max-h-56 overflow-auto rounded-xl border border-stone-100">
            <table className="w-full text-sm">
              <tbody>
                {flagged.map((l) => (
                  <tr key={l.lineId} className="border-t border-stone-100 first:border-t-0">
                    <td className="px-3 py-1.5">{l.centreName} <Slot slot={l.mealSlot} /></td>
                    <td className="text-right text-xs tabular-nums text-stone-600">billed {l.billedQty} · received {l.receivedQty ?? '—'} · pays {l.payableQty}</td>
                    <td className="px-3">{FLAG_VIEW[l.flag] ?? l.flag}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </Card>
  )
}

/** Last 30 days of this kitchen's bills; click one to see its status and cuts. */
export function BillHistory() {
  const { data, error } = useData(() => api<InvoiceSummary[]>('/api/kitchen/invoices'), [], 15000)
  const [open, setOpen] = useState<number | null>(null)
  const detail = useData(() => (open ? api<InvoiceDetail>(`/api/kitchen/invoices/${open}`) : Promise.resolve(null)), [open])
  if (!data) return error ? <ErrorNote message={error} /> : <Spinner />

  const paid = data.filter((i) => i.status === 'PAID').reduce((s, i) => s + i.payablePaise, 0)
  const pending = data.filter((i) => i.status === 'APPROVED').reduce((s, i) => s + i.payablePaise, 0)
  return (
    <Card>
      <SectionTitle title={`My bills · last 30 days (${data.length})`}
                    hint={`Paid ${rupees(paid)} · approved, payment pending ${rupees(pending)}`} />
      {!data.length ? <p className="text-sm text-stone-500">No bills yet.</p> : (
        <div className="overflow-auto rounded-xl border border-stone-100">
          <table className="w-full text-sm">
            <thead className="bg-stone-50 text-left text-xs text-stone-500">
              <tr><th className="px-3 py-2">Day</th><th className="text-right">Billed</th><th className="text-right">Payable</th><th className="px-3">Status</th><th>Bank ref</th></tr>
            </thead>
            <tbody>
              {data.map((i) => (
                <tr key={i.id} onClick={() => setOpen(open === i.id ? null : i.id)} className="cursor-pointer border-t border-stone-100 hover:bg-stone-50">
                  <td className="px-3 py-2 font-medium">{day(i.serviceDate)}</td>
                  <td className="text-right tabular-nums">{rupees(i.billedPaise)}</td>
                  <td className="text-right font-semibold tabular-nums">{rupees(i.payablePaise)}</td>
                  <td className="px-3"><span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_CHIP[i.status]}`}>{STATUS_TEXT[i.status]}</span></td>
                  <td className="font-mono text-xs text-stone-600">{i.paymentRef ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {open && detail.data && <div className="mt-4"><BillStatus d={detail.data} /></div>}
    </Card>
  )
}
