import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import type { DemoState, Role } from '../types'
import { Button, Card, ErrorNote, Logo } from '../ui'

const roleInfo: Record<Role, { title: string; app: string; does: string; color: string; icon: string }> = {
  CENTRE: {
    title: 'Centre worker', app: 'Partner App', icon: '🏫', color: 'from-sky-500 to-sky-700',
    does: 'Sunita: confirms tomorrow’s meal count, reports closures, confirms food arrived, gives feedback.',
  },
  KITCHEN: {
    title: 'Kitchen manager', app: 'Partner App', icon: '🍳', color: 'from-orange-500 to-orange-700',
    does: 'Ramesh: sees the final cooking list per route after the 8 PM cutoff, dispatches vans, bills the day.',
  },
  OPS: {
    title: 'Operations team', app: 'Ops Console', icon: '📊', color: 'from-brand-500 to-brand-700',
    does: 'Saurabh: watches all 2,000 centres at once: missing deliveries, hungry children, waste, bills.',
  },
}

const flow = [
  { n: 1, title: 'Plan', text: 'Tomorrow’s count is suggested from real attendance' },
  { n: 2, title: 'Lock at 8 PM', text: 'Kitchens get the final number' },
  { n: 3, title: 'Deliver', text: 'Route-wise, early morning' },
  { n: 4, title: 'Confirm', text: 'Centre confirms; FRAT counts children' },
  { n: 5, title: 'Reconcile', text: 'Pay for what was actually received' },
]

export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [demo, setDemo] = useState<DemoState | null>(null)

  useEffect(() => {
    api<DemoState>('/api/demo/state').then(setDemo, () => setDemo(null))
  }, [])

  const signIn = async (p: string, pw: string) => {
    setError(null)
    try {
      const me = await login(p, pw)
      navigate(me.role === 'OPS' ? '/ops' : '/partner')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not sign in')
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    signIn(phone, password)
  }

  return (
    <div className="mx-auto grid max-w-6xl gap-10 px-5 pt-12 lg:grid-cols-[1.15fr_1fr]">
      <div>
        <Logo />
        <h1 className="mt-8 text-4xl font-bold leading-tight tracking-tight text-stone-900">
          Daily meals for <span className="text-brand-600">2,000 schools & Anganwadis</span>, tracked without fail.
        </h1>
        <p className="mt-4 max-w-xl text-base leading-relaxed text-stone-600">
          Each day, every centre needs exactly the meals its children will eat. MealLedger plans the count from attendance,
          locks it for the kitchen, tracks each route to the centre, and checks every bill against what was received.
        </p>

        <Link to="/story" className="mt-6 inline-flex items-center gap-2 rounded-xl bg-stone-900 px-4 py-2.5 text-sm font-semibold text-white shadow hover:bg-stone-800">
          ▶ See how it works: a 2-minute story
        </Link>

        <ol className="mt-8 grid gap-3 sm:grid-cols-5">
          {flow.map((s) => (
            <li key={s.n} className="rounded-2xl border border-stone-200 bg-white/70 p-3">
              <div className="flex h-6 w-6 items-center justify-center rounded-full bg-stone-900 text-xs font-bold text-white">{s.n}</div>
              <div className="mt-2 text-sm font-semibold">{s.title}</div>
              <div className="mt-0.5 text-xs leading-snug text-stone-500">{s.text}</div>
            </li>
          ))}
        </ol>
      </div>

      <div className="space-y-4">
        {demo && (
          <Card>
            <div className="mb-1 text-sm font-semibold">Sign in as a demo user</div>
            <p className="mb-4 text-xs text-stone-500">One click. Each role sees a different app.</p>
            <div className="space-y-2.5">
              {demo.users.map((u) => {
                const info = roleInfo[u.role]
                return (
                  <button key={u.phone} onClick={() => signIn(u.phone, demo.password)}
                          className="group flex w-full items-start gap-3 rounded-2xl border border-stone-200 p-3 text-left transition hover:border-stone-300 hover:bg-stone-50 hover:shadow-sm">
                    <div className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br text-xl ${info.color}`}>{info.icon}</div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-semibold">{info.title}</span>
                        <span className="rounded-full bg-stone-100 px-2 py-0.5 text-[10px] font-medium text-stone-600">{info.app}</span>
                      </div>
                      <div className="truncate text-xs text-stone-500">{u.label}</div>
                      <div className="mt-1 text-xs leading-snug text-stone-600">{info.does}</div>
                    </div>
                    <span className="self-center text-stone-300 transition group-hover:translate-x-0.5 group-hover:text-stone-600">→</span>
                  </button>
                )
              })}
            </div>
          </Card>
        )}

        <Card>
          <form onSubmit={submit} className="space-y-3">
            <div className="text-sm font-semibold">Or sign in with phone</div>
            <input className="w-full rounded-xl border border-stone-300 px-3 py-2.5 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-100"
                   placeholder="Phone number" value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="numeric" autoComplete="username" />
            <input className="w-full rounded-xl border border-stone-300 px-3 py-2.5 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-100"
                   placeholder="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
            <ErrorNote message={error} />
            <Button type="submit" variant="primary" className="w-full">Sign in</Button>
            {demo && <p className="text-center text-xs text-stone-400">Demo password: {demo.password}</p>}
          </form>
        </Card>
      </div>
    </div>
  )
}
