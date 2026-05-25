<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatUserRole, formatUserStatus } from '@/utils/format'

const store = useCampusHubStore()
const router = useRouter()
const emailDomainOptions = [
  { label: '@nju.edu.cn', value: 'nju.edu.cn' },
  { label: '@smail.nju.edu.cn', value: 'smail.nju.edu.cn' }
]

const activeTab = ref<'login' | 'register'>('login')
const message = ref('')
const error = ref('')
const verificationCodeHint = ref('')
const codeCountdown = ref(0)
const codeSent = ref(false)
const codeSending = ref(false)
let countdownTimer: number | undefined

const loginForm = reactive({
  studentId: '',
  password: ''
})

const registerForm = reactive({
  studentId: '',
  password: '',
  emailPrefix: '',
  emailDomain: emailDomainOptions[0].value,
  verificationCode: '',
  nickname: '',
  avatarUrl: ''
})

const currentUser = computed(() => store.currentUser)

const registrationEmail = computed(() => {
  const prefix = registerForm.emailPrefix.trim()
  return prefix ? `${prefix}@${registerForm.emailDomain}` : ''
})

function validateEmailPrefix(): boolean {
  const prefix = registerForm.emailPrefix.trim()
  if (!prefix) {
    error.value = '请先输入邮箱前缀'
    return false
  }

  if (!/^\d+$/.test(prefix)) {
    error.value = '邮箱前缀只能包含数字'
    return false
  }

  return true
}

async function submitLogin(): Promise<void> {
  error.value = ''
  message.value = ''

  try {
    const user = await store.login(loginForm)
    message.value = `欢迎回来，${user.nickname}。`
    router.replace('/profile')
  } catch (loginError) {
    error.value = loginError instanceof Error ? loginError.message : '登录失败'
  }
}

async function sendVerificationCode(): Promise<void> {
  error.value = ''
  message.value = ''

  if (codeSending.value || codeCountdown.value > 0) {
    return
  }

  if (!registerForm.emailPrefix.trim()) {
    error.value = '请先输入邮箱'
    return
  }

  if (!validateEmailPrefix()) {
    return
  }

  const email = registrationEmail.value

  try {
    codeSending.value = true
    verificationCodeHint.value = ''
    await store.sendRegistrationCode(email, registerForm.studentId)
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

    message.value = `验证码已发送到 ${email}，请查收邮箱后完成注册。`
  } catch (sendError) {
    error.value = sendError instanceof Error ? sendError.message : '验证码发送失败'
  } finally {
    codeSending.value = false
  }
}

async function submitRegister(): Promise<void> {
  error.value = ''
  message.value = ''

  if (!validateEmailPrefix()) {
    return
  }

  const email = registrationEmail.value

  try {
    const user = await store.register({
      ...registerForm,
      email
    })
    message.value = `注册成功，${user.nickname} 已自动登录。`
    activeTab.value = 'login'
    registerForm.verificationCode = ''
    registerForm.avatarUrl = ''
    verificationCodeHint.value = ''
    router.replace('/profile')
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

      <form v-if="activeTab === 'login'" class="field-grid" @submit.prevent="submitLogin">
        <div class="field">
          <label for="login-student-id">学号</label>
          <input id="login-student-id" v-model="loginForm.studentId" placeholder="20260001" />
        </div>
        <div class="field">
          <label for="login-password">密码</label>
          <input id="login-password" v-model="loginForm.password" type="password" placeholder="campus123" />
        </div>
        <button type="submit" class="button primary">登录到平台</button>
      </form>

      <form v-else class="form-grid two-column" @submit.prevent="submitRegister">
        <div class="field">
          <label for="register-student-id">学号</label>
          <input id="register-student-id" v-model="registerForm.studentId" placeholder="例如 20260012" />
        </div>
        <div class="field">
          <label for="register-password">密码</label>
          <input id="register-password" v-model="registerForm.password" type="password" placeholder="至少 8 位" />
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="register-email-prefix">邮箱</label>
          <div class="inline-actions" style="align-items: stretch;">
            <input
              id="register-email-prefix"
              v-model="registerForm.emailPrefix"
              inputmode="numeric"
              pattern="[0-9]*"
              placeholder="241880515"
              style="flex: 1 1 220px;"
            />
            <span class="chip is-neutral" style="padding-inline: 14px;">@</span>
            <select id="register-email-domain" v-model="registerForm.emailDomain" style="flex: 0 0 180px;">
              <option v-for="option in emailDomainOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
          </div>
          <span class="input-help">请输入邮箱前缀，并在右侧选择学校邮箱域名。</span>
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="register-code">邮箱验证码</label>
          <div class="inline-actions" style="align-items: stretch;">
            <input
              id="register-code"
              v-model="registerForm.verificationCode"
              placeholder="输入 6 位验证码"
              style="flex: 1 1 220px;"
            />
            <button
              type="button"
              class="button secondary"
              :disabled="codeSending || codeCountdown > 0"
              @click="sendVerificationCode"
            >
              {{ codeSending ? '发送中...' : codeCountdown > 0 ? `${codeCountdown}s 后重发` : '发送验证码' }}
            </button>
          </div>
          <span v-if="verificationCodeHint" class="input-help">请使用收到的验证码完成注册：{{ verificationCodeHint }}</span>
        </div>
        <div class="field">
          <label for="register-nickname">昵称</label>
          <input id="register-nickname" v-model="registerForm.nickname" placeholder="你的校园昵称" />
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="register-avatar">头像链接</label>
          <input
            id="register-avatar"
            v-model="registerForm.avatarUrl"
            placeholder="https://..."
          />
          <span class="input-help">请输入可访问的头像图片链接，注册后会直接保存该地址。</span>
          <div v-if="registerForm.avatarUrl.trim()" class="avatar-row" style="margin-top: 12px;">
            <img :src="registerForm.avatarUrl" alt="头像预览" class="avatar" />
            <div>
              <strong>头像预览</strong>
              <p class="subtle">将使用当前填写的图片链接。</p>
            </div>
          </div>
        </div>
        <button type="submit" class="button primary" style="grid-column: 1 / -1;">注册并进入平台</button>
      </form>

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
          <p class="subtle">{{ currentUser.studentId }} / {{ currentUser.email }}</p>
          <div class="stats-row">
            <span class="chip is-neutral">{{ formatUserRole(currentUser.role) }}</span>
            <span class="chip is-success">{{ formatUserStatus(currentUser.status) }}</span>
            <span class="chip is-success">信用分 {{ currentUser.creditScore }}</span>
          </div>
        </div>
      </div>

      <div class="section-grid">
        <div class="list-card">
          <strong>使用说明</strong>
          <p>注册完成后就可以直接登录，继续使用发布、接单和消息功能。</p>
        </div>
        <div class="list-card">
          <strong>账号提示</strong>
          <p>登录后会自动展示当前账号信息，方便继续使用平台。</p>
        </div>
      </div>
    </section>
  </div>
</template>
