export type Role = 'owner' | 'cashier' | 'kitchen'
export const ROLES: Record<Role, { label: string; views: string[]; can: Record<string, number> }> = {
  owner: { label: 'Owner / Admin', views: ['dashboard', 'pos', 'orders', 'online', 'menu', 'inventory', 'customers', 'reports', 'admin'],
    can: { take: 1, start: 1, ready: 1, serve: 1, pay: 1, cancelNew: 1, cancelPrep: 1, prices: 1 } },
  cashier: { label: 'Cashier', views: ['pos', 'orders', 'online', 'customers'], can: { take: 1, serve: 1, pay: 1, cancelNew: 1, prices: 1 } },
  kitchen: { label: 'Kitchen', views: ['orders'], can: { start: 1, ready: 1 } }
}
export const NAV = [
  { id: 'dashboard', label: 'Dashboard', icon: 'grid' }, { id: 'pos', label: 'New order', icon: 'pos' }, { id: 'orders', label: 'Orders', icon: 'orders' },
  { id: 'online', label: 'Online orders', icon: 'globe' }, { id: 'menu', label: 'Menu', icon: 'fork' }, { id: 'inventory', label: 'Inventory', icon: 'box' },
  { id: 'customers', label: 'Customers', icon: 'users' }, { id: 'reports', label: 'Reports', icon: 'chart' }, { id: 'admin', label: 'Admin', icon: 'admin' }
]
// The same table drives the board, the search results, the payment step and the handlers (README 7.1). The server re-checks every one.
const STEP: Record<string, { to: string; perm: string }> = { new: { to: 'preparing', perm: 'start' }, preparing: { to: 'ready', perm: 'ready' }, ready: { to: 'completed', perm: 'serve' } }
import { PLAT, STATUS_LABEL, TYPE } from './format'
export const isOpen = (o: any) => o.status !== 'completed' && o.status !== 'cancelled'
export const isPaid = (o: any) => o.paymentStatus === 'paid'
export const serveLabel = (o: any) => (o.source !== 'pos' ? 'Hand to rider' : o.orderType === 'dine_in' ? 'Mark served' : o.orderType === 'takeaway' ? 'Hand over' : 'Dispatch')
export const stepLabel = (o: any) => (o.status === 'new' ? 'Start preparing' : o.status === 'preparing' ? 'Mark ready' : serveLabel(o))
export function canAdvance(o: any, role: Role) {
  const st = STEP[o.status]
  if (!st) return { ok: false, why: 'This order is already ' + STATUS_LABEL[o.status].toLowerCase() + '.' }
  if (!ROLES[role].can[st.perm]) return { ok: false, why: ROLES[role].label + ' cannot ' + stepLabel(o).toLowerCase() + '.' }
  if (st.perm === 'serve' && o.orderType !== 'dine_in' && !isPaid(o))
    return { ok: false, unpaid: true, why: 'Collect payment before you ' + serveLabel(o).toLowerCase() + ' a ' + TYPE[o.orderType].toLowerCase() + ' order.' }
  return { ok: true }
}
export function canCancel(o: any, role: Role) {
  const c = ROLES[role].can
  if (o.status === 'new') return c.cancelNew ? { ok: true } : { ok: false, why: ROLES[role].label + ' cannot cancel orders.' }
  if (o.status === 'preparing') return c.cancelPrep ? { ok: true } : { ok: false, why: 'Only the owner can cancel an order the kitchen has started.' }
  return { ok: false, why: 'A ' + STATUS_LABEL[o.status].toLowerCase() + ' order cannot be cancelled.' }
}
export function canPay(o: any, role: Role) {
  if (!ROLES[role].can.pay) return { ok: false, why: ROLES[role].label + ' cannot collect payments.' }
  if (o.status === 'cancelled') return { ok: false, why: 'This order was cancelled.' }
  if (isPaid(o)) return { ok: false, why: 'This order is already paid.' }
  return { ok: true }
}
export function waitingNote(o: any, role: Role) {
  const c = ROLES[role].can
  if (o.status === 'new' && !c.start) return 'Waiting for the kitchen'
  if (o.status === 'preparing' && !c.ready) return 'Kitchen is preparing'
  if (o.status === 'ready' && !c.serve) return 'Waiting for pickup'
  return ''
}
export const whereLabel = (o: any) => (o.orderType === 'dine_in' ? 'Table ' + o.tableLabel : o.source !== 'pos' && PLAT[o.source] ? PLAT[o.source].label : TYPE[o.orderType])
