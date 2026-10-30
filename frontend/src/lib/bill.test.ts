import { expect, test } from 'vitest'
import { calc } from './bill'

const st = { gstPercent: 5, serviceChargePercent: 0 }
test('flat discount, GST and round-off match README 7.2', () => {
  const b = calc([{ price: 260, qty: 1 }, { price: 55, qty: 2 }], { type: 'flat', val: 20 }, st)
  expect(b.sub).toBe(370)
  expect(b.disc).toBe(20)
  expect(b.total).toBe(368) // 350 + 17.5 = 367.5 -> 368
  expect(b.round).toBeCloseTo(0.5)
})
test('percent discount is capped at 100 and flat at subtotal', () => {
  expect(calc([{ price: 100, qty: 1 }], { type: 'percent', val: 150 }, st).total).toBe(0)
  expect(calc([{ price: 100, qty: 1 }], { type: 'flat', val: 500 }, st).disc).toBe(100)
})
test('service charge is taxed', () => {
  const b = calc([{ price: 100, qty: 1 }], null, { gstPercent: 5, serviceChargePercent: 10 })
  expect(b.svc).toBeCloseTo(10)
  expect(b.gst).toBeCloseTo(5.5)
  expect(b.total).toBe(116)
})
