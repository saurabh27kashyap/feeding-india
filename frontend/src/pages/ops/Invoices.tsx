import { useState } from 'react'
import { Link } from 'react-router'
import { api, dateTimeIST, day, num, rupees } from '../../api'
import { useData } from '../../hooks'
import type { InvoiceSummary } from '../../types'
import { Card, ErrorNote, Explainer, Flag, SectionTitle, Spinner } from '../../ui'

const statusChip: Record<InvoiceSummary['status'], string> = {
  SUBMITTED: 'bg-amber-50 text-amber-800',
  REJECTED: 'bg-red-50 text-red-800',
  APPROVED: 'bg-sky-50 text-sky-800',
  PAID: 'bg-brand-50 text-brand-700',
}
const statusText: Record<InvoiceSummary['status'], string> = {
  SUBMITTED: 'To review', REJECTED: 'Sent back', APPROVED: 'Approved · to pay', PAID: 'Paid',
}

function isoDaysAgo(today: string, n: number) {
  const d = new Date(today + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() - n)
  return d.toISOString().slice(0, 10)
}

/** Bills: review today's, then pay; and the full payment history. */
export default function Invoices() {
  const [tab, setTab] = useState<'today' | 'history'>('today')
  return (
    <div className="space-y-5">
      <Explainer title="Review → approve → pay">
        Kitchens send one bill per day. For every centre-meal: <b>payable = min(billed, received, ordered)</b>. Approval waits until every
        delivery is confirmed, and freezes the numbers. Finance then pays and records the bank reference (UTR). One UTR can pay only one bill.
      </Explainer>
      <div className="inline-flex rounded-xl border border-stone-200 bg-white p-1 shadow-sm">
        {(['today', 'history'] as const).map((t) => (
          <button key={t} onClick={() => setTab(t)}
                  className={`rounded-lg px-4 py-1.5 text-sm font-medium ${tab === t ? 'bg-stone-900 text-white' : 'text-stone-600 hover:text-stone-900'}`}>
            {t === 'today' ? "Today's bills" : 'Payment history'}
          </button>
        ))}
      </div>
      {tab === 'today' ? <Today /> : <History />}
    </div>
  )
}

function Today() {
  const { data, error } = useData(() => api<InvoiceSummary[]>('/api/ops/invoices'), [], 5000)
  if (!data) return error ? <ErrorNote message={error} /> : <Spinner />
  const total = data.reduce((s, i) => s + i.payablePaise, 0)
  return (
    <Card>
      <SectionTitle title={`Today's bills (${data.length})`} hint={`Total payable ${rupees(total)}`} />
      <InvoiceTable rows={data} empty="No bills yet. Kitchens bill after the day starts." />
    </Card>
  )
}

function History() {
  const today = new Date().toISOString().slice(0, 10)
  const [from, setFrom] = useState(isoDaysAgo(today, 30))
  const [to, setTo] = useState(today)
  const { data, error } = useData(() => api<InvoiceSummary[]>(`/api/ops/invoices?from=${from}&to=${to}`), [from, to])

  const sum = (st: InvoiceSummary['status']) => (data ?? []).filter((i) => i.status === st).reduce((s, i) => s + i.payablePaise, 0)
  return (
    <div className="space-y-4">
      <Card className="flex flex-wrap items-end gap-4">
        <label className="text-xs text-stone-500">From<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="mt-1 block rounded-lg border border-stone-300 px-2 py-1 text-sm text-stone-900" /></label>
        <label className="text-xs text-stone-500">To<input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="mt-1 block rounded-lg border border-stone-300 px-2 py-1 text-sm text-stone-900" /></label>
        <span className="flex-1" />
        <Total label="Paid" value={rupees(sum('PAID'))} tone="text-brand-700" />
        <Total label="Approved, not paid" value={rupees(sum('APPROVED'))} tone="text-sky-700" />
        <Total label="Waiting for review" value={rupees(sum('SUBMITTED'))} tone="text-amber-700" />
      </Card>
      {!data ? (error ? <ErrorNote message={error} /> : <Spinner />) : (
        <Card><InvoiceTable rows={data} empty="No bills in this range." showPayment /></Card>
      )}
    </div>
  )
}

function Total({ label, value, tone }: { label: string; value: string; tone: string }) {
  return <div><div className="text-[11px] text-stone-500">{label}</div><div className={`text-lg font-bold tabular-nums ${tone}`}>{value}</div></div>
}

function InvoiceTable({ rows, empty, showPayment = false }: { rows: InvoiceSummary[]; empty: string; showPayment?: boolean }) {
  if (!rows.length) return <p className="text-sm text-stone-500">{empty}</p>
  return (
    <div className="overflow-auto rounded-xl border border-stone-100">
      <table className="w-full text-sm">
        <thead className="bg-stone-50 text-left text-xs text-stone-500">
          <tr><th className="px-3 py-2">Kitchen</th><th>Day</th><th className="text-right">Billed</th><th className="text-right">Payable</th>
            <th className="text-right">Amount</th><th className="pl-5">Check</th><th className="px-3">Status</th>{showPayment && <th className="px-3">Paid</th>}</tr>
        </thead>
        <tbody>
          {rows.map((i) => (
            <tr key={i.id} className="border-t border-stone-100 hover:bg-stone-50/60">
              <td className="px-3 py-2"><Link className="font-medium hover:underline" to={`/ops/invoices/${i.id}`}>{i.vendorName}</Link></td>
              <td className="text-stone-600">{day(i.serviceDate)}</td>
              <td className="text-right tabular-nums">{num(i.billedMeals)}</td>
              <td className="text-right tabular-nums">{num(i.payableMeals)}</td>
              <td className="text-right font-medium tabular-nums">{rupees(i.payablePaise)}</td>
              <td className="pl-5">
                {i.unconfirmedLines > 0 ? <Flag level="serious">{i.unconfirmedLines} unconfirmed</Flag>
                  : i.flaggedLines > 0 ? <Flag level="warning">{i.flaggedLines} flagged</Flag>
                  : <Flag level="good">Matches</Flag>}
              </td>
              <td className="px-3"><span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ${statusChip[i.status]}`}>{statusText[i.status]}</span></td>
              {showPayment && <td className="px-3 text-xs text-stone-600">{i.paidAt ? <>{dateTimeIST(i.paidAt)}<br /><span className="font-mono">{i.paymentRef}</span></> : '—'}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
