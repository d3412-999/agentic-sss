import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)
app.use(router)
app.mount('#app')

// http.js 在 refresh 也失败时广播该事件，统一跳回登录页
window.addEventListener('sss:unauthorized', () => {
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login' })
  }
})
