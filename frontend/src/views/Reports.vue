<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import BarChart from '../components/BarChart.vue'
import ExportModal from '../components/ExportModal.vue'
import PageHead from '../components/PageHead.vue'
import { api } from '../lib/api'
import { inr, inr2, pctOf, startOfDay } from '../lib/format'
import { applyPreset, dayDiff, fmtD, fmtRange, grainOf, histStart, monthStart, normAnchor, parseYmd, periodOf, repCompare, repRange, stepAnchor, yStart, ymd, type Rep } from '../lib/periods'
import { useUi } from '../stores/ui'

const ui = useUi()
const r = reactive<Rep>({ mode: 'month', anchor: 0, basis: 'fy', compare: 'prev', from: '', to: '', preset: 'thisMonth' })
r.anchor = monthStart(startOfDay(0))
const cur = computed(() => repRange(r)), cmp = computed(() => repCompare(r, cur.value))
const grain = computed(() => (A.value ? (cur.value.days <= 1 ? null : A.value.grain) : grainOf(cur.value.days))) // the server picks the grain with the README 9 thresholds
const A = ref<any>(null), P = ref<any>(null), loading = ref(false)
// Flatten the API's period (totals, channels, series, paymentModes, topItems, categories) into what the tables read.
const norm = (c: any) => ({ from: c.from, to: c.to, grain: c.grain, orders: +c.totals.orders, gross: +c.totals.grossSales, discount: +c.totals.discount, taxable: +c.totals.taxableValue,
  gst: +c.totals.cgst + +c.totals.sgst, total: +c.totals.total, channels: c.channels.map((x: any) => ({ channel: x.channel, orders: +x.orders, sales: +x.total })),
  payments: c.paymentModes.map((x: any) => ({ mode: x.method, amount: +x.amount })), items: c.topItems, cats: c.categories, platform: +c.gst.swiggy.total + +c.gst.zomato.total,
  buckets: c.series.map((b: any) => ({ start: b.date, orders: +b.orders, total: +b.sales })) })

let seq = 0
async function load() {
  if (cur.value.empty) { A.value = P.value = null; return }
  const mine = ++seq; loading.value = true
  try {
    const res = await api.get('/reports/summary', { from: ymd(cur.value.from), to: ymd(cur.value.to), compare: r.compare })
    if (mine === seq) { A.value = norm(res.current); P.value = res.comparison ? norm(res.comparison) : null }
  } catch (e: any) { if (mine === seq) ui.toast(e.message) } finally { if (mine === seq) loading.value = false }
}
watch(() => [cur.value.from, cur.value.to, r.compare], load, { immediate: true })

const CH: Record<string, string> = { dine_in: 'Dine-in', takeaway: 'Takeaway', delivery: 'Delivery', own_delivery: 'Own delivery', swiggy: 'Swiggy', zomato: 'Zomato', website: 'Website' }
const PAY: Record<string, string> = { cash: 'Cash', upi_qr: 'UPI', upi: 'UPI', card: 'Card', payment_link: 'Payment link', platform: 'Online (platform)', unpaid: 'Unpaid' }
const byKey = (a: any[] | undefined, k: string, v: string) => Object.fromEntries((a ?? []).map(x => [x[k], +x[v]]))
const chCur = computed(() => (A.value?.channels ?? []).filter((c: any) => c.orders)), chPrev = computed(() => byKey(P.value?.channels, 'channel', 'sales'))
const payCur = computed(() => (A.value?.payments ?? []).filter((p: any) => +p.amount)), payPrev = computed(() => byKey(P.value?.payments, 'mode', 'amount'))
const items = computed<any[]>(() => A.value?.items ?? [])
const cats = computed<[string, { qty: number; sales: number }][]>(() => (A.value?.cats ?? []).map((c: any) => [c.name, { qty: +c.quantity, sales: +c.sales }]))
const itemTotal = computed(() => cats.value.reduce((t, [, c]) => t + c.sales, 0))
const platform = computed(() => A.value?.platform ?? 0)
const cmpLabel = computed(() => (r.compare === 'yoy' ? 'the same period last year' : `the previous ${cur.value.days} days`))
const cmpRange = computed(() => (P.value ? fmtRange(parseYmd(P.value.from)!, parseYmd(P.value.to)!) : ''))

