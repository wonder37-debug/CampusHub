<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { validateLoginForm, validateRegisterForm } from '@/utils/validators'
import { formatUserRole, formatUserStatus } from '@/utils/format'
import { handleError } from '@/utils/errorHandler'

const store = useCampusHubStore()
const router = useRouter()
const route = useRoute()
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
const showLoginPassword = ref(false)

const registerForm = reactive({
  studentId: '',
  password: '',
  emailPrefix: '',
  emailDomain: emailDomainOptions[0].value,
  verificationCode: '',
  nickname: '',
  avatarUrl: ''
})
const showRegisterPassword = ref(false)

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

  // basic validations
  const loginErrors = validateLoginForm(loginForm)
  if (Object.keys(loginErrors).length) {
    error.value = Object.values(loginErrors)[0]
    return
  }

  try {
    const user = await store.login(loginForm)
    message.value = `欢迎回来，${user.nickname}。`
    // 如果有 redirect 查询参数，则跳回原页面
    const redirect = route.query.redirect as string | undefined
    if (redirect) {
      router.replace(redirect)
    } else {
      router.replace('/profile')
    }
  } catch (loginError) {
      error.value = handleError(loginError, '登录失败')
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
      error.value = handleError(sendError, '验证码发送失败')
  } finally {
    codeSending.value = false
  }
}

async function submitRegister(): Promise<void> {
  error.value = ''
  message.value = ''

  // basic validations
  const regFieldErrors = validateRegisterForm({
    studentId: registerForm.studentId,
    password: registerForm.password,
    emailPrefix: registerForm.emailPrefix,
    verificationCode: registerForm.verificationCode
  })
  if (Object.keys(regFieldErrors).length) {
    error.value = Object.values(regFieldErrors)[0]
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
    const redirect = route.query.redirect as string | undefined
    if (redirect) {
      router.replace(redirect)
    } else {
      router.replace('/profile')
    }
  } catch (registerError) {
      error.value = handleError(registerError, '注册失败')
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
          <input id="login-student-id" v-model="loginForm.studentId" placeholder="请输入您的学号" />
        </div>
        <div class="field">
          <label for="login-password">密码</label>
          <div class="password-wrapper">
            <input :type="showLoginPassword ? 'text' : 'password'" id="login-password" v-model="loginForm.password" placeholder="请输入您的密码" />
            <button type="button" class="password-toggle" @click="showLoginPassword = !showLoginPassword" :aria-label="showLoginPassword ? '隐藏密码' : '显示密码'">
              <svg v-if="showLoginPassword" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
            </button>
          </div>
        </div>
        <button type="submit" class="button primary">登录到平台</button>
      </form>

      <form v-else class="form-grid two-column" @submit.prevent="submitRegister">
        <div class="field">
          <label for="register-student-id"><span class="required">*</span> 学号</label>
          <input id="register-student-id" v-model="registerForm.studentId" placeholder="例如 20260012" />
        </div>
        <div class="field">
          <label for="register-password"><span class="required">*</span> 密码</label>
          <div class="password-wrapper">
            <input :type="showRegisterPassword ? 'text' : 'password'" id="register-password" v-model="registerForm.password" placeholder="至少 8 位" />
            <button type="button" class="password-toggle" @click="showRegisterPassword = !showRegisterPassword" :aria-label="showRegisterPassword ? '隐藏密码' : '显示密码'">
              <svg v-if="showRegisterPassword" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
            </button>
          </div>
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="register-email-prefix"><span class="required">*</span> 邮箱</label>
          <div class="inline-actions" style="align-items: stretch;">
            <input
              id="register-email-prefix"
              v-model="registerForm.emailPrefix"
              inputmode="numeric"
              pattern="[0-9]*"
              placeholder="例如241880515"
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
          <label for="register-code"><span class="required">*</span> 邮箱验证码</label>
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
          <label for="register-nickname"><span class="required">*</span> 昵称</label>
          <input id="register-nickname" v-model="registerForm.nickname" placeholder="你的校园昵称" />
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="register-avatar">头像链接 <span class="optional-hint">（选填）</span></label>
          <input
            id="register-avatar"
            v-model="registerForm.avatarUrl"
            placeholder="https://..."
          />
          <span class="input-help">你可以填写一张头像图片链接来设置个人头像，不填也完全没关系。</span>
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
          <p class="eyebrow">快速指引</p>
          <h2 class="section-title">登录与注册</h2>
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
