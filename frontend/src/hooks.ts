import { useCallback, useEffect, useState } from 'react'
import { DEMO_EVENT } from './api'

/**
 * Load data, reload on demand, on demo changes, and (optionally) every N ms.
 * Polling is enough at this scale: Ops is ~50 people, 1 request per 10 s each.
 */
export function useData<T>(load: () => Promise<T>, deps: unknown[], pollMs?: number) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(() => {
    load().then(
      (d) => {
        setData(d)
        setError(null)
      },
      (e: Error) => setError(e.message),
    )
  }, deps)

  useEffect(() => {
    reload()
    window.addEventListener(DEMO_EVENT, reload)
    // Coming back to a tab (e.g. after acting as another person) shows fresh data at once.
    const onVisible = () => document.visibilityState === 'visible' && reload()
    window.addEventListener('focus', reload)
    document.addEventListener('visibilitychange', onVisible)
    const timer = pollMs ? window.setInterval(reload, pollMs) : undefined
    return () => {
      window.removeEventListener(DEMO_EVENT, reload)
      window.removeEventListener('focus', reload)
      document.removeEventListener('visibilitychange', onVisible)
      if (timer) window.clearInterval(timer)
    }
  }, [reload, pollMs])

  return { data, error, reload }
}
