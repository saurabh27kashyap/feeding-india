import { clock12, day } from './api'
import { STEPS } from './story'
import { useTour } from './tour'

/** The day at a glance, with "you are here". */
export default function StoryTimeline() {
  const { demo, started, active, go, busy, dateOf, mode } = useTour()
  if (!demo || !started || mode !== 'guided') return null

  const now = demo.nowIndia
  return (
    <div className="border-b border-stone-200 bg-white/90 backdrop-blur">
      <div className="mx-auto flex max-w-7xl items-center gap-3 overflow-x-auto px-5 py-2">
        <span className="shrink-0 rounded-lg bg-stone-900 px-2.5 py-1 font-mono text-xs text-amber-300">
          🕒 {day(now.slice(0, 10))} · {clock12(now.slice(11, 16))}
        </span>
        <ol className="flex flex-1 items-center gap-1">
          {STEPS.map((s, i) => (
            <li key={s.key} className="flex flex-1 items-center gap-1">
              <button
                disabled={busy}
                onClick={() => go(i)}
                title={s.title}
                className={`flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs transition ${
                  i === active ? 'bg-amber-400 font-semibold text-stone-900 shadow'
                    : i < active ? 'bg-brand-50 text-brand-800 hover:bg-brand-100' : 'text-stone-500 hover:bg-stone-100'}`}>
                <span>{i < active ? '✓' : s.scene.split(' ')[0]}</span>
                <span className="whitespace-nowrap">{day(dateOf(s)).split(' ')[0].replace(',', '')} {clock12(s.when.hhmm)}</span>
              </button>
              {i < STEPS.length - 1 && <span className={`h-px min-w-3 flex-1 ${i < active ? 'bg-brand-400' : 'bg-stone-200'}`} />}
            </li>
          ))}
        </ol>
      </div>
    </div>
  )
}
