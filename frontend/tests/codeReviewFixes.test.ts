import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { reactive } from 'vue'

import ProfileEditView from '@/views/ProfileEditView.vue'

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
  default: { name: 'AvatarCropper', props: ['modelValue', 'size'], emits: ['update:modelValue'], template: '<div class="avatar-stub"></div>' }
}))

vi.mock('@/utils/validators', () => ({
  validateNickname: (v: string) => (v && v.trim().length >= 2 ? '' : '昵称至少2个字符'),
  validateAvatarUrl: () => ''
}))

vi.mock('@/utils/errorHandler', () => ({
  handleError: (e: any, fallback: string) => e?.message || fallback
}))

function mockStoreFactory(user: any = { id: '1', nickname: '初始昵称', avatarUrl: '', role: 'USER' }) {
  return reactive({
    currentUser: user,
    currentUserId: user.id,
    fetchProfile: vi.fn().mockResolvedValue(undefined),
    updateProfile: vi.fn().mockImplementation((form: any) => {
      mockStore.value.currentUser = { ...mockStore.value.currentUser, nickname: form.nickname, avatarUrl: form.avatarUrl }
      return Promise.resolve({ ...mockStore.value.currentUser })
    })
  })
}

function mountProfileEdit() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/profile/edit', component: ProfileEditView },
      { path: '/profile', component: { template: '<div></div>' } }
    ]
  })
  return mount(ProfileEditView, { global: { plugins: [router] } })
}

describe('ProfileEditView - formDirty 防止覆盖用户未保存输入', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('初次 fetch → 正常填充表单', async () => {
    mockStore.value = mockStoreFactory({ id: '1', nickname: '商店昵称', avatarUrl: '/avatar.png', role: 'USER' })
    const wrapper = mountProfileEdit()
    await flushPromises()

    const input = wrapper.find('#nickname')
    expect((input.element as HTMLInputElement).value).toBe('商店昵称')
  })

  it('用户编辑后 fetchProfile 返回不覆盖表单', async () => {
    mockStore.value = mockStoreFactory({ id: '1', nickname: '初始', avatarUrl: '', role: 'USER' })
    const wrapper = mountProfileEdit()
    await flushPromises()

    const input = wrapper.find('#nickname')
    await input.setValue('用户手动输入的昵称')
    await flushPromises()

    mockStore.value.currentUser = { id: '1', nickname: '新的商店昵称', avatarUrl: '', role: 'USER' }
    await flushPromises()

    expect((input.element as HTMLInputElement).value).toBe('用户手动输入的昵称')
  })

  it('未编辑表单 + profile 更新 → 可以正常同步', async () => {
    mockStore.value = mockStoreFactory({ id: '1', nickname: '旧昵称', avatarUrl: '', role: 'USER' })
    const wrapper = mountProfileEdit()
    await flushPromises()

    mockStore.value.currentUser = { id: '1', nickname: '更新后的昵称', avatarUrl: '', role: 'USER' }
    await flushPromises()

    const input = wrapper.find('#nickname')
    expect((input.element as HTMLInputElement).value).toBe('更新后的昵称')
  })
})
