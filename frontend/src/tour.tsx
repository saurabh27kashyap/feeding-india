import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, DEMO_EVENT, announceDemoChange, api, post } from './api'
import { useAuth } from './auth'
import { STEPS, type Step } from './story'
import type { DemoState, Role } from './types'

/**
 * The guided story: which step is active, what to highlight, and a plain-language
 * "what just happened" message after every action. Only active in the demo profile.
 */

interface Note {
  text: string
  ok: boolean
  at: number
}

export type DemoMode = 'guided' | 'free'

interface TourValue {
  mode: DemoMode
  setMode: (m: DemoMode) => void
  setClock: (date: string, hhmm: string) => Promise<void>
  demo: DemoState | null
  active: number
  setActive: (i: number) => void
  started: boolean
  busy: boolean
  target?: string
  note: Note | null
  narrate: (text: string, ok?: boolean) => void
  dismiss: () => void
  go: (i: number) => Promise<void>
  run: (says: string, action: () => Promise<unknown>) => Promise<void>
  signInAs: (role: Role) => Promise<void>
  dateOf: (s: Step) => string
  reset: () => Promise<void>
}

const TourContext = createContext<TourValue | null>(null)

export function TourProvider({ children }: { children: ReactNode }) {
  const { me, login, logout } = useAuth()
  const navigate = useNavigate()
  const [demo, setDemo] = useState<DemoState | null>(null)
  const [active, setActiveState] = useState(() => Number(localStorage.getItem('story-step') ?? 0))
  const [started, setStarted] = useState(() => localStorage.getItem('story-started') === 'yes')
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<Note | null>(null)
  const [mode, setModeState] = useState<DemoMode>(() => (localStorage.getItem('demo-mode') as DemoMode) || 'guided')
  const setMode = (m: DemoMode) => {
    setModeState(m)
    localStorage.setItem('demo-mode', m)
  }

  const refresh = useCallback(() => {
    api<DemoState>('/api/demo/state').then(setDemo, () => setDemo(null))
  }, [])

  useEffect(refresh, [me, refresh])
  useEffect(() => {
    window.addEventListener(DEMO_EVENT, refresh)
    return () => window.removeEventListener(DEMO_EVENT, refresh)
  }, [refresh])

  const setActive = (i: number) => {
    setActiveState(i)
    localStorage.setItem('story-step', String(i))
  }

  const narrate = useCallback((text: string, ok = true) => setNote({ text, ok, at: Date.now() }), [])

  const run = async (says: string, action: () => Promise<unknown>) => {
    setBusy(true)
    try {
      await action()
      announceDemoChange()
      narrate(says)
    } catch (e) {
      narrate(e instanceof ApiError ? e.message : String(e), false)
    } finally {
      setBusy(false)
    }
  }

  const signInAs = async (role: Role) => {
    if (!demo) return
    const user = demo.users.find((u) => u.role === role)!
    if (me?.authenticated) await logout()
    const who = await login(user.phone, demo.password)
    navigate(who.role === 'OPS' ? '/ops' : '/partner')
  }

  const dateOf = (s: Step) => (demo ? (s.when.date === 'anchor' ? demo.anchorDay : demo.serviceDay) : '')

  const go = async (i: number) => {
    if (!demo) return
    const s = STEPS[i]
    setActive(i)
    setStarted(true)
    localStorage.setItem('story-started', 'yes')
    setBusy(true)
    try {
      if (!me?.authenticated || me.role !== s.who) await signInAs(s.who)
      await post('/api/demo/clock', { at: `${dateOf(s)}T${s.when.hhmm}:00` })
      announceDemoChange()
      narrate(`Step ${i + 1}: ${s.click}`)
    } catch (e) {
      narrate(e instanceof ApiError ? e.message : String(e), false)
    } finally {
      setBusy(false)
    }
  }

  const setClock = (date: string, hhmm: string) =>
    run(`App time is now ${hhmm} on ${date}. Screens show what happens at that moment.`,
      () => post('/api/demo/clock', { at: `${date}T${hhmm}:00` }))

  const reset = async () => {
    await run('Everything is back to Monday evening. Start again from step 1.', () => post('/api/demo/reset'))
    setActive(0)
  }

  const step = STEPS[active]
  const target = mode === 'guided' && started && demo && me?.role === step?.who ? step.target : undefined

  return (
    <TourContext.Provider value={{
      mode, setMode, setClock,
      demo, active, setActive, started, busy, target, note, narrate, dismiss: () => setNote(null),
      go, run, signInAs, dateOf, reset,
    }}>
      {children}
    </TourContext.Provider>
  )
}

export function useTour(): TourValue {
  const value = useContext(TourContext)
  if (!value) throw new Error('useTour outside TourProvider')
  return value
}

/** Wrap the thing the presenter should click. When it is the current step's target, it glows with a tip. */
export function Tour({ id, tip, children, className = '', below = false }: { id: string; tip: string; children: ReactNode; className?: string; below?: boolean }) {
  const { target } = useTour()
  const on = target === id
  return (
    <div className={`relative ${on ? 'tour-on' : ''} ${className}`}>
      {children}
      {on && (
        <div className={`pointer-events-none absolute left-1/2 z-40 -translate-x-1/2 whitespace-nowrap rounded-lg bg-amber-400 px-2.5 py-1 text-xs font-semibold text-stone-900 shadow-lg ${below ? 'top-full mt-2' : 'bottom-full mb-2'}`}>
          {below ? '👆' : '👇'} {tip}
          <span className={`absolute left-1/2 -translate-x-1/2 border-4 border-transparent ${below ? 'bottom-full border-b-amber-400' : 'top-full border-t-amber-400'}`} />
        </div>
      )}
    </div>
  )
}

/** "What just happened", in plain language, bottom-left. */
export function Narrator() {
  const { note, dismiss } = useTour()
  useEffect(() => {
    if (!note) return
    const t = window.setTimeout(dismiss, 14000)
    return () => window.clearTimeout(t)
  }, [note, dismiss])
  if (!note) return null
  return (
    <div key={note.at} className="fixed bottom-4 left-4 z-50 flex max-w-md animate-[fadein_.25s_ease-out] items-start gap-3 rounded-2xl border border-stone-200 bg-white p-4 shadow-2xl">
      <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-sm text-white ${note.ok ? 'bg-brand-600' : 'bg-critical'}`}>
        {note.ok ? '✓' : '!'}
      </div>
      <div className="flex-1">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-stone-400">{note.ok ? 'What just happened' : 'Not allowed'}</div>
        <div className="mt-0.5 text-sm leading-relaxed text-stone-800">{note.text}</div>
      </div>
      <button className="text-stone-400 hover:text-stone-700" onClick={dismiss}>✕</button>
    </div>
  )
}
