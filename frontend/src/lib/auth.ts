import { supabase } from './supabase'

// Local development (docker compose) signs in with the backend's `local` profile, which accepts "Bearer dev-<staff user uuid>".
// Never build with VITE_LOCAL_DEV=true for production.
export const localDev = import.meta.env.VITE_LOCAL_DEV === 'true'
export const DEV_USERS = [
  { label: 'Owner / Admin', id: '00000000-0000-0000-0000-0000000000a1' },
  { label: 'Cashier', id: '00000000-0000-0000-0000-0000000000a2' },
  { label: 'Kitchen', id: '00000000-0000-0000-0000-0000000000a3' }
]
const KEY = 'tw-dev-user'
const store = { get: () => { try { return sessionStorage.getItem(KEY) } catch { return null } }, set: (v: string | null) => { try { v ? sessionStorage.setItem(KEY, v) : sessionStorage.removeItem(KEY) } catch { /* storage blocked */ } } }

export async function accessToken(): Promise<string | null> {
  if (localDev) { const id = store.get(); return id ? 'dev-' + id : null }
  return (await supabase.auth.getSession()).data.session?.access_token ?? null
}
export const signInLocal = (id: string) => store.set(id)
export async function endSession() { if (localDev) store.set(null); else await supabase.auth.signOut() }
