import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import DemandPublishView from '@/views/DemandPublishView.vue'
import type { AiDemandDraft } from '@/types/campushub'

const { mockStore, mockGenerate, mockCreateDemand } = vi.hoisted(() => {
  const mockStore: any = {
    currentUser: { role: 'USER', balance: 100, frozenBalance: 0 }
  }
  const mockGenerate = vi.fn()
  const mockCreateDemand = vi.fn()
  mockStore.generateDemandDraft = mockGenerate
  mockStore.createDemand = mockCreateDemand
  mockStore.fetchBalance = vi.fn().mockResolvedValue(100)
  mockStore.fetchProfile = vi.fn().mockResolvedValue(undefined)
  return { mockStore, mockGenerate, mockCreateDemand }
})

vi.mock('@/stores/campusHub', () => ({
  useCampusHubStore: () => mockStore
}))

vi.mock('@/composables/useDialog', () => ({
  useConfirm: vi.fn().mockResolvedValue(false)
}))

vi.mock('@/components/ImageUploader.vue', () => ({
  default: { name: 'ImageUploader', template: '<div class="image-uploader-stub" />' }
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
  onBeforeRouteLeave: vi.fn()
}))

function buildDraft(overrides: Partial<AiDemandDraft> = {}): AiDemandDraft {
  return {
    title: '代取快递并送到南区宿舍',
    description: '明天下午三点帮我从菜鸟驿站取快递送到南区宿舍',
    category: 'EXPRESS',
    campusZone: 'XIANLIN',
    location: '南区宿舍',
    startTime: '2026-10-08T15:00:00',
    endTime: '2026-10-08T17:00:00',
    reward: 10,
    tags: ['快递', '跑腿'],
    interactionMode: 'DIRECT_ACCEPT',
    targetParticipantCount: null,
    note: null,
    missingFields: [],
    ...overrides
  }
}

