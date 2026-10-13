import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import DemandPublishView from '@/views/DemandPublishView.vue'

// 用 vi.hoisted 把需要在 mock factory 中引用的可变状态提到顶层
const { mockStore, mockCreateDemand, mockConfirm } = vi.hoisted(() => ({
  mockStore: { value: null as any },
  mockCreateDemand: { fn: null as ((data: any) => Promise<any>) | null },
  mockConfirm: vi.fn()
}))

vi.mock('@/stores/campusHub', () => ({
  useCampusHubStore: () => mockStore.value
}))

vi.mock('@/composables/useDialog', () => ({
  useConfirm: (...args: unknown[]) => mockConfirm(...args),
  useAlert: () => Promise.resolve()
}))

vi.mock('@/components/ImageUploader.vue', () => ({
  default: { name: 'ImageUploader', template: '<div class="image-uploader-stub"></div>' }
}))

function createMockStore(createDemandOverride?: (data: any) => Promise<any>) {
  mockCreateDemand.fn = createDemandOverride ?? null
  return {
    currentUser: { id: 'u1', role: 'USER', balance: 1000, frozenBalance: 0 },
    fetchBalance: () => Promise.resolve(1000),
    fetchProfile: () => Promise.resolve(),
    createDemand: (data: any) => {
      const fn = mockCreateDemand.fn
      if (fn) return fn(data)
      return Promise.resolve({ id: 'd1' })
    }
  }
}

function mountPublishView() {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div></div>' } }, { path: '/demands', component: { template: '<div></div>' } }]
  })
  return mount(DemandPublishView, {
    global: {
      plugins: [router]
    }
  })
}

// 填充除 category/targetParticipantCount/interactionMode 之外的所有必填字段
async function fillCommonRequiredFields(wrapper: any) {
  await flushPromises()
  if (!wrapper.find('#demand-title').exists()) {
    await wrapper.find('[data-testid="category-card-EXPRESS"]').trigger('click')
  }
  const textareas = wrapper.findAll('textarea')
  await textareas[0].setValue('测试描述') // description

  // title input
  const titleInput = wrapper.find('input#demand-title')
  await titleInput.setValue('测试标题需求')

  // category select
  await wrapper.find('select#demand-category').setValue('TEAM_UP')
  await flushPromises()

  // campusZone select
  await wrapper.find('select#demand-zone').setValue('GULOU')

  // location input
  await wrapper.find('input#demand-location').setValue('北区图书馆')

  // reward input
  await wrapper.find('input#demand-reward').setValue('10')

  // start/end datetime
  await wrapper.find('input#demand-start-datetime').setValue('2026-11-01T10:00')
  await wrapper.find('input#demand-end-datetime').setValue('2026-11-01T12:00')

  await flushPromises()
}

async function setTargetCount(wrapper: any, value: string) {
  const targetInput = wrapper.find('input#demand-target-count')
  expect(targetInput.exists()).toBe(true)
  await targetInput.setValue(value)
  await flushPromises()
}

async function submitForm(wrapper: any) {
  const submitBtn = wrapper.find('button[data-testid="submit-demand"]')
  expect(submitBtn.exists()).toBe(true)
  await submitBtn.trigger('click')
  await flushPromises()
  await flushPromises()
}

