import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { clock12, day, post } from './api'
import { useAuth } from './auth'
import { STEPS, personOf } from './story'
import { useTour } from './tour'
import type { Role } from './types'

/**
 * Demo guide: the current step of the story, what to click, why it works that way,
 * and one-click controls. Only shown when the backend runs the "demo" profile.
 */
export default function DemoBar() {
  const { me } = useAuth()
  const { demo, active, setActive, go, run, busy, signInAs, reset, dateOf, mode, setMode } = useTour()
  const [open, setOpen] = useState(() => localStorage.getItem('demo-open') !== 'no')
  const [showWhy, setShowWhy] = useState(false)

  if (!demo) return null
  const toggle = (v: boolean) => {
    setOpen(v)
    localStorage.setItem('demo-open', v ? 'yes' : 'no')
  }

  const s = STEPS[active]
  const p = personOf(s.who)
  const now = demo.nowIndia
  const clock = `${day(now.slice(0, 10))} · ${clock12(now.slice(11, 16))}`

  if (!open) {
    return (
      <button onClick={() => toggle(true)}
              className="fixed bottom-4 right-4 z-50 flex items-center gap-2 rounded-full bg-stone-900 px-4 py-2.5 text-sm font-medium text-white shadow-xl hover:bg-stone-800">
        <span className="rounded bg-amber-400 px-1.5 text-[10px] font-black text-stone-900">STORY</span>
        Step {active + 1}/{STEPS.length} · {clock}
      </button>
    )
  }

  return (
    <aside className="fixed bottom-4 right-4 z-50 flex max-h-[calc(100vh-2rem)] w-[400px] flex-col overflow-hidden rounded-2xl border border-stone-700 bg-stone-900 text-stone-100 shadow-2xl">
      <div className="flex items-center justify-between border-b border-stone-700 px-4 py-3">
        <div>
          <div className="flex items-center gap-2 text-sm font-semibold">
            <span className="rounded bg-amber-400 px-1.5 text-[10px] font-black text-stone-900">STORY</span>
            {mode === 'guided' ? <>One school-meal day · step {active + 1} of {STEPS.length}</> : 'Free demo controls'}
          </div>
          <div className="mt-0.5 font-mono text-xs text-amber-200">🕒 App time: {clock}</div>
        </div>
        <button className="rounded-lg px-2 py-1 text-stone-400 hover:bg-stone-800 hover:text-white" onClick={() => toggle(false)}>—</button>
      </div>

      <div className="flex gap-1 border-b border-stone-700 p-2">
        {(['guided', 'free'] as const).map((m) => (
          <button key={m} onClick={() => setMode(m)}
                  className={`flex-1 rounded-lg px-2 py-1.5 text-xs font-semibold ${mode === m ? 'bg-amber-400 text-stone-900' : 'text-stone-300 hover:bg-stone-800'}`}>
            {m === 'guided' ? '📖 Guided story' : '🎛️ Free demo'}
          </button>
        ))}
      </div>

      {mode === 'free' ? <FreePanel /> : (
      <div className="flex-1 space-y-3 overflow-auto p-4">
        <div className="flex items-center gap-3">
          <div className="text-3xl tracking-widest">{s.scene}</div>
        </div>
        <div>
          <div className="text-xs text-stone-400">{day(dateOf(s))} · {clock12(s.when.hhmm)} · {p.emoji} {p.name}</div>
          <div className="text-lg font-bold">{s.title}</div>
          <p className="mt-1 text-sm leading-relaxed text-stone-300">{s.story}</p>
        </div>

        <Box tone="amber" label="👆 What to click">{s.click}</Box>

        <button className="text-xs font-medium text-sky-300 hover:text-sky-200" onClick={() => setShowWhy(!showWhy)}>
          {showWhy ? '▾' : '▸'} Why does it work this way? (business logic)
        </button>
        {showWhy && <Box tone="sky" label={`Solves: ${s.problem}`}>{s.why}</Box>}

        <div className="flex flex-wrap gap-2">
          <Btn primary disabled={busy} onClick={() => go(active)}>
            ▶ Go to {clock12(s.when.hhmm)} as {p.name}
          </Btn>
          {s.extras?.map((x) => (
            <Btn key={x.label} disabled={busy} onClick={() => run(x.says, () => x.run(demo))}>{x.label}</Btn>
          ))}
        </div>

        <div className="flex items-center justify-between pt-1">
          <button className="text-xs text-stone-400 hover:text-white disabled:opacity-30" disabled={active === 0} onClick={() => setActive(active - 1)}>← Previous</button>
          <div className="flex gap-1">
            {STEPS.map((_, i) => (
              <button key={i} onClick={() => setActive(i)} className={`h-2 w-2 rounded-full ${i === active ? 'bg-amber-400' : i < active ? 'bg-brand-500' : 'bg-stone-600'}`} />
            ))}
          </div>
          <button className="text-xs text-stone-400 hover:text-white disabled:opacity-30" disabled={active === STEPS.length - 1} onClick={() => go(active + 1)}>Next step →</button>
        </div>
      </div>
      )}

      <div className="flex flex-wrap items-center gap-1.5 border-t border-stone-700 p-3 text-[11px] text-stone-400">
        Be:
        {(['CENTRE', 'KITCHEN', 'OPS'] as Role[]).map((r) => (
          <Btn key={r} disabled={busy || me?.role === r} onClick={() => signInAs(r)}>{personOf(r).emoji} {personOf(r).name}</Btn>
        ))}
        <span className="flex-1" />
        <Link to="/story" className="rounded-lg border border-stone-600 px-2 py-1.5 text-stone-200 hover:bg-stone-700">Story</Link>
        <Btn disabled={busy} onClick={() => window.confirm('Start the story again from Monday evening?') && reset()}>Restart</Btn>
      </div>
    </aside>
  )
}

