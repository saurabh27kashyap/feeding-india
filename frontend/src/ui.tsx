import type { ButtonHTMLAttributes, ReactNode } from 'react'
import type { Status } from './types'

// ---------- surfaces ----------

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-2xl border border-stone-200/80 bg-white p-5 shadow-[0_1px_2px_rgba(16,24,40,0.04),0_4px_16px_rgba(16,24,40,0.04)] ${className}`}>{children}</div>
}

export function SectionTitle({ title, hint, right }: { title: string; hint?: string; right?: ReactNode }) {
  return (
    <div className="mb-3 flex items-start justify-between gap-3">
      <div>
        <h2 className="text-[15px] font-semibold text-stone-900">{title}</h2>
        {hint && <p className="mt-0.5 text-xs leading-relaxed text-stone-500">{hint}</p>}
      </div>
      {right}
    </div>
  )
}

/** A short, plain-language explanation of what a screen is for. */
export function Explainer({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex gap-3 rounded-2xl border border-brand-100 bg-brand-50/70 p-4 text-sm text-brand-900">
      <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-brand-600 text-xs font-bold text-white">i</span>
      <div>
        <div className="font-semibold">{title}</div>
        <div className="mt-0.5 leading-relaxed text-brand-900/80">{children}</div>
      </div>
    </div>
  )
}

// ---------- controls ----------

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost' | 'brand'
const variants: Record<Variant, string> = {
  primary: 'bg-stone-900 text-white hover:bg-stone-800 shadow-sm',
  brand: 'bg-brand-600 text-white hover:bg-brand-700 shadow-sm',
  secondary: 'bg-white text-stone-800 border border-stone-300 hover:bg-stone-50 shadow-sm',
  danger: 'bg-white text-red-700 border border-red-200 hover:bg-red-50',
  ghost: 'text-stone-600 hover:text-stone-900 hover:bg-stone-100',
}

export function Button({ variant = 'primary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      {...props}
      className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-3.5 py-2 text-sm font-medium transition active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-40 ${variants[variant]} ${className}`}
    />
  )
}

export function Stepper({ value, onChange }: { value: number; onChange: (n: number) => void }) {
  return (
    <div className="inline-flex items-center rounded-2xl border border-stone-300 bg-white shadow-sm">
      <button className="h-12 w-12 text-2xl text-stone-600 hover:bg-stone-50 rounded-l-2xl" onClick={() => onChange(Math.max(1, value - 1))}>−</button>
      <input type="number" min={1} value={value} onChange={(e) => onChange(Number(e.target.value))}
             className="h-12 w-20 border-x border-stone-200 text-center text-2xl font-bold tabular-nums outline-none" />
      <button className="h-12 w-12 text-2xl text-stone-600 hover:bg-stone-50 rounded-r-2xl" onClick={() => onChange(value + 1)}>+</button>
    </div>
  )
}

// ---------- status ----------

const statusStyle: Record<Status, string> = {
  REQUESTED: 'bg-sky-50 text-sky-800 ring-sky-200',
  LOCKED: 'bg-amber-50 text-amber-900 ring-amber-200',
  DISPATCHED: 'bg-violet-50 text-violet-800 ring-violet-200',
  RECEIVED: 'bg-brand-50 text-brand-700 ring-brand-200',
  CLOSED: 'bg-stone-100 text-stone-500 ring-stone-200',
}

export const statusLabel: Record<Status, string> = {
  REQUESTED: 'Open',
  LOCKED: 'Locked',
  DISPATCHED: 'On the way',
  RECEIVED: 'Received',
  CLOSED: 'Closed',
}

export function StatusBadge({ status }: { status: Status }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${statusStyle[status]}`}>
      {statusLabel[status]}
    </span>
  )
}

const lifecycle: { status: Status; label: string }[] = [
  { status: 'REQUESTED', label: 'Plan' },
  { status: 'LOCKED', label: 'Locked' },
  { status: 'DISPATCHED', label: 'On the way' },
  { status: 'RECEIVED', label: 'Received' },
]

/** Where a meal is in its life: Plan → Locked → On the way → Received. */
export function Lifecycle({ status }: { status: Status }) {
  if (status === 'CLOSED') {
    return <div className="text-xs text-stone-500">Closed for the day: no meal served</div>
  }
  const current = lifecycle.findIndex((s) => s.status === status)
  return (
    <ol className="flex items-center gap-1">
      {lifecycle.map((s, i) => (
        <li key={s.status} className="flex flex-1 items-center gap-1">
          <div className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[10px] font-bold ${
            i < current ? 'bg-brand-600 text-white' : i === current ? 'bg-stone-900 text-white ring-4 ring-stone-200' : 'bg-stone-200 text-stone-500'}`}>
            {i < current ? '✓' : i + 1}
          </div>
          <span className={`text-[11px] ${i === current ? 'font-semibold text-stone-900' : 'text-stone-500'}`}>{s.label}</span>
          {i < lifecycle.length - 1 && <div className={`h-0.5 flex-1 rounded ${i < current ? 'bg-brand-500' : 'bg-stone-200'}`} />}
        </li>
      ))}
    </ol>
  )
}

type Level = 'good' | 'warning' | 'serious' | 'critical'
const levelIcon: Record<Level, string> = { good: '✓', warning: '!', serious: '▲', critical: '✕' }
const levelColor: Record<Level, string> = {
  good: 'bg-good',
  warning: 'bg-warning',
  serious: 'bg-serious',
  critical: 'bg-critical',
}

/** Status is never color alone: icon + label + color. */
export function Flag({ level, children }: { level: Level; children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-stone-800">
      <span className={`inline-flex h-4 w-4 items-center justify-center rounded-full text-[9px] text-white ${levelColor[level]}`}>
        {levelIcon[level]}
      </span>
      {children}
    </span>
  )
}

export function ErrorNote({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <div className="mt-2 flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-900">
      <span className="mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-critical text-[9px] text-white">✕</span>
      {message}
    </div>
  )
}

export function Slot({ slot }: { slot: string }) {
  const breakfast = slot === 'BREAKFAST'
  return (
    <span className={`inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide ${breakfast ? 'bg-orange-50 text-orange-800' : 'bg-teal-50 text-teal-800'}`}>
      {breakfast ? '☀' : '◐'} {breakfast ? 'Breakfast' : 'Lunch'}
    </span>
  )
}

export function Logo({ small = false }: { small?: boolean }) {
  return (
    <div className="flex items-center gap-2">
      <div className={`flex items-center justify-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 font-black text-white shadow-sm ${small ? 'h-8 w-8 text-sm' : 'h-10 w-10 text-base'}`}>M</div>
      <div className="leading-tight">
        <div className={`font-bold tracking-tight text-stone-900 ${small ? 'text-sm' : 'text-lg'}`}>MealLedger</div>
        {!small && <div className="text-xs text-stone-500">Every child fed, every meal accounted for</div>}
      </div>
    </div>
  )
}

export function Spinner() {
  return <div className="flex justify-center p-10"><div className="h-6 w-6 animate-spin rounded-full border-2 border-stone-300 border-t-brand-600" /></div>
}
