import { accessToken } from './auth'

const BASE = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '')
export class ApiError extends Error { constructor(public status: number, message: string) { super(message) } }

let outletId = ''
export const setOutlet = (id: string) => { outletId = id }
const ctx = { onUnauthorized: () => {} }
export const onUnauthorized = (fn: () => void) => { ctx.onUnauthorized = fn }
let deviceToken = ''
export const setDeviceToken = (t: string) => { deviceToken = t }

async function headers(json: boolean): Promise<Record<string, string>> {
  const h: Record<string, string> = {}
  if (json) h['Content-Type'] = 'application/json'
  if (deviceToken) { h['X-Device-Token'] = deviceToken; return h }
  const token = await accessToken()
  if (token) h.Authorization = 'Bearer ' + token
  if (outletId) h['X-Outlet-Id'] = outletId
  return h
}

async function call(method: string, path: string, body?: unknown, query?: Record<string, unknown>) {
  const qs = query ? '?' + new URLSearchParams(Object.entries(query).filter(([, v]) => v !== undefined && v !== null && v !== '' && v !== false).map(([k, v]) => [k, String(v)])) : ''
  let res: Response
  try {
    res = await fetch(BASE + path + (qs === '?' ? '' : qs), { method, headers: await headers(body !== undefined), body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Check your connection and try again.')
  }
  if (res.status === 401 && !deviceToken) ctx.onUnauthorized()
  if (!res.ok) {
    let msg = res.status === 403 ? 'You do not have permission to do that.' : 'Something went wrong (' + res.status + ').'
    try { const j = await res.json(); if (j.error) msg = j.error } catch { /* keep default */ }
    throw new ApiError(res.status, msg)
  }
  return res
}

export const api = {
  async get<T = any>(path: string, query?: Record<string, unknown>): Promise<T> { const r = await call('GET', path, undefined, query); return (r.status === 204 ? null : await r.json()) as T },
  async post<T = any>(path: string, body: unknown = {}): Promise<T> { const r = await call('POST', path, body); const t = await r.text(); return (t ? JSON.parse(t) : null) as T },
  async put<T = any>(path: string, body: unknown): Promise<T> { const r = await call('PUT', path, body); const t = await r.text(); return (t ? JSON.parse(t) : null) as T },
  async patch<T = any>(path: string, body: unknown): Promise<T> { const r = await call('PATCH', path, body); const t = await r.text(); return (t ? JSON.parse(t) : null) as T },
  async del(path: string) { await call('DELETE', path) },
  async blob(path: string, query: Record<string, unknown>) { const r = await call('GET', path, undefined, query); return r.blob() }
}
