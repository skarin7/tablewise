import { expect, test } from 'vitest'
import { canAdvance, canCancel, canPay } from './roles'

const o = (status: string, orderType = 'takeaway', paymentStatus = 'unpaid') => ({ status, orderType, paymentStatus, source: 'pos' })
test('takeaway cannot be handed over unpaid, dine-in can', () => {
  expect(canAdvance(o('ready'), 'cashier')).toMatchObject({ ok: false, unpaid: true })
  expect(canAdvance(o('ready', 'dine_in'), 'cashier').ok).toBe(true)
  expect(canAdvance(o('ready', 'takeaway', 'paid'), 'cashier').ok).toBe(true)
})
test('kitchen can start but not serve; cashier cannot start', () => {
  expect(canAdvance(o('new'), 'kitchen').ok).toBe(true)
  expect(canAdvance(o('ready', 'dine_in'), 'kitchen').ok).toBe(false)
  expect(canAdvance(o('new'), 'cashier').ok).toBe(false)
})
test('only owner cancels a preparing order', () => {
  expect(canCancel(o('preparing'), 'cashier').ok).toBe(false)
  expect(canCancel(o('preparing'), 'owner').ok).toBe(true)
  expect(canCancel(o('new'), 'cashier').ok).toBe(true)
})
test('paid or cancelled orders cannot be paid', () => {
  expect(canPay(o('new', 'dine_in', 'paid'), 'owner').ok).toBe(false)
  expect(canPay(o('cancelled'), 'owner').ok).toBe(false)
  expect(canPay(o('new'), 'kitchen').ok).toBe(false)
})
