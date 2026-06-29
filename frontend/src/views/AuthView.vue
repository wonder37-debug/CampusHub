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
const showForgotPassword = ref(false)
const message = ref('')
const error = ref('')
const verificationCodeHint = ref('')
const codeCountdown = ref(0)
const codeSent = ref(false)
const codeSending = ref(false)
let countdownTimer: number | undefined
const forgotCodeCountdown = ref(0)
const forgotCodeSending = ref(false)
const forgotPasswordSubmitting = ref(false)
const forgotCodeMessage = ref('')
let forgotCountdownTimer: number | undefined

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
const registerAvatarInput = ref<HTMLInputElement | null>(null)
const uploadingRegisterAvatar = ref(false)

function triggerRegisterAvatarUpload() {
  registerAvatarInput.value?.click()
}

async function handleRegisterAvatarChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  target.value = ''

  const allowedExts = ['jpg', 'jpeg', 'png', 'webp']
  const ext = file.name.split('.').pop()?.toLowerCase()
  if (!ext || !allowedExts.includes(ext)) {
    error.value = '头像仅支持 jpg/png/webp 格式'
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    error.value = '头像大小不能超过 5MB'
    return
  }

  uploadingRegisterAvatar.value = true
  error.value = ''
  try {
    const urls = await store.uploadImages([file])
    if (urls.length > 0) {
      registerForm.avatarUrl = urls[0]
    }
  } catch (uploadErr: any) {
    error.value = handleError(uploadErr, '头像上传失败')
  } finally {
    uploadingRegisterAvatar.value = false
  }
}

function removeRegisterAvatar() {
  registerForm.avatarUrl = ''
}

const forgotPasswordForm = reactive({
  email: '',
  verificationCode: '',
  newPassword: ''
})
const showForgotPasswordValue = ref(false)

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

async function sendForgotPasswordCode(): Promise<void> {
  error.value = ''
  message.value = ''
  forgotCodeMessage.value = ''

  if (forgotCodeSending.value || forgotCodeCountdown.value > 0) {
    return
  }

  if (!forgotPasswordForm.email.trim()) {
    error.value = '请输入注册邮箱'
    return
  }

  try {
    forgotCodeSending.value = true
    await store.sendPasswordResetCode(forgotPasswordForm.email)
    forgotCodeCountdown.value = 60
    forgotCodeMessage.value = `验证码已发送到 ${forgotPasswordForm.email.trim()}，请前往邮箱查收。`

    if (forgotCountdownTimer) {
      window.clearInterval(forgotCountdownTimer)
    }

    forgotCountdownTimer = window.setInterval(() => {
      if (forgotCodeCountdown.value <= 1) {
        forgotCodeCountdown.value = 0
        if (forgotCountdownTimer) {
          window.clearInterval(forgotCountdownTimer)
          forgotCountdownTimer = undefined
        }
        return
      }

      forgotCodeCountdown.value -= 1
    }, 1000)
  } catch (sendError) {
    error.value = handleError(sendError, '验证码发送失败')
  } finally {
    forgotCodeSending.value = false
  }
}

async function submitForgotPassword(): Promise<void> {
  error.value = ''
  message.value = ''

  if (!forgotPasswordForm.email.trim()) {
    error.value = '请输入注册邮箱'
    return
  }

  if (!forgotPasswordForm.verificationCode.trim()) {
    error.value = '请输入邮箱验证码'
    return
  }

  if (!forgotPasswordForm.newPassword.trim()) {
    error.value = '请输入新密码'
    return
  }

  try {
    forgotPasswordSubmitting.value = true
    await store.resetPassword(
      forgotPasswordForm.email,
      forgotPasswordForm.verificationCode,
      forgotPasswordForm.newPassword
    )
    message.value = '密码已重置，请使用新密码登录。'
    showForgotPassword.value = false
    forgotPasswordForm.verificationCode = ''
    forgotPasswordForm.newPassword = ''
    activeTab.value = 'login'
  } catch (resetError) {
    error.value = handleError(resetError, '密码重置失败')
  } finally {
    forgotPasswordSubmitting.value = false
  }
}
</script>

