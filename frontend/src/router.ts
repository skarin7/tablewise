import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from './stores/session'

const v = (n: string) => () => import(`./views/${n}.vue`)
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: v('Login') },
    { path: '/display', component: v('Display') },
    { path: '/', component: () => import('./components/Shell.vue'), children: [
      { path: 'dashboard', component: v('Dashboard'), meta: { view: 'dashboard' } },
      { path: 'pos', component: v('Pos'), meta: { view: 'pos' } },
      { path: 'orders', component: v('Orders'), meta: { view: 'orders' } },
      { path: 'online', component: v('Online'), meta: { view: 'online' } },
      { path: 'menu', component: v('Menu'), meta: { view: 'menu' } },
      { path: 'inventory', component: v('Inventory'), meta: { view: 'inventory' } },
      { path: 'customers', component: v('Customers'), meta: { view: 'customers' } },
      { path: 'reports', component: v('Reports'), meta: { view: 'reports' } },
      { path: 'admin', component: v('Admin'), meta: { view: 'admin' } }
    ] },
    { path: '/:rest(.*)*', redirect: '/dashboard' }
  ]
})

router.beforeEach(to => {
  if (to.path === '/display') return true
  const s = useSession()
  if (!s.me) return to.path === '/login' ? true : { path: '/login', query: { next: to.fullPath } }
  if (to.path === '/login') return '/' + s.views[0]
  const view = to.meta.view as string | undefined
  if (view && !s.views.includes(view)) return '/' + s.views[0] // role cannot open this screen
  return true
})