function bucket(b: any) {
  const t = parseYmd(b.start)!
  if (grain.value === 'week') return { label: fmtD(t, { day: 'numeric', month: 'short' }), full: 'Week of ' + fmtD(t, { day: 'numeric', month: 'short', year: 'numeric' }) }
  if (grain.value === 'month') return { label: fmtD(t, { month: 'short' }), full: fmtD(t, { month: 'long', year: 'numeric' }) }
  return { label: String(new Date(t).getDate()), full: fmtD(t, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) }
}
const buckets = computed(() => (A.value?.buckets ?? []).map((b: any) => ({ ...b, ...bucket(b) })))
const chartData = computed(() => grain.value ? buckets.value.map((b: any) => ({ label: b.label, full: b.full, value: +b.total })) : chCur.value.map((c: any) => ({ label: CH[c.channel] ?? c.channel, value: +c.sales })))
const chart = computed(() => ({ title: grain.value === 'day' ? 'Daily sales' : grain.value === 'week' ? 'Weekly sales' : grain.value === 'month' ? 'Monthly sales' : 'Sales by channel' }))

const avg = (x: any) => (x?.orders ? x.total / x.orders : 0)
function delta(v: number, pv: number | null | undefined, neutral = false) {
  if (r.compare === 'none') return { txt: 'No comparison selected' }
  if (!P.value || pv == null) return { txt: 'No records to compare with' }
  if (!pv) return { txt: 'Nothing in the comparison period' }
  const p = ((v - pv) / pv) * 100, up = p >= 0
  return { pill: (neutral ? '' : up ? 'good' : 'bad'), arrow: `${up ? '▲' : '▼'} ${Math.abs(p).toFixed(1)}%`, txt: 'vs ' + cmpLabel.value }
}
const chg = (v: number, pv?: number) => (!pv ? null : { cls: v >= pv ? 'up' : 'down', txt: `${v >= pv ? '+' : '−'}${Math.abs(((v - pv) / pv) * 100).toFixed(1)}%` })
const kpis = computed(() => !A.value ? [] : [
  { l: 'Net sales', v: inr(A.value.total), d: delta(A.value.total, P.value?.total) }, { l: 'Orders', v: A.value.orders.toLocaleString('en-IN'), d: delta(A.value.orders, P.value?.orders) },
  { l: 'Average bill', v: inr(avg(A.value)), d: delta(avg(A.value), P.value ? avg(P.value) : null) }, { l: 'GST collected', v: inr(A.value.gst), d: delta(A.value.gst, P.value?.gst, true) },
  { l: 'Discounts given', v: inr(A.value.discount), d: delta(A.value.discount, P.value?.discount, true) }])

const mn = computed(() => ymd(histStart())), mx = computed(() => ymd(startOfDay(0)))
function setMode(m: any) { r.preset = ''; if (m === 'custom') { r.from = ymd(cur.value.from); r.to = ymd(cur.value.to) } else r.anchor = normAnchor(m, cur.value.empty ? startOfDay(0) : cur.value.to, r.basis); r.mode = m }
function setBasis(b: any) { r.basis = b; r.preset = ''; if (r.mode === 'year') r.anchor = yStart(cur.value.empty ? startOfDay(0) : cur.value.to, b) }
function step(n: number) { const a = stepAnchor(r.mode, r.anchor, n); if (a > startOfDay(0)) return; r.anchor = a; r.preset = '' }
function setDay(v: string) { const t = parseYmd(v); if (t == null) return; r.anchor = Math.min(Math.max(t, histStart()), startOfDay(0)); r.preset = '' }
function setRange(which: 'from' | 'to', v: string) {
  const t = parseYmd(v); if (t == null) return
  const c = Math.min(Math.max(t, histStart()), startOfDay(0)), f = which === 'from' ? c : parseYmd(r.from)!, to = which === 'to' ? c : parseYmd(r.to)!
  if (f > to) return ui.toast('The start date must be on or before the end date')
  if (dayDiff(f, to) > 730) return ui.toast('Choose a range of 2 years or less')
  r[which] = ymd(c); r.preset = ''
}
const options = computed(() => { const out: { v: number; label: string }[] = []; let a = normAnchor(r.mode, startOfDay(0), r.basis), g = 0; while (periodOf(r.mode, a, r.basis).end >= histStart() && g++ < 60) { out.push({ v: a, label: periodOf(r.mode, a, r.basis).label }); a = stepAnchor(r.mode, a, -1) } return out })
const prevDis = computed(() => periodOf(r.mode, stepAnchor(r.mode, r.anchor, -1), r.basis).end < histStart()), nextDis = computed(() => stepAnchor(r.mode, r.anchor, 1) > startOfDay(0))
const PRESETS = [['today', 'Today'], ['yesterday', 'Yesterday'], ['last7', 'Last 7 days'], ['last30', 'Last 30 days'], ['thisMonth', 'This month'], ['lastMonth', 'Last month'], ['thisQuarter', 'This quarter'], ['thisFY', 'This financial year']]
</script>

