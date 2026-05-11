<script setup lang="ts">
import { computed, reactive, ref } from 'vue'

import { useCampusHubStore } from '@/stores/campusHub'

const store = useCampusHubStore()
const activeTab = ref<'login' | 'register'>('login')
const message = ref('')
const error = ref('')
const verificationCodeHint = ref('')
const codeCountdown = ref(0)
const codeSent = ref(false)
let countdownTimer: number | undefined

const loginForm = reactive({
  studentId: '20260001',
  password: 'campus123'
})

const registerForm = reactive({
  studentId: '',
  password: '',
  email: '',
  verificationCode: '',
  nickname: '',
  college: '',
  phone: '',
  avatarUrl: ''
})

const currentUser = computed(() => store.currentUser)

function submitLogin(): void {
  error.value = ''
  message.value = ''

  try {
    const user = store.login(loginForm)
    message.value = `欢迎回来，${user.nickname}。`
  } catch (loginError) {
    error.value = loginError instanceof Error ? loginError.message : '登录失败'
  }
}

function sendVerificationCode(): void {
  error.value = ''
  message.value = ''

  try {
    verificationCodeHint.value = store.sendRegistrationCode(registerForm.email)
    codeSent.value = true
    codeCountdown.value = 60

    if (countdownTimer) {
      window.clearInterval(countdownTimer)
    }

    countdownTimer = window.setInterval(() => {
      if (codeCountdown.value <= 1) {
        codeCountdown.value = 0
        codeSent.value = false

        if (countdownTimer) {
          window.clearInterval(countdownTimer)
          countdownTimer = undefined
        }

        return
      }

      codeCountdown.value -= 1
    }, 1000)

    message.value = `验证码已通过平台主邮箱发送到 ${registerForm.email}。演示验证码：${verificationCodeHint.value}`
  } catch (sendError) {
    error.value = sendError instanceof Error ? sendError.message : '验证码发送失败'
  }
}

function submitRegister(): void {
  error.value = ''
  message.value = ''

  try {
    const user = store.register(registerForm)
    message.value = `注册成功，${user.nickname} 已自动登录。`
    activeTab.value = 'login'
    registerForm.verificationCode = ''
    verificationCodeHint.value = ''
  } catch (registerError) {
    error.value = registerError instanceof Error ? registerError.message : '注册失败'
  }
}
</script>

<template>
  <div class="page-grid two-column">
    <section class="form-panel">
      <div class="segment-row">
        <button
          type="button"
          class="button"
          :class="activeTab === 'login' ? 'primary' : 'secondary'"
          @click="activeTab = 'login'"
        >
          登录
        </button>
        <button
          type="button"
          class="button"
          :class="activeTab === 'register' ? 'primary' : 'secondary'"
          @click="activeTab = 'register'"
        >
          注册
        </button>
      </div>

      <div v-if="activeTab === 'login'" class="field-grid">
        <div class="field">
          <label for="login-student-id">学号</label>
          <input id="login-student-id" v-model="loginForm.studentId" placeholder="20260001" />
        </div>
        <div class="field">
          <label for="login-password">密码</label>
          <input id="login-password" v-model="loginForm.password" type="password" placeholder="campus123" />
        </div>
        <button type="button" class="button primary" @click="submitLogin">登录到演示平台</button>
      </div>

      <div v-else class="form-grid two-column">
        <div class="field">
          <label for="register-student-id">学号</label>
          <input id="register-student-id" v-model="registerForm.studentId" placeholder="例如 20260012" />
        </div>
        <div class="field">
          <label for="register-password">密码</label>
          <input id="register-password" v-model="registerForm.password" type="password" placeholder="至少 8 位" />
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="register-email">邮箱</label>
          <input id="register-email" v-model="registerForm.email" type="email" placeholder="请输入接收验证码的邮箱" />
          <span class="input-help">验证码会从平台主邮箱发送到此邮箱，当前为前端演示版。</span>
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="register-code">邮箱验证码</label>
          <div class="inline-actions" style="align-items: stretch;">
            <input id="register-code" v-model="registerForm.verificationCode" placeholder="输入 6 位验证码" style="flex: 1 1 220px;" />
            <button type="button" class="button secondary" :disabled="codeCountdown > 0" @click="sendVerificationCode">
              {{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : '发送验证码' }}
            </button>
          </div>
          <span v-if="verificationCodeHint" class="input-help">演示验证码：{{ verificationCodeHint }}</span>
        </div>
        <div class="field">
          <label for="register-nickname">昵称</label>
          <input id="register-nickname" v-model="registerForm.nickname" placeholder="你的校园昵称" />
        </div>
        <div class="field">
          <label for="register-college">学院</label>
          <input id="register-college" v-model="registerForm.college" placeholder="所在学院" />
        </div>
        <div class="field">
          <label for="register-phone">手机号</label>
          <input id="register-phone" v-model="registerForm.phone" placeholder="用于联系" />
        </div>
        <div class="field">
          <label for="register-avatar">头像链接</label>
          <input id="register-avatar" v-model="registerForm.avatarUrl" placeholder="可选" />
        </div>
        <button type="button" class="button primary" style="grid-column: 1 / -1;" @click="submitRegister">注册并进入平台</button>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <p class="eyebrow">账号概览</p>
          <h2 class="section-title">当前登录状态</h2>
        </div>
      </div>

      <div v-if="currentUser" class="avatar-row">
        <img :src="currentUser.avatarUrl" :alt="currentUser.nickname" class="avatar large" />
        <div>
          <h3>{{ currentUser.nickname }}</h3>
          <p class="subtle">{{ currentUser.studentId }} · {{ currentUser.college }}</p>
          <div class="stats-row">
            <span class="chip is-neutral">{{ currentUser.role === 'admin' ? '管理员' : '学生' }}</span>
            <span class="chip is-success">信用分 {{ currentUser.creditScore }}</span>
          </div>
        </div>
      </div>

      <div class="section-grid">
        <div class="list-card">
          <strong>API 对齐</strong>
          <p>注册 / 登录表单对应 <code>/api/v1/auth/register</code> 和 <code>/api/v1/auth/login</code>。</p>
        </div>
        <div class="list-card">
          <strong>展示层说明</strong>
          <p>当前使用 Pinia 演示仓库模拟后端返回，后续只需替换 service 层即可接真实接口。</p>
        </div>
      </div>
    </section>
  </div>
</template>