<template>
  <div>
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
        <p style="text-align:right; margin:-4px 0 8px 0;">
          <a href="#" @click.prevent="showForgotPassword = true" style="color:var(--muted);font-size:13px;">忘记密码？</a>
        </p>
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
          <label>头像 <span class="optional-hint">（选填）</span></label>
          <div class="register-avatar-section">
            <div v-if="!registerForm.avatarUrl.trim()" class="avatar-upload-trigger" @click="triggerRegisterAvatarUpload">
              <input
                ref="registerAvatarInput"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                style="display: none"
                @change="handleRegisterAvatarChange"
              />
              <span v-if="uploadingRegisterAvatar" class="avatar-upload-status">上传中...</span>
              <template v-else>
                <span class="avatar-upload-icon">📷</span>
                <span class="avatar-upload-text">点击上传头像</span>
                <span class="avatar-upload-hint">支持 jpg/png/webp，≤5MB</span>
              </template>
            </div>
            <div v-else class="avatar-preview-card">
              <img :src="registerForm.avatarUrl" alt="头像预览" class="avatar large" />
              <div class="avatar-preview-info">
                <strong>头像已上传</strong>
                <p class="subtle">点击可重新选择，或移除后填入链接。</p>
              </div>
              <button type="button" class="avatar-remove-btn" @click="removeRegisterAvatar" title="移除头像">×</button>
            </div>
          </div>
          <div class="avatar-url-fallback">
            <input
              id="register-avatar"
              v-model="registerForm.avatarUrl"
              placeholder="或直接粘贴图片链接 https://..."
            />
          </div>
          <span class="input-help">上传或粘贴链接设置头像，不填也完全没关系。</span>
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

  <!-- 忘记密码提示 -->
  <Teleport to="body">
    <div v-if="showForgotPassword" class="modal-backdrop">
      <div class="modal-card panel">
        <div class="panel-head">
          <div>
            <h3 class="section-title">找回密码</h3>
            <p class="eyebrow">使用注册邮箱验证</p>
          </div>
          <button type="button" class="button secondary" @click="showForgotPassword = false">关闭</button>
        </div>

        <form class="field-grid" @submit.prevent="submitForgotPassword">
          <div class="field">
            <label for="forgot-email">注册邮箱</label>
            <input id="forgot-email" v-model="forgotPasswordForm.email" placeholder="请输入账号绑定邮箱" />
          </div>
          <div class="field">
            <label for="forgot-code">邮箱验证码</label>
            <div class="inline-actions" style="align-items: stretch;">
              <input id="forgot-code" v-model="forgotPasswordForm.verificationCode" placeholder="输入验证码" style="flex: 1 1 220px;" />
              <button
                type="button"
                class="button secondary"
                :disabled="forgotCodeSending || forgotCodeCountdown > 0"
                @click="sendForgotPasswordCode"
              >
                {{ forgotCodeSending ? '发送中...' : forgotCodeCountdown > 0 ? `${forgotCodeCountdown}s 后重发` : '发送验证码' }}
              </button>
            </div>
          </div>
          <div class="field">
            <label for="forgot-password">新密码</label>
            <div class="password-wrapper">
              <input
                :type="showForgotPasswordValue ? 'text' : 'password'"
                id="forgot-password"
                v-model="forgotPasswordForm.newPassword"
                placeholder="请输入新密码"
              />
              <button
                type="button"
                class="password-toggle"
                :aria-label="showForgotPasswordValue ? '隐藏密码' : '显示密码'"
                @click="showForgotPasswordValue = !showForgotPasswordValue"
              >
                <svg v-if="showForgotPasswordValue" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
              </button>
            </div>
          </div>
          <p v-if="forgotCodeMessage" class="hero-badge" style="margin: 0;">{{ forgotCodeMessage }}</p>
          <p class="input-help">系统会向账号绑定邮箱发送验证码，验证通过后可直接设置新密码。</p>
          <div class="card-actions" style="justify-content: flex-end;">
            <button type="button" class="button secondary" @click="showForgotPassword = false">取消</button>
            <button type="submit" class="button primary" :disabled="forgotPasswordSubmitting">
              {{ forgotPasswordSubmitting ? '提交中...' : '确认重置密码' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </Teleport>
  </div>
</template>

<style scoped>
.register-avatar-section {
  margin-bottom: 8px;
}

.avatar-upload-trigger {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 24px 16px;
  border: 2px dashed rgba(0, 0, 0, 0.14);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.avatar-upload-trigger:hover {
  border-color: var(--accent, #1f5f53);
  background: rgba(31, 95, 83, 0.04);
}

.avatar-upload-icon {
  font-size: 28px;
}

.avatar-upload-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-strong, #1f1a17);
}

.avatar-upload-hint {
  font-size: 12px;
  color: var(--muted, #7f7264);
}

.avatar-upload-status {
  font-size: 14px;
  font-weight: 600;
  color: var(--accent, #1f5f53);
}

.avatar-preview-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(0, 0, 0, 0.08);
  position: relative;
}

.avatar-preview-info {
  flex: 1;
}

.avatar-preview-info strong {
  display: block;
  margin-bottom: 2px;
}

.avatar-preview-info p {
  margin: 0;
  font-size: 13px;
}

.avatar-remove-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: none;
  background: rgba(0, 0, 0, 0.08);
  color: var(--text, #4c4134);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.avatar-remove-btn:hover {
  background: rgba(181, 71, 71, 0.15);
  color: var(--danger, #b54747);
}

.avatar-url-fallback input {
  width: 100%;
  font-size: 13px;
}
</style>
