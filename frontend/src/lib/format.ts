const DAY = 864e5
export const inr = (n: number) => '₹' + Math.round(n || 0).toLocaleString('en-IN')
export const inr2 = (n: number) => '₹' + (n || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
export const num = (n: number) => (Math.round((+n || 0) * 100) / 100).toLocaleString('en-IN')
export const ts = (t: string | number | Date) => new Date(t).getTime()
export const startOfDay = (back = 0) => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime() - back * DAY }
export const fmtTime = (t: string | number) => new Date(t).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' })
export function fmtDay(t: string | number) {
  const s = startOfDay(0), x = ts(t)
  if (x >= s) return 'Today, ' + fmtTime(x)
  if (x >= s - DAY) return 'Yesterday, ' + fmtTime(x)
  return new Date(x).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })
}
export function elapsed(t: string | number, now = Date.now()) {
  const m = Math.max(0, Math.round((now - ts(t)) / 60000))
  return m >= 60 ? Math.floor(m / 60) + 'h ' + (m % 60) + 'm' : m + ' min'
}
export const cdown = (ms: number) => Math.floor(ms / 60000) + ':' + ('0' + (Math.floor(ms / 1000) % 60)).slice(-2)
export const validPhone = (p: string) => /^\d{10}$/.test(String(p || '').replace(/\D/g, ''))
export const compact = (v: number) => v >= 1e5 ? '₹' + Math.round(v / 1e4) / 10 + 'L' : v >= 1e3 ? '₹' + Math.round(v / 100) / 10 + 'k' : '₹' + Math.round(v)
export const niceStep = (raw: number) => { const p = Math.pow(10, Math.floor(Math.log10(raw))); for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= raw) return m * p; return 10 * p }
export const pctOf = (a: number, b: number) => (b ? Math.round((a / b) * 1000) / 10 : 0)
export const TYPE: Record<string, string> = { dine_in: 'Dine-in', takeaway: 'Takeaway', delivery: 'Delivery' }
export const PLAT: Record<string, { label: string; cls: string }> = { swiggy: { label: 'Swiggy', cls: 'warn' }, zomato: { label: 'Zomato', cls: 'bad' }, website: { label: 'Website', cls: 'info' } }
export const STATUS_LABEL: Record<string, string> = { new: 'New', preparing: 'Preparing', ready: 'Ready', completed: 'Completed', cancelled: 'Cancelled' }
export const STATUS_PILL: Record<string, string> = { new: 'info', preparing: 'warn', ready: 'good', completed: '', cancelled: 'bad' }
