import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import AuthView from '@/views/AuthView.vue'

const { mockStore } = vi.hoisted(() => ({
  mockStore: { value: null as any }
}))

vi.mock('@/stores/campusHub', () => ({
  useCampusHubStore: () => mockStore.value
}))

vi.mock('@/composables/useDialog', () => ({
  useConfirm: () => Promise.resolve(true),
  useAlert: () => Promise.resolve()
}))

vi.mock('@/components/AvatarCropper.vue', () => ({
  default: {
    name: 'AvatarCropper',
    props: ['modelValue', 'size', 'deferUpload'],
    emits: ['update:modelValue'],
    template: '<div data-testid="avatar-cropper-stub"></div>'
  }
}))

vi.mock('@/utils/validators', () => ({
  validateRegisterForm: () => ({}),
  validateNickname: (v: string) => (v && v.trim().length >= 2 ? '' : '昵称至少2个字符'),
  validateAvatarUrl: () => '',
  validateStudentId: () => '',
  validatePassword: () => '',
  validateEmailPrefix: () => '',
  validateVerificationCode: () => ''
}))

vi.mock('@/utils/errorHandler', () => ({
  handleError: (e: any, fallback: string) => e?.message || fallback
}))

function mockStoreFactory() {
  return {
    currentUser: null,
    currentUserId: null,
    token: '',
    register: vi.fn().mockImplementation((form: any) => {
      return Promise.resolve({ id: '1', nickname: form.nickname || 'testuser', avatarUrl: form.avatarUrl || '' })
    }),
    login: vi.fn(),
    uploadImages: vi.fn().mockResolvedValue(['/api/v1/uploads/2026/10/avatar.png']),
    updateProfile: vi.fn().mockImplementation((form: any) => {
      return Promise.resolve({ id: '1', nickname: form.nickname, avatarUrl: form.avatarUrl })
    }),
    fetchProfile: vi.fn().mockResolvedValue(undefined),
    sendVerificationEmail: vi.fn().mockResolvedValue(undefined)
  }
}

function mountAuthView() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: AuthView },
      { path: '/profile', component: { template: '<div></div>' } }
    ]
  })
  router.push('/')
  return mount(AuthView, { global: { plugins: [router] } })
}

describe('AuthView - 头像选择覆盖问题', () => {
  beforeEach(() => {
    mockStore.value = mockStoreFactory()
  })

  it('裁剪后粘贴 HTTPS 链接时清理旧暂存文件并保留新链接', async () => {
    const wrapper = mountAuthView()
    const vm = wrapper.vm as any

    vm.activeTab = 'register'
    await wrapper.vm.$nextTick()

    // 模拟 AvatarCropper 的 ref（含 pendingFile）
    let pendingFile: File | null = new File([], 'avatar.png', { type: 'image/png' })
    vm.avatarCropperRef = {
      getPendingFile: () => pendingFile,
      clearPendingFile: () => { pendingFile = null }
    }

    // 裁剪后 avatarUrl 是 blob: URL
    const blobUrl = 'blob:https://localhost/abc123'
    vm.registerForm.avatarUrl = blobUrl

    // 用户粘贴 HTTPS 链接，覆盖 blob: URL
    const httpsUrl = 'https://example.com/avatar.png'
    vm.registerForm.avatarUrl = httpsUrl

    // 触发注册
    vm.registerForm.studentId = 'test123'
    vm.registerForm.password = 'Test1234!'
    vm.registerForm.nickname = 'testuser'
    vm.registerForm.emailPrefix = 'test'
    vm.registerForm.emailDomain = 'nju.edu.cn'
    vm.registerForm.verificationCode = '123456'
    await vm.submitRegister()
    await flushPromises()

    // 验证注册时传的是 HTTPS URL（因为 avatarUrl 不是 blob:）
    expect(mockStore.value.register).toHaveBeenCalledTimes(1)
    const registerArgs = mockStore.value.register.mock.calls[0][0]
    expect(registerArgs.avatarUrl).toBe(httpsUrl)

    // 验证没有调用 uploadImages（pendingFile 被清理了）
    expect(mockStore.value.uploadImages).not.toHaveBeenCalled()
  })

  it('裁剪后直接注册时使用 pendingFile 上传', async () => {
    const wrapper = mountAuthView()
    const vm = wrapper.vm as any

    vm.activeTab = 'register'
    await wrapper.vm.$nextTick()

    // 模拟 AvatarCropper 的 ref（含 pendingFile）
    let pendingFile: File | null = new File([], 'avatar.png', { type: 'image/png' })
    vm.avatarCropperRef = {
      getPendingFile: () => pendingFile,
      clearPendingFile: () => { pendingFile = null }
    }

    // 裁剪后 avatarUrl 是 blob: URL
    const blobUrl = 'blob:https://localhost/abc123'
    vm.registerForm.avatarUrl = blobUrl

    // 触发注册
    vm.registerForm.studentId = 'test456'
    vm.registerForm.password = 'Test1234!'
    vm.registerForm.nickname = 'testuser2'
    vm.registerForm.emailPrefix = 'test2'
    vm.registerForm.emailDomain = 'nju.edu.cn'
    vm.registerForm.verificationCode = '654321'
    await vm.submitRegister()
    await flushPromises()

    // 验证注册时传的是空字符串（blob URL 不传给后端）
    const registerArgs = mockStore.value.register.mock.calls[0][0]
    expect(registerArgs.avatarUrl).toBe('')

    // 验证调用了 uploadImages（使用 pendingFile）
    expect(mockStore.value.uploadImages).toHaveBeenCalledTimes(1)

    // 验证调用了 updateProfile（更新头像 URL）
    expect(mockStore.value.updateProfile).toHaveBeenCalledTimes(1)
    expect(mockStore.value.updateProfile.mock.calls[0][0].avatarUrl).toBe('/api/v1/uploads/2026/10/avatar.png')
  })
})
