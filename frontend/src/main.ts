import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import pinia from './stores'
import { useCampusHubStore } from '@/stores/campusHub'
import './style.css'

const app = createApp(App)

app.use(pinia)
app.use(router)

// 尝试从 localStorage 恢复登录态（非阻塞）
try {
	const store = useCampusHubStore()
	store.initializeFromStorage().catch(() => {})
} catch {
	// ignore if pinia/store not ready
}

app.mount('#app')
