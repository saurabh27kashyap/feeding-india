import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, post } from './api'
import type { Me } from './types'

interface AuthValue {
  me: Me | null
  login: (phone: string, password: string) => Promise<Me>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)

  // Also fetches the CSRF cookie before anything is posted.
  const refresh = useCallback(async () => setMe(await api<Me>('/api/auth/me')), [])

  useEffect(() => {
    refresh().catch(() => setMe({ authenticated: false }))
  }, [refresh])

  const login = async (phone: string, password: string) => {
    const result = await post<Me>('/api/auth/login', { phone, password })
    setMe(result)
    return result
  }

  const logout = async () => {
    await post('/api/auth/logout')
    await refresh()
  }

  return <AuthContext.Provider value={{ me, login, logout }}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth outside AuthProvider')
  return value
}
