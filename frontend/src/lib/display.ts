import type { Totals } from './bill'

/** What the customer display draws. The tablet gets it from the socket, the cashier's preview builds it locally. */
export interface Screen {
  mode: 'idle' | 'bill' | 'paid' | 'failed'
  name?: string; no?: number
  items?: { n: string; q: number; p: number }[]; bill?: Totals
  method?: string; status?: 'waiting' | 'failed' | 'expired'; qr?: string; expiresAt?: number
  amount?: number; payMode?: string; ref?: string; reason?: string
}

/**
 * Reads /topic/counters/{id}/display as the backend sends it (PaymentService):
 *  idle | failed {reason} | paid {orderNo,total,invoiceNo} | bill {orderNo, items[{name,quantity,lineTotal}], subtotal, discount, serviceCharge, gst, roundOff, total, qr, expiresAt}
 */
export function fromMessage(m: any, restaurant = ''): Screen {
  const name = restaurant
  if (m.state === 'paid') return { mode: 'paid', name, no: m.orderNo, amount: +m.total, ref: m.invoiceNo }
  if (m.state === 'failed') return { mode: 'failed', name, reason: m.reason }
  if (m.state !== 'bill') return { mode: 'idle', name }
  const bill: Totals = { sub: +m.subtotal, disc: +m.discount, svc: +m.serviceCharge, gst: +m.gst, round: +m.roundOff, total: +m.total, gstPct: 0, svcPct: 0 }
  return { mode: 'bill', name, no: m.orderNo, method: 'upi_qr', status: 'waiting', qr: m.qr, expiresAt: m.expiresAt ? new Date(m.expiresAt).getTime() : undefined, bill,
    items: (m.items ?? []).map((i: any) => ({ n: i.name, q: i.quantity, p: +i.lineTotal / (i.quantity || 1) })) }
}