describe('DemandPublishView - AI 帮我发布', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    vi.clearAllMocks()
    mockStore.currentUser = { role: 'USER', balance: 100, frozenBalance: 0 }
    localStorage.clear()
    wrapper = mount(DemandPublishView, {
      global: {
        stubs: { ImageUploader: true }
      }
    })
  })

  afterEach(() => {
    wrapper?.unmount()
  })

  it('渲染 AI 帮我发布入口按钮', () => {
    expect(wrapper.find('[data-testid="ai-publish-entry"]').exists()).toBe(true)
  })

  it('点击入口按钮打开 AI Dialog', async () => {
    expect(wrapper.find('[data-testid="ai-modal"]').exists()).toBe(false)

    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')

    expect(wrapper.find('[data-testid="ai-modal"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ai-prompt-input"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ai-generate-button"]').exists()).toBe(true)
  })

  it('prompt 为空时生成按钮禁用', async () => {
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')

    const button = wrapper.find('[data-testid="ai-generate-button"]')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('输入 prompt 后生成按钮可用', async () => {
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    const textarea = wrapper.find('[data-testid="ai-prompt-input"]')
    await textarea.setValue('明天下午三点帮我取快递，给10元')

    const button = wrapper.find('[data-testid="ai-generate-button"]')
    expect((button.element as HTMLButtonElement).disabled).toBe(false)
  })

  it('点击生成调用 store.generateDemandDraft', async () => {
    mockGenerate.mockResolvedValue(buildDraft())
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('明天下午三点帮我取快递，给10元')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    expect(mockGenerate).toHaveBeenCalledWith('明天下午三点帮我取快递，给10元')
  })

  it('生成期间按钮显示生成中并禁止重复点击', async () => {
    let resolveGenerate: (value: AiDemandDraft) => void = () => {}
    mockGenerate.mockReturnValue(new Promise<AiDemandDraft>((resolve) => {
      resolveGenerate = resolve
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    const button = wrapper.find('[data-testid="ai-generate-button"]')
    expect(button.text()).toContain('生成中')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)

    resolveGenerate(buildDraft())
    await flushPromises()
  })

  it('成功后回填现有表单字段', async () => {
    mockGenerate.mockResolvedValue(buildDraft())
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('明天下午三点帮我取快递，给10元')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    expect((wrapper.find('#demand-title').element as HTMLInputElement).value).toBe('代取快递并送到南区宿舍')
    expect((wrapper.find('#demand-category').element as HTMLSelectElement).value).toBe('EXPRESS')
    expect((wrapper.find('#demand-zone').element as HTMLSelectElement).value).toBe('XIANLIN')
    expect((wrapper.find('#demand-location').element as HTMLInputElement).value).toBe('南区宿舍')
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('10')
    expect((wrapper.find('#demand-start-datetime').element as HTMLInputElement).value).toBe('2026-10-08T15:00')
    expect((wrapper.find('#demand-end-datetime').element as HTMLInputElement).value).toBe('2026-10-08T17:00')
    expect((wrapper.find('#demand-tags').element as HTMLInputElement).value).toBe('快递,跑腿')
  })

  it('成功后关闭 Dialog', async () => {
    mockGenerate.mockResolvedValue(buildDraft())
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="ai-modal"]').exists()).toBe(false)
  })

  it('missingFields 非空时在表单区域展示补充提示', async () => {
    mockGenerate.mockResolvedValue(buildDraft({
      campusZone: null,
      location: null,
      reward: null,
      missingFields: ['campusZone', 'location', 'reward']
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('帮我找人买咖啡')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    const hint = wrapper.find('[data-testid="ai-missing-hint"]')
    expect(hint.exists()).toBe(true)
    expect(hint.text()).toContain('校区')
    expect(hint.text()).toContain('地点')
    expect(hint.text()).toContain('报酬')
    // 未虚构的字段保持空（reward 在 missingFields 中，默认值被清空）
    expect((wrapper.find('#demand-location').element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('')
  })

  it('AI 请求失败时展示错误且不回填表单', async () => {
    mockGenerate.mockRejectedValue(new Error('AI 服务暂时不可用，请稍后重试'))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    const errorEl = wrapper.find('[data-testid="ai-error"]')
    expect(errorEl.exists()).toBe(true)
    expect(errorEl.text()).toContain('AI 服务暂时不可用')
    // Dialog 仍然打开
    expect(wrapper.find('[data-testid="ai-modal"]').exists()).toBe(true)
    // 表单未被回填
    expect((wrapper.find('#demand-title').element as HTMLInputElement).value).toBe('')
  })

  it('AI 生成后不自动提交 Demand', async () => {
    mockGenerate.mockResolvedValue(buildDraft())
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    expect(mockCreateDemand).not.toHaveBeenCalled()
  })

  it('AI 生成后用户可继续编辑表单', async () => {
    mockGenerate.mockResolvedValue(buildDraft())
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    const titleInput = wrapper.find('#demand-title')
    expect((titleInput.element as HTMLInputElement).value).toBe('代取快递并送到南区宿舍')
    await titleInput.setValue('代取快递并送到北区宿舍（用户修改）')
    expect((titleInput.element as HTMLInputElement).value).toBe('代取快递并送到北区宿舍（用户修改）')
  })

  it('TEAM_UP 草稿回填目标人数', async () => {
    mockGenerate.mockResolvedValue(buildDraft({
      title: '周六下午找3个人一起打羽毛球',
      category: 'TEAM_UP',
      interactionMode: 'SELECT_MANY',
      targetParticipantCount: 3,
      startTime: '2026-10-10T14:00:00',
      endTime: '2026-10-10T17:00:00',
      reward: 0,
      missingFields: ['campusZone', 'location']
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('周六下午找3个人一起打羽毛球')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    expect((wrapper.find('#demand-category').element as HTMLSelectElement).value).toBe('TEAM_UP')
    expect((wrapper.find('#demand-target-count').element as HTMLInputElement).value).toBe('3')
  })

  it('取消按钮关闭 Dialog（未生成时）', async () => {
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    expect(wrapper.find('[data-testid="ai-modal"]').exists()).toBe(true)

    // Dialog 底部取消按钮
    const buttons = wrapper.findAll('[data-testid="ai-modal"] button')
    const cancelButton = buttons.find((b) => b.text() === '取消')
    await cancelButton?.trigger('click')

    expect(wrapper.find('[data-testid="ai-modal"]').exists()).toBe(false)
  })
})