describe('DemandPublishView - TEAM_UP 表单校验', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockStore.value = createMockStore()
    mockConfirm.mockResolvedValue(true)
  })

  it('TEAM_UP + 目标人数为空 → 校验失败，并明确指出“目标人数”', async () => {
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await submitForm(wrapper)

    const errorText = wrapper.find('p.hero-badge').text()
    expect(errorText).toContain('目标人数')
    const targetError = wrapper.find('input#demand-target-count').element.parentElement?.querySelector('p.input-help')
    expect(targetError?.textContent).toContain('目标人数')
  })

  it('TEAM_UP + 目标人数为 0 → 校验失败', async () => {
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await setTargetCount(wrapper, '0')
    await submitForm(wrapper)

    const errorText = wrapper.find('p.hero-badge').text()
    expect(errorText).toContain('目标人数')
  })

  it('TEAM_UP + 小数 → 校验失败', async () => {
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await setTargetCount(wrapper, '2.5')
    await submitForm(wrapper)

    const errorText = wrapper.find('p.hero-badge').text()
    expect(errorText).toContain('目标人数')
    // 字段下方应显示“整数”相关具体错误
    const targetError = wrapper.find('input#demand-target-count').element.parentElement?.querySelector('p.input-help')
    expect(targetError?.textContent).toContain('整数')
  })

  it('TEAM_UP + 101 → 校验失败', async () => {
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await setTargetCount(wrapper, '101')
    await submitForm(wrapper)

    const errorText = wrapper.find('p.hero-badge').text()
    expect(errorText).toContain('目标人数')
    // 字段下方应显示“100”相关具体错误
    const targetError = wrapper.find('input#demand-target-count').element.parentElement?.querySelector('p.input-help')
    expect(targetError?.textContent).toContain('100')
  })

  it('TEAM_UP + 2 → 校验通过', async () => {
    const createDemand = vi.fn(() => Promise.resolve({ id: 'd1' }))
    mockStore.value = createMockStore(createDemand)
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await setTargetCount(wrapper, '2')
    await submitForm(wrapper)

    const heroBadge = wrapper.find('p.hero-badge')
    if (heroBadge.exists()) {
      expect(heroBadge.text()).not.toContain('请完善')
    }
    expect(createDemand).toHaveBeenCalled()
    const callArg = createDemand.mock.calls[0][0]
    // TEAM_UP 不需要用户选择 interactionMode
    expect(callArg.interactionMode).toBeNull()
    expect(callArg.targetParticipantCount).toBe(2)
  })

  it('过去的开始时间仍可发布，页面不增加“开始时间必须在未来”的规则', async () => {
    const createDemand = vi.fn(() => Promise.resolve({ id: 'd1' }))
    mockStore.value = createMockStore(createDemand)
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await wrapper.find('#demand-category').setValue('EXPRESS')
    await wrapper.find('#demand-start-datetime').setValue('2020-01-01T09:00')
    await wrapper.find('#demand-end-datetime').setValue('2020-01-01T10:00')
    await submitForm(wrapper)
    expect(createDemand).toHaveBeenCalledTimes(1)
  })

  it('发布请求失败时保留表单内容并展示错误', async () => {
    const createDemand = vi.fn(() => Promise.reject(new Error('网络暂时不可用')))
    mockStore.value = createMockStore(createDemand)
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await setTargetCount(wrapper, '2')
    await submitForm(wrapper)
    expect(createDemand).toHaveBeenCalledTimes(1)
    expect((wrapper.find('#demand-title').element as HTMLInputElement).value).toBe('测试标题需求')
    expect(wrapper.find('p.hero-badge').text()).toContain('网络暂时不可用')
  })

  it('确认框打开期间重复点击不会重复提交', async () => {
    const createDemand = vi.fn(() => Promise.resolve({ id: 'd1' }))
    mockStore.value = createMockStore(createDemand)
    let resolveConfirm: (value: boolean) => void = () => {}
    mockConfirm.mockReturnValue(new Promise<boolean>((resolve) => { resolveConfirm = resolve }))
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await setTargetCount(wrapper, '2')
    const button = wrapper.find('[data-testid="submit-demand"]')
    await button.trigger('click')
    await button.trigger('click')
    expect(createDemand).not.toHaveBeenCalled()
    resolveConfirm(true)
    await flushPromises()
    expect(createDemand).toHaveBeenCalledTimes(1)
  })

  it('切换离开 SELECT_MANY 类型会清除旧目标人数', async () => {
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await wrapper.find('#demand-category').setValue('TEAM_UP')
    await setTargetCount(wrapper, '5')
    await wrapper.find('#demand-category').setValue('ERRAND')
    await flushPromises()
    await wrapper.find('#demand-category').setValue('TEAM_UP')
    await flushPromises()
    expect((wrapper.find('#demand-target-count').element as HTMLInputElement).value).toBe('')
  })

  it('TEAM_UP 不要求 interactionMode - 互动模式选择框不渲染', async () => {
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    expect(wrapper.find('select#demand-interaction-mode').exists()).toBe(false)
  })

  it('OTHER + 空 interactionMode → 校验失败', async () => {
    const wrapper = mountPublishView()
    await fillCommonRequiredFields(wrapper)
    await wrapper.find('select#demand-category').setValue('OTHER')
    await flushPromises()
    await submitForm(wrapper)

    const errorText = wrapper.find('p.hero-badge').text()
    expect(errorText).toContain('互动模式')
  })
})
