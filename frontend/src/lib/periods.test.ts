import { expect, test } from 'vitest'
import { dayDiff, grainOf, normAnchor, periodOf, repCompare, repRange, type Rep, ymd } from './periods'

const rep = (o: Partial<Rep>): Rep => ({ mode: 'month', anchor: 0, basis: 'fy', compare: 'prev', from: '', to: '', preset: '', ...o })
const t = (y: number, m: number, d: number) => new Date(y, m - 1, d).getTime()

test('financial quarter and year labels run April to March', () => {
  expect(periodOf('quarter', t(2026, 1, 1), 'fy').label).toBe('Q4 FY 2025-26 (Jan–Mar)')
  expect(periodOf('year', normAnchor('year', t(2026, 2, 10), 'fy'), 'fy').label).toContain('FY 2025-26')
  expect(periodOf('year', t(2026, 1, 1), 'cal').label).toBe('Calendar year 2026')
})
test('a finished month compares with the whole previous month', () => {
  const r = rep({ anchor: t(2026, 1, 1) }), cur = repRange(r), cmp = repCompare(r, cur)!
  expect(ymd(cur.from)).toBe('2026-01-01'); expect(ymd(cur.to)).toBe('2026-01-31')
  expect(ymd(cmp.from)).toBe('2025-12-01'); expect(ymd(cmp.to)).toBe('2025-12-31')
})
test('custom range compares with the same number of days before it', () => {
  const r = rep({ mode: 'custom', from: '2026-03-10', to: '2026-03-16', compare: 'prev' }), cur = repRange(r), cmp = repCompare(r, cur)!
  expect(cur.days).toBe(7); expect(ymd(cmp.from)).toBe('2026-03-03'); expect(ymd(cmp.to)).toBe('2026-03-09')
})
test('chart grain switches at 62 and 190 days', () => {
  expect([1, 2, 62, 63, 190, 191].map(grainOf)).toEqual([null, 'day', 'day', 'week', 'week', 'month'])
  expect(dayDiff(t(2026, 1, 1), t(2026, 1, 8))).toBe(7)
})