<template>
  <PageHead title="Reports" sub="Sales, tax and item reports for any period. Export them for your accountant.">
    <button class="btn primary" :disabled="!A" @click="ui.open(ExportModal, { range: cur, label: cur.label, from: ymd(cur.from), to: ymd(cur.to) })">Export</button>
  </PageHead>
  <div class="stack">
    <div class="card rp">
      <div class="row between wrap">
        <div class="seg" role="group" aria-label="Period type"><button v-for="m in [['day', 'Day'], ['month', 'Month'], ['quarter', 'Quarter'], ['year', 'Year'], ['custom', 'Custom range']]" :key="m[0]" :aria-pressed="r.mode === m[0]" @click="setMode(m[0])">{{ m[1] }}</button></div>
        <div v-if="r.mode === 'quarter' || r.mode === 'year'" class="seg" role="group" aria-label="Year type"><button :aria-pressed="r.basis === 'fy'" @click="setBasis('fy')">Financial year (Apr–Mar)</button><button :aria-pressed="r.basis === 'cal'" @click="setBasis('cal')">Calendar year</button></div>
      </div>
      <div v-if="r.mode === 'custom'" class="row wrap" style="align-items:flex-end">
        <div class="fld"><label for="rf">From</label><input id="rf" type="date" :value="ymd(cur.from)" :min="mn" :max="mx" @change="setRange('from', ($event.target as HTMLInputElement).value)"></div>
        <div class="fld"><label for="rt">To</label><input id="rt" type="date" :value="ymd(cur.to)" :min="mn" :max="mx" @change="setRange('to', ($event.target as HTMLInputElement).value)"></div></div>
      <div v-else class="row"><button class="btn" :aria-label="'Previous ' + r.mode" :disabled="prevDis" @click="step(-1)">‹</button>
        <input v-if="r.mode === 'day'" type="date" :value="ymd(r.anchor)" :min="mn" :max="mx" aria-label="Report date" @change="setDay(($event.target as HTMLInputElement).value)">
        <select v-else :value="r.anchor" :aria-label="'Choose ' + r.mode" @change="r.anchor = +($event.target as HTMLSelectElement).value; r.preset = ''"><option v-for="o in options" :key="o.v" :value="o.v">{{ o.label }}</option></select>
        <button class="btn" :aria-label="'Next ' + r.mode" :disabled="nextDis" @click="step(1)">›</button></div>
      <div class="chips"><button v-for="p in PRESETS" :key="p[0]" class="chip" :aria-pressed="r.preset === p[0]" @click="applyPreset(r, p[0])">{{ p[1] }}</button></div>
      <div><p class="rsum"><b>{{ cur.label }}</b><template v-if="cur.empty"> · no records</template><template v-else> · {{ fmtRange(cur.from, cur.to) }}<template v-if="cur.partial"> · to date</template> · {{ cur.days }} day{{ cur.days === 1 ? '' : 's' }}</template></p>
        <p v-if="cmp && P" class="sub">Compared with {{ cmpLabel }} · {{ cmpRange }}</p>
        <p v-if="cur.clipped" class="sub">Reports go back two years, so this period is shown from {{ fmtD(cur.from, { day: 'numeric', month: 'short', year: 'numeric' }) }}.</p></div>
      <div class="row wrap"><span class="lb">Compare with</span><div class="seg" role="group" aria-label="Compare with"><button v-for="c in [['prev', 'Previous period'], ['yoy', 'Same period last year'], ['none', 'No comparison']]" :key="c[0]" :aria-pressed="r.compare === c[0]" @click="r.compare = c[0] as any">{{ c[1] }}</button></div></div>
    </div>

    <div v-if="cur.empty" class="card"><div class="empty">No sales in this period.</div></div>
    <div v-else-if="!A" class="loading">{{ loading ? 'Loading…' : 'No data' }}</div>
    <template v-else>
      <div class="kpis"><div v-for="k in kpis" :key="k.l" class="card kpi"><span class="l">{{ k.l }}</span><span class="v">{{ k.v }}</span><span class="s"><span v-if="k.d.arrow" class="pill" :class="k.d.pill">{{ k.d.arrow }}</span> {{ k.d.txt }}</span></div></div>
      <div class="card"><div class="row between wrap" style="margin-bottom:10px"><h2>{{ chart.title }}</h2><span class="sub">Including GST{{ grain === 'week' ? '. Weeks start on Monday' : '' }}</span></div><BarChart :data="chartData" /></div>
      <div class="cols2" style="align-items:start">
        <div class="card"><h2>Sales by channel</h2><div class="tw"><table><thead><tr><th>Channel</th><th class="n">Orders</th><th class="n">Sales</th><th>Share</th><th v-if="P" class="n">Change</th></tr></thead><tbody>
          <tr v-for="c in chCur" :key="c.channel"><td>{{ CH[c.channel] ?? c.channel }}</td><td class="n">{{ c.orders.toLocaleString('en-IN') }}</td><td class="n">{{ inr(c.sales) }}</td><td><div class="sb" style="margin:0;width:90px"><i :style="{ width: pctOf(c.sales, A.total) + '%' }"></i></div><span class="sub">{{ pctOf(c.sales, A.total) }}%</span></td>
            <td v-if="P" class="n"><span v-if="chg(+c.sales, chPrev[c.channel])" :class="chg(+c.sales, chPrev[c.channel])!.cls">{{ chg(+c.sales, chPrev[c.channel])!.txt }}</span><span v-else class="sub">–</span></td></tr></tbody></table></div></div>
        <div class="card"><h2>Payment modes</h2><div class="tw"><table><thead><tr><th>Mode</th><th class="n">Amount</th><th>Share</th><th v-if="P" class="n">Change</th></tr></thead><tbody>
          <tr v-for="p in payCur" :key="p.mode"><td>{{ PAY[p.mode] ?? p.mode }}</td><td class="n">{{ inr(p.amount) }}</td><td><div class="sb" style="margin:0;width:90px"><i :style="{ width: pctOf(p.amount, A.total) + '%' }"></i></div><span class="sub">{{ pctOf(p.amount, A.total) }}%</span></td>
            <td v-if="P" class="n"><span v-if="chg(+p.amount, payPrev[p.mode])" :class="chg(+p.amount, payPrev[p.mode])!.cls">{{ chg(+p.amount, payPrev[p.mode])!.txt }}</span><span v-else class="sub">–</span></td></tr></tbody></table></div></div>
        <div class="card"><h2>Sales by category</h2><div class="tw"><table><thead><tr><th>Category</th><th class="n">Items sold</th><th class="n">Sales</th><th>Share</th></tr></thead><tbody>
          <tr v-for="[k, c] in cats" :key="k"><td>{{ k }}</td><td class="n">{{ c.qty.toLocaleString('en-IN') }}</td><td class="n">{{ inr(c.sales) }}</td><td><div class="sb" style="margin:0;width:90px"><i :style="{ width: pctOf(c.sales, itemTotal) + '%' }"></i></div><span class="sub">{{ pctOf(c.sales, itemTotal) }}%</span></td></tr></tbody></table></div><p class="sub" style="margin-top:8px">Menu prices before discount and GST.</p></div>
        <div class="card"><h2>Top 10 dishes</h2><div class="tw"><table><thead><tr><th>Dish</th><th class="n">Qty</th><th class="n">Sales</th></tr></thead><tbody>
          <tr v-for="i in items.slice(0, 10)" :key="i.name"><td>{{ i.name }}</td><td class="n">{{ (+i.quantity).toLocaleString('en-IN') }}</td><td class="n">{{ inr(i.sales) }}</td></tr></tbody></table></div></div>
        <div class="card"><h2>GST summary</h2>
          <div v-for="x in [['Taxable value', A.taxable], ['CGST', A.gst / 2], ['SGST', A.gst / 2], ['Total GST', A.gst], ['Invoice value (incl. GST, rounded)', A.total]]" :key="x[0] as string" class="li"><span>{{ x[0] }}</span><b class="mono">{{ inr2(+x[1]) }}</b></div>
          <p class="sub" style="margin-top:10px">Restaurant GST is split equally between CGST and SGST. Swiggy and Zomato orders worth {{ inr(platform) }} in this period usually have their GST paid by the platform, so check with your accountant before filing.</p></div>
      </div>
      <details v-if="grain" class="card"><summary><h2>Breakdown by {{ grain }}</h2></summary><div class="tw" style="margin-top:12px"><table><thead><tr><th>{{ grain === 'day' ? 'Date' : grain === 'week' ? 'Week' : 'Month' }}</th><th class="n">Orders</th><th class="n">Total</th></tr></thead><tbody>
        <tr v-for="b in buckets" :key="b.start"><td>{{ b.full }}</td><td class="n">{{ b.orders.toLocaleString('en-IN') }}</td><td class="n">{{ inr(b.total) }}</td></tr>
        <tr><td><b>Total</b></td><td class="n"><b>{{ A.orders.toLocaleString('en-IN') }}</b></td><td class="n"><b>{{ inr(A.total) }}</b></td></tr></tbody></table></div></details>
    </template>
  </div>
</template>
