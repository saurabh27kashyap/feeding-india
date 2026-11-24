import { NavLink, Outlet } from 'react-router'
import { useAuth } from '../../auth'
import { Tour } from '../../tour'
import { Button, Logo } from '../../ui'

const links = [
  { to: '/ops', label: 'Today', end: true, tour: 'nav-today', tip: 'Today' },
  { to: '/ops/invoices', label: 'Invoices', end: false, tour: 'nav-invoices', tip: 'Open the kitchen bills' },
  { to: '/ops/kitchens', label: 'Kitchen scorecard', end: false, tour: 'nav-scorecard', tip: 'Which kitchens need help' },
]

export default function OpsLayout() {
  const { logout } = useAuth()
  return (
    <div>
      <header className="sticky top-0 z-30 mt-0 border-b border-stone-200/80 bg-white/80 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center gap-6 px-5 py-3">
          <Logo small />
          <span className="rounded-full bg-brand-50 px-2.5 py-0.5 text-xs font-semibold text-brand-700 ring-1 ring-inset ring-brand-200">Ops Console · 🧑‍💻 Saurabh, Gurugram office</span>
          <nav className="flex gap-1">
            {links.map((l) => (
              <Tour key={l.to} id={l.tour} tip={l.tip} below>
                <NavLink to={l.to} end={l.end}
                         className={({ isActive }) => `block rounded-lg px-3 py-1.5 text-sm font-medium transition ${isActive ? 'bg-stone-900 text-white' : 'text-stone-600 hover:bg-stone-100 hover:text-stone-900'}`}>
                  {l.label}
                </NavLink>
              </Tour>
            ))}
          </nav>
          <span className="flex-1" />
          <Button variant="ghost" onClick={logout}>Sign out</Button>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-5 pt-6">
        <Outlet />
      </main>
    </div>
  )
}
