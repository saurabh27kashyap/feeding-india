import { Link } from 'react-router'
import { clock12, day } from '../api'
import { PEOPLE, PROBLEMS, STEPS, personOf } from '../story'
import { useTour } from '../tour'
import { Button, Card, Logo } from '../ui'

const problemTone: Record<string, string> = {
  'Food waste': 'bg-orange-50 text-orange-800',
  'Missed centres': 'bg-red-50 text-red-800',
  'Wrong bills': 'bg-violet-50 text-violet-800',
  Quality: 'bg-sky-50 text-sky-800',
}

/** The whole system as a picture story: who, what goes wrong, and one day step by step. */
export default function Story() {
  const { demo, go, busy, dateOf } = useTour()

  return (
    <div className="mx-auto max-w-6xl px-5 pb-40 pt-8">
      <div className="flex items-center justify-between">
        <Logo />
        <Link to="/login" className="text-sm font-medium text-stone-600 hover:text-stone-900">Sign in →</Link>
      </div>

      <div className="mt-10 max-w-3xl">
        <div className="text-sm font-semibold uppercase tracking-wider text-brand-600">How it works · 2-minute story</div>
        <h1 className="mt-2 text-4xl font-bold leading-tight tracking-tight">
          Every day, 1.7 lakh children in 2,000 schools and Anganwadis get a free meal.
        </h1>
        <p className="mt-3 text-lg text-stone-600">
          The meals are paid for by people who donate on Zomato and Blinkit. Here is how one meal gets from a kitchen to a child, and how MealLedger makes sure it happens <b>every single day</b>.
        </p>
      </div>

      <h2 className="mt-10 text-lg font-bold">Meet the three people</h2>
      <div className="mt-3 grid gap-4 md:grid-cols-3">
        {PEOPLE.map((p) => (
          <Card key={p.role} className="flex gap-4">
            <div className="flex h-16 w-16 shrink-0 items-center justify-center rounded-2xl bg-stone-100 text-4xl">{p.emoji}</div>
            <div>
              <div className="text-lg font-bold">{p.name}</div>
              <div className="text-sm text-stone-700">{p.job}</div>
              <div className="mt-1 text-xs text-stone-500">📍 {p.where} · uses the <b>{p.app}</b></div>
            </div>
          </Card>
        ))}
      </div>

      <h2 className="mt-10 text-lg font-bold">What goes wrong without a system</h2>
      <div className="mt-3 grid gap-4 md:grid-cols-3">
        {PROBLEMS.map((p) => (
          <div key={p.title} className="rounded-2xl border border-red-100 bg-red-50/50 p-4">
            <div className="text-2xl">{p.emoji}</div>
            <div className="mt-1 font-semibold">{p.title}</div>
            <div className="text-sm text-stone-600">{p.text}</div>
          </div>
        ))}
      </div>

      <h2 className="mt-12 text-lg font-bold">One day, step by step</h2>
      <p className="text-sm text-stone-500">Press “Try it” on any step: the app jumps to that time and signs you in as the right person.</p>
      <ol className="mt-4 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {STEPS.map((s, i) => {
          const p = personOf(s.who)
          return (
            <li key={s.key}>
              <Card className="flex h-full flex-col">
                <div className="flex items-center justify-between">
                  <span className="flex h-7 w-7 items-center justify-center rounded-full bg-stone-900 text-xs font-bold text-white">{i + 1}</span>
                  <span className="text-sm font-semibold text-stone-700">
                    {demo ? day(dateOf(s)) + ' · ' : ''}{clock12(s.when.hhmm)}
                  </span>
                </div>
                <div className="my-4 rounded-2xl bg-gradient-to-br from-stone-50 to-stone-100 py-6 text-center text-5xl tracking-[0.3em]">{s.scene}</div>
                <div className="text-base font-bold">{s.title}</div>
                <p className="mt-1 flex-1 text-sm leading-relaxed text-stone-600">{s.story}</p>
                <div className="mt-3 flex items-center justify-between gap-2">
                  <span className="text-xs text-stone-500">{p.emoji} {p.name}</span>
                  <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${problemTone[s.problem]}`}>Solves: {s.problem}</span>
                </div>
                {demo && (
                  <Button variant="secondary" className="mt-3 w-full" disabled={busy} onClick={() => go(i)}>
                    Try it ▶
                  </Button>
                )}
              </Card>
            </li>
          )
        })}
      </ol>

      {demo && (
        <div className="mt-10 flex justify-center">
          <Button variant="brand" className="!px-6 !py-3 text-base" disabled={busy} onClick={() => go(0)}>
            Start the live demo from step 1 ▶
          </Button>
        </div>
      )}
    </div>
  )
}
