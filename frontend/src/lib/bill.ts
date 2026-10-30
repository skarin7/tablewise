// Cart preview only. The server computes the real bill (README 7.2) and ignores these totals.
export interface Totals { sub: number; disc: number; svc: number; gst: number; round: number; total: number; gstPct: number; svcPct: number }
export function calc(lines: { price: number; qty: number }[], disc: { type: 'flat' | 'percent'; val: number } | null, st: { gstPercent: number; serviceChargePercent: number }): Totals {
  const sub = lines.reduce((s, l) => s + l.price * l.qty, 0)
  let d = 0
  if (disc && disc.val > 0) d = disc.type === 'percent' ? (sub * Math.min(disc.val, 100)) / 100 : Math.min(disc.val, sub)
  d = Math.round(d * 100) / 100
  const base = sub - d, svc = (base * st.serviceChargePercent) / 100, gst = ((base + svc) * st.gstPercent) / 100, exact = base + svc + gst, total = Math.round(exact)
  return { sub, disc: d, svc, gst, round: total - exact, total, gstPct: st.gstPercent, svcPct: st.serviceChargePercent }
}
/** Bill block of a board row (server numbers) in the same shape the totals markup uses. */
export function billOf(o: any): Totals {
  const gst = (+o.cgstAmount || 0) + (+o.sgstAmount || 0), taxable = +o.taxableAmount || 0, base = (+o.subtotal || 0) - (+o.discountAmount || 0), svc = +o.serviceChargeAmount || 0
  return { sub: +o.subtotal || 0, disc: +o.discountAmount || 0, svc, gst, round: +o.roundOff || 0, total: +o.total || 0,
    gstPct: taxable ? Math.round((gst / taxable) * 1000) / 10 : 0, svcPct: base ? Math.round((svc / base) * 1000) / 10 : 0 }
}
