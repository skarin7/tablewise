// Report periods and their comparison ranges, ported from the prototype. All values are local-midnight timestamps.
import { startOfDay } from './format'
const DAY = 864e5
export type Mode = 'day' | 'month' | 'quarter' | 'year' | 'custom'
export type Basis = 'fy' | 'cal'
export interface Rep { mode: Mode; anchor: number; basis: Basis; compare: 'prev' | 'yoy' | 'none'; from: string; to: string; preset: string }

export const sod = (t: number) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime() }
export const addDays = (t: number, n: number) => { const d = new Date(t); d.setDate(d.getDate() + n); return d.getTime() }
export const addMonths = (t: number, n: number) => { const d = new Date(t); return new Date(d.getFullYear(), d.getMonth() + n, 1).getTime() }
export const monthStart = (t: number) => { const d = new Date(t); return new Date(d.getFullYear(), d.getMonth(), 1).getTime() }
export const qStart = (t: number) => { const d = new Date(t); return new Date(d.getFullYear(), Math.floor(d.getMonth() / 3) * 3, 1).getTime() }
export const yStart = (t: number, basis: Basis) => { const d = new Date(t); return basis === 'cal' ? new Date(d.getFullYear(), 0, 1).getTime() : new Date(d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1, 3, 1).getTime() }
export const shiftYear = (t: number, n: number) => { const d = new Date(t); d.setFullYear(d.getFullYear() + n); return d.getTime() }
export const dayDiff = (a: number, b: number) => Math.round((b - a) / DAY)
export const ymd = (t: number) => { const d = new Date(t); return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2) }
export function parseYmd(v: string) { const p = String(v || '').split('-'); if (p.length !== 3) return null; const d = new Date(+p[0], +p[1] - 1, +p[2]); return isNaN(d.getTime()) ? null : d.getTime() }
const fyLabel = (y: number) => y + '-' + String(y + 1).slice(2)
export const fmtD = (t: number, o: Intl.DateTimeFormatOptions) => new Date(t).toLocaleDateString('en-IN', o)
export function fmtRange(f: number, t: number) {
  if (f === t) return fmtD(f, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
  const sameY = new Date(f).getFullYear() === new Date(t).getFullYear()
  return fmtD(f, sameY ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' }) + ' – ' + fmtD(t, { day: 'numeric', month: 'short', year: 'numeric' })
}
export const normAnchor = (mode: Mode, t: number, basis: Basis) => mode === 'day' ? sod(t) : mode === 'month' ? monthStart(t) : mode === 'quarter' ? qStart(t) : yStart(t, basis)
export const stepAnchor = (mode: Mode, a: number, n: number) => mode === 'day' ? addDays(a, n) : mode === 'month' ? addMonths(a, n) : mode === 'quarter' ? addMonths(a, 3 * n) : addMonths(a, 12 * n)

export function periodOf(mode: Mode, a: number, basis: Basis) {
  const d = new Date(a), y = d.getFullYear(), m = d.getMonth()
  let end: number, label: string
  if (mode === 'day') { end = a; label = fmtD(a, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) }
  else if (mode === 'month') { end = addDays(addMonths(a, 1), -1); label = fmtD(a, { month: 'long', year: 'numeric' }) }
  else if (mode === 'quarter') {
    end = addDays(addMonths(a, 3), -1)
    const mn = fmtD(a, { month: 'short' }) + '–' + fmtD(end, { month: 'short' })
    label = basis === 'fy' ? 'Q' + (Math.floor(((m + 9) % 12) / 3) + 1) + ' FY ' + fyLabel(m >= 3 ? y : y - 1) + ' (' + mn + ')' : 'Q' + (Math.floor(m / 3) + 1) + ' ' + y + ' (' + mn + ')'
  } else { end = addDays(addMonths(a, 12), -1); label = basis === 'fy' ? 'FY ' + fyLabel(y) + ' (Apr ' + y + ' – Mar ' + (y + 1) + ')' : 'Calendar year ' + y }
  return { from: a, end, label }
}

/** Reports cover at most two years back and never the future (README 9). */
export const histStart = () => shiftYear(startOfDay(0), -2)

export function repRange(r: Rep) {
  const today = startOfDay(0)
  let p: { from: number; end: number; label: string; anchorFrom: number }
  if (r.mode === 'custom') { const f = parseYmd(r.from) ?? addDays(today, -6), t = parseYmd(r.to) ?? today; p = { from: f, end: t, label: 'Custom range', anchorFrom: f } }
  else { const pr = periodOf(r.mode, r.anchor || monthStart(today), r.basis); p = { ...pr, anchorFrom: pr.from } }
  const to = Math.min(p.end, today), hs = histStart(), clipped = p.from < hs, from = Math.max(p.from, hs), empty = to < from
  return { from, to, end: p.end, label: p.label, anchorFrom: p.anchorFrom, partial: p.end > today, clipped, empty, days: empty ? 0 : dayDiff(from, to) + 1 }
}
export type Range = ReturnType<typeof repRange>

export function repCompare(r: Rep, cur: Range) {
  if (r.compare === 'none' || cur.empty) return null
  let f: number, t: number, label: string
  if (r.compare === 'yoy') { f = shiftYear(cur.from, -1); t = shiftYear(cur.to, -1); label = 'the same period last year' }
  else if (r.mode === 'custom') { t = addDays(cur.from, -1); f = addDays(t, -(cur.days - 1)); label = 'the previous ' + cur.days + ' days' }
  else { const pp = periodOf(r.mode, stepAnchor(r.mode, r.anchor, -1), r.basis); f = pp.from; t = Math.min(pp.end, addDays(pp.from, dayDiff(cur.anchorFrom, cur.to))); label = pp.label.replace(/ \(.*\)$/, '') }
  const ff = Math.max(f, histStart())
  return { from: ff, to: t, label, range: t < ff ? '' : fmtRange(ff, t), none: t < ff }
}
/** Chart grain: daily up to 62 days, weekly (Monday start) up to 190, monthly beyond. */
export const grainOf = (days: number) => days <= 1 ? null : days <= 62 ? 'day' : days <= 190 ? 'week' : 'month'

export function applyPreset(r: Rep, p: string) {
  const today = startOfDay(0); r.preset = p
  if (p === 'today') { r.mode = 'day'; r.anchor = today }
  else if (p === 'yesterday') { r.mode = 'day'; r.anchor = addDays(today, -1) }
  else if (p === 'last7' || p === 'last30') { r.mode = 'custom'; r.from = ymd(addDays(today, p === 'last7' ? -6 : -29)); r.to = ymd(today) }
  else if (p === 'thisMonth') { r.mode = 'month'; r.anchor = monthStart(today) }
  else if (p === 'lastMonth') { r.mode = 'month'; r.anchor = addMonths(today, -1) }
  else if (p === 'thisQuarter') { r.mode = 'quarter'; r.anchor = qStart(today) }
  else if (p === 'thisFY') { r.mode = 'year'; r.basis = 'fy'; r.anchor = yStart(today, 'fy') }
}
