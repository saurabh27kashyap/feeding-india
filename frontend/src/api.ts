// Thin fetch wrapper: same-origin session cookie + CSRF header, and typed errors.

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

function cookie(name: string): string | undefined {
  const hit = document.cookie.split('; ').find((c) => c.startsWith(name + '='))
  return hit ? decodeURIComponent(hit.split('=')[1]) : undefined
}

export async function api<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = {}
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    const token = cookie('XSRF-TOKEN')
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  if (res.status === 204) return undefined as T
  const text = await res.text()
  const data = text ? JSON.parse(text) : undefined
  if (!res.ok) {
    const fallback: Record<number, string> = {
      401: 'Please sign in first.',
      403: 'You do not have access to this.',
      404: 'Not found.',
    }
    throw new ApiError(res.status, data?.code ?? `HTTP_${res.status}`,
      data?.detail || fallback[res.status] || `Request failed (HTTP ${res.status}).`)
  }
  return data as T
}

export const post = <T>(path: string, body?: unknown) => api<T>(path, { method: 'POST', body: body ?? {} })
export const put = <T>(path: string, body: unknown) => api<T>(path, { method: 'PUT', body })

// ---------- formatting (all business times are India time) ----------

const IST = 'Asia/Kolkata'

export function timeIST(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-US', { timeZone: IST, hour: 'numeric', minute: '2-digit', hour12: true })
}

export function dateTimeIST(iso: string): string {
  const d = new Date(iso)
  const date = d.toLocaleDateString('en-IN', { timeZone: IST, weekday: 'short', day: 'numeric', month: 'short' })
  return `${date}, ${timeIST(iso)}`
}

/** "19:30" -> "7:30 PM" */
export function clock12(hhmm: string): string {
  const [h, m] = hhmm.split(':').map(Number)
  return `${((h + 11) % 12) + 1}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`
}

export function day(date: string): string {
  // A plain "2026-11-24" date: format it without shifting time zones.
  const [y, m, d] = date.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-IN', {
    timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short',
  })
}

export function rupees(paise: number): string {
  return '₹' + (paise / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function num(n: number): string {
  return n.toLocaleString('en-IN')
}

/** Pages listen for this after demo actions (clock move, simulation) to refetch. */
export const DEMO_EVENT = 'mealledger:demo-changed'
export function announceDemoChange() {
  window.dispatchEvent(new Event(DEMO_EVENT))
}
