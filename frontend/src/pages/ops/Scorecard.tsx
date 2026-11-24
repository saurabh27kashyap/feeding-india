import { api } from '../../api'
import { useData } from '../../hooks'
import type { VendorScore } from '../../types'
import { Card, ErrorNote, Explainer, Spinner } from '../../ui'

const pct = (v?: number) => (v == null ? '—' : `${v}%`)

/** Last 7 days per kitchen: partner taps + LLM tags from notes. Worst on-time first. */
export default function Scorecard() {
  const { data, error } = useData(() => api<VendorScore[]>('/api/ops/scorecard'), [], 30000)
  if (!data) return error ? <ErrorNote message={error} /> : <Spinner />

  return (
    <div className="space-y-5">
    <Explainer title="Which kitchens need attention">
      Percentages come from the three taps centres give after each meal. Tag counts come from free-text notes, labelled by
      an LLM (it only labels text; it never touches counts or money). “Vans on time” is measured from dispatch timestamps (facts); the other percentages are what centres tapped (opinions). Worst first; red means below target.
    </Explainer>
    <Card>
      <h1 className="mb-3 text-[15px] font-semibold">Kitchen scorecard · last 7 days</h1>
      <div className="overflow-auto rounded-xl border border-stone-100">
        <table className="w-full text-sm">
          <thead className="bg-stone-50 text-left text-xs text-stone-500">
            <tr>
              <th className="px-3 py-2">Kitchen</th><th className="text-right">Meals</th><th className="text-right">Vans on time</th><th className="text-right">Feedback</th>
              <th className="text-right">On time</th><th className="text-right">Quality OK</th><th className="text-right">Enough food</th>
              <th className="text-right">Shortages</th><th className="text-right">Late</th><th className="text-right">Quality</th>
              <th className="text-right">Hygiene</th><th className="text-right">Quantity</th><th className="text-right">Taste</th>
              <th className="text-right">Untagged</th>
            </tr>
          </thead>
          <tbody>
            {data.map((v) => (
              <tr key={v.vendorId} className="border-t border-stone-100 tabular-nums">
                <td className="px-3 py-2 font-medium">{v.vendorName}</td>
                <td className="text-right">{v.ordersReceived}</td>
                <td className={`text-right ${v.vansOnTimePercent != null && v.vansOnTimePercent < 90 ? 'font-bold text-red-800' : ''}`}>{pct(v.vansOnTimePercent)}</td>
                <td className="text-right">{v.feedbackCount}</td>
                <td className={`text-right ${v.onTimePercent != null && v.onTimePercent < 90 ? 'font-bold text-red-800' : ''}`}>{pct(v.onTimePercent)}</td>
                <td className={`text-right ${v.qualityOkPercent != null && v.qualityOkPercent < 92 ? 'font-bold text-red-800' : ''}`}>{pct(v.qualityOkPercent)}</td>
                <td className="text-right">{pct(v.quantityOkPercent)}</td>
                <td className="text-right">{v.shortages}</td>
                <td className="text-right">{v.lateTags}</td>
                <td className="text-right">{v.qualityTags}</td>
                <td className="text-right">{v.hygieneTags}</td>
                <td className="text-right">{v.quantityTags}</td>
                <td className="text-right">{v.tasteTags}</td>
                <td className="text-right text-stone-500">{v.pendingTags}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
    </div>
  )
}
