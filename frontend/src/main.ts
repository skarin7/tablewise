import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { useSession } from './stores/session'
import './style.css'

const app = createApp(App).use(createPinia())
// The display tablet never signs in, so only staff screens wait for the session.
const boot = location.pathname.startsWith('/display') ? Promise.resolve() : useSession().init()
boot.then(() => { app.use(router); app.mount('#app') })