function Box({ tone, label, children }: { tone: 'amber' | 'sky'; label: string; children: ReactNode }) {
  const t = tone === 'amber' ? 'border-amber-400/40 bg-amber-400/10 text-amber-50' : 'border-sky-400/40 bg-sky-400/10 text-sky-50'
  return (
    <div className={`rounded-xl border p-3 text-sm leading-relaxed ${t}`}>
      <div className="mb-1 text-[11px] font-semibold uppercase tracking-wide opacity-80">{label}</div>
      {children}
    </div>
  )
}

function Btn({ children, onClick, disabled, primary = false }: { children: ReactNode; onClick: () => void; disabled?: boolean; primary?: boolean }) {
  return (
    <button onClick={onClick} disabled={disabled}
            className={`rounded-lg px-2.5 py-1.5 text-xs font-medium transition disabled:opacity-40 ${primary ? 'bg-amber-400 text-stone-900 hover:bg-amber-300' : 'border border-stone-600 text-stone-200 hover:bg-stone-700'}`}>
      {children}
    </button>
  )
}

/** Free demo: jump to any moment of the day, be anyone, run the rest of the program. */
function FreePanel() {
  const { me } = useAuth()
  const { demo, busy, run, setClock, signInAs } = useTour()
  if (!demo) return null
  const plan = demo.anchorDay
  const serve = demo.serviceDay
  const moments: { label: string; date: string; hhmm: string; hint: string }[] = [
    { label: '7:30 PM', date: plan, hhmm: '19:30', hint: 'Centres plan tomorrow' },
    { label: '8:01 PM', date: plan, hhmm: '20:01', hint: 'Counts locked' },
    { label: '6:15 AM', date: serve, hhmm: '06:15', hint: 'Vans leave' },
    { label: '8:30 AM', date: serve, hhmm: '08:30', hint: 'Food arrives' },
    { label: '10:00 AM', date: serve, hhmm: '10:00', hint: 'Breakfast deadline passed' },
    { label: '2:30 PM', date: serve, hhmm: '14:30', hint: 'Lunch done, bills' },
  ]
  const sims: { label: string; says: string; path: string; body: object }[] = [
    { label: 'Other centres: breakfast', says: 'The other centres had breakfast: vans left (a few late, two never), most centres confirmed.', path: '/api/demo/simulate/morning', body: { date: serve, mealSlot: 'BREAKFAST' } },
    { label: 'Other centres: lunch', says: 'Lunch happened at all centres, Ramesh’s vans included.', path: '/api/demo/simulate/morning', body: { date: serve, mealSlot: 'LUNCH' } },
    { label: 'Other kitchens: bills', says: '19 kitchens sent their bills to Saurabh.', path: '/api/demo/simulate/invoices', body: { date: serve } },
    { label: 'FRAT: 31 children at Sunita’s', says: 'FRAT counted 31 children at Sunita’s breakfast.', path: '/api/demo/frat', body: { date: serve, mealSlot: 'BREAKFAST', present: 31 } },
  ]
  return (
    <div className="flex-1 space-y-4 overflow-auto p-4 text-sm">
      <div>
        <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-stone-400">1 · Be someone</div>
        <div className="grid grid-cols-3 gap-1.5">
          {(['CENTRE', 'KITCHEN', 'OPS'] as Role[]).map((r) => (
            <Btn key={r} primary={me?.role === r} disabled={busy || me?.role === r} onClick={() => signInAs(r)}>
              {personOf(r).emoji} {personOf(r).name}
            </Btn>
          ))}
        </div>
      </div>
      <div>
        <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-stone-400">2 · Set the time</div>
        <div className="grid grid-cols-2 gap-1.5">
          {moments.map((m) => (
            <button key={m.label} disabled={busy} onClick={() => setClock(m.date, m.hhmm)}
                    className="rounded-lg border border-stone-600 px-2 py-1.5 text-left hover:bg-stone-700 disabled:opacity-40">
              <div className="text-xs font-semibold">{day(m.date).split(',')[0]} {m.label}</div>
              <div className="text-[10px] text-stone-400">{m.hint}</div>
            </button>
          ))}
        </div>
        <p className="mt-1.5 text-[10px] leading-snug text-stone-500">Moving back in time does not unlock orders that are already locked, just like real life. Use Restart to replay from the start.</p>
      </div>
      <div>
        <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-stone-400">3 · Run the rest of the program</div>
        <div className="grid grid-cols-2 gap-1.5">
          {sims.map((x) => <Btn key={x.label} disabled={busy} onClick={() => run(x.says, () => post(x.path, x.body))}>{x.label}</Btn>)}
        </div>
      </div>
    </div>
  )
}
