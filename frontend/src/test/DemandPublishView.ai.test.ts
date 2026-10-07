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

  it('reward=null 时清空默认值，即使 missingFields 不含 reward', async () => {
    mockGenerate.mockResolvedValue(buildDraft({
      title: '取快递',
      description: '描述',
      campusZone: 'XIANLIN',
      location: '图书馆',
      startTime: '2026-10-08T10:00:00',
      endTime: '2026-10-08T12:00:00',
      reward: null,
      // 故意让 missingFields 不含 reward，验证前端不依赖 missingFields 决定清空
      missingFields: []
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // reward=null 必须清空，不依赖 missingFields，不能保留默认 10 元
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('')
  })

  it('reward=0 时保留为 0，不误判为空', async () => {
    mockGenerate.mockResolvedValue(buildDraft({
      title: '取快递',
      description: '描述',
      campusZone: 'XIANLIN',
      location: '图书馆',
      startTime: '2026-10-08T10:00:00',
      endTime: '2026-10-08T12:00:00',
      reward: 0,
      missingFields: []
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // reward=0 是有效值，必须保留为 "0"，不能误判为空
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('0')
  })

  it('category 从 TEAM_UP 改为 EXPRESS 时清理旧 targetParticipantCount', async () => {
    // 先模拟用户选了 TEAM_UP + target count = 3
    await wrapper.find('#demand-category').setValue('TEAM_UP')
    await wrapper.find('#demand-target-count').setValue('3')
    expect((wrapper.find('#demand-target-count').element as HTMLInputElement).value).toBe('3')

    // AI 返回 EXPRESS（category 改变）
    mockGenerate.mockResolvedValue(buildDraft({
      title: '取快递',
      description: '描述',
      category: 'EXPRESS',
      campusZone: 'XIANLIN',
      location: '图书馆',
      startTime: '2026-10-08T10:00:00',
      endTime: '2026-10-08T12:00:00',
      reward: 10,
      missingFields: []
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // EXPRESS 模式下 target count 输入框不显示（effectiveInteractionMode=DIRECT_ACCEPT）
    // 旧的 targetParticipantCount=3 已被清理，不残留
    expect(wrapper.find('#demand-target-count').exists()).toBe(false)
    expect((wrapper.find('#demand-category').element as HTMLSelectElement).value).toBe('EXPRESS')
  })

  it('AI interactionMode=null 清空旧 mode（OTHER）', async () => {
    // 先选 OTHER + interactionMode=SELECT_ONE
    await wrapper.find('#demand-category').setValue('OTHER')
    const modeSelect = wrapper.find('#demand-interaction-mode')
    await modeSelect.setValue('SELECT_ONE')
    expect((modeSelect.element as HTMLSelectElement).value).toBe('SELECT_ONE')

    // AI 返回 OTHER + interactionMode=null
    mockGenerate.mockResolvedValue(buildDraft({
      title: '其他需求',
      description: '描述',
      category: 'OTHER',
      campusZone: 'XIANLIN',
      location: '图书馆',
      startTime: '2026-10-08T10:00:00',
      endTime: '2026-10-08T12:00:00',
      reward: 0,
      tags: [],
      interactionMode: null,
      targetParticipantCount: null,
      missingFields: ['interactionMode']
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('其他需求')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // 旧 SELECT_ONE 已清空，不能残留
    expect((wrapper.find('#demand-interaction-mode').element as HTMLSelectElement).value).toBe('')
  })

  it('AI tags=[] 清空旧 tags', async () => {
    await wrapper.find('#demand-tags').setValue('跑腿,代取')
    expect((wrapper.find('#demand-tags').element as HTMLInputElement).value).toBe('跑腿,代取')

    mockGenerate.mockResolvedValue(buildDraft({
      title: '取快递',
      description: '描述',
      category: 'EXPRESS',
      campusZone: 'XIANLIN',
      location: '图书馆',
      startTime: '2026-10-08T10:00:00',
      endTime: '2026-10-08T12:00:00',
      reward: 10,
      tags: [],
      missingFields: []
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // 旧 tags 已清空，不 append
    expect((wrapper.find('#demand-tags').element as HTMLInputElement).value).toBe('')
  })

  it('AI targetParticipantCount=null 清空旧人数（TEAM_UP）', async () => {
    await wrapper.find('#demand-category').setValue('TEAM_UP')
    await wrapper.find('#demand-target-count').setValue('3')

    mockGenerate.mockResolvedValue(buildDraft({
      title: '组队',
      description: '描述',
      category: 'TEAM_UP',
      campusZone: 'XIANLIN',
      location: '操场',
      startTime: '2026-10-10T14:00:00',
      endTime: '2026-10-10T17:00:00',
      reward: 0,
      tags: [],
      interactionMode: null,
      targetParticipantCount: null,
      missingFields: ['targetParticipantCount']
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('组队')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // TEAM_UP 仍显示 target count 输入框，但值已清空（null → ''）
    expect((wrapper.find('#demand-target-count').element as HTMLInputElement).value).toBe('')
  })

  it('连续两次 AI 生成：最终完全等于第二次草稿，不残留第一次', async () => {
    // 第一次：TEAM_UP + count=3 + reward=20 + tags=['跑腿']
    mockGenerate.mockResolvedValueOnce(buildDraft({
      title: '组队打球',
      description: '描述',
      category: 'TEAM_UP',
      campusZone: 'XIANLIN',
      location: '操场',
      startTime: '2026-10-10T14:00:00',
      endTime: '2026-10-10T17:00:00',
      reward: 20,
      tags: ['跑腿'],
      interactionMode: 'SELECT_MANY',
      targetParticipantCount: 3,
      missingFields: []
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('组队')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // 第二次：EXPRESS + null + null + []
    mockGenerate.mockResolvedValueOnce(buildDraft({
      title: '代取快递',
      description: '描述2',
      category: 'EXPRESS',
      campusZone: 'GULOU',
      location: '图书馆',
      startTime: '2026-10-08T10:00:00',
      endTime: '2026-10-08T12:00:00',
      reward: null,
      tags: [],
      interactionMode: null,
      targetParticipantCount: null,
      missingFields: ['reward']
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // 最终完全等于第二次草稿，不残留第一次的 3/20/跑腿
    expect((wrapper.find('#demand-category').element as HTMLSelectElement).value).toBe('EXPRESS')
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('#demand-tags').element as HTMLInputElement).value).toBe('')
    // EXPRESS 不显示 target count（DIRECT_ACCEPT），旧的 3 已清空
    expect(wrapper.find('#demand-target-count').exists()).toBe(false)
  })

  it('localStorage 污染：AI 返回 null/[] 清空 localStorage 旧值', async () => {
    // 模拟 localStorage 有旧草稿
    localStorage.setItem('campushub.demand.draft', JSON.stringify({
      title: '', description: '', category: 'TEAM_UP', campusZone: '', location: '',
      startDateTime: '', endDateTime: '', reward: '10', targetParticipantCount: '3',
      interactionMode: '', tags: '旧标签', images: [], contactInfo: '', anonymous: false
    }))
    // 重新 mount 触发 onMounted loadDemandDraft
    wrapper.unmount()
    wrapper = mount(DemandPublishView, {
      global: { stubs: { ImageUploader: true } }
    })
    await flushPromises()
    // localStorage 恢复了旧值
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('10')
    expect((wrapper.find('#demand-tags').element as HTMLInputElement).value).toBe('旧标签')

    // AI 返回 null/[]
    mockGenerate.mockResolvedValue(buildDraft({
      title: '取快递',
      description: '描述',
      category: 'EXPRESS',
      campusZone: 'XIANLIN',
      location: '图书馆',
      startTime: '2026-10-08T10:00:00',
      endTime: '2026-10-08T12:00:00',
      reward: null,
      tags: [],
      interactionMode: null,
      targetParticipantCount: null,
      missingFields: ['reward']
    }))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // localStorage 的旧值被 AI null/[] 清空，不能 fallback
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('#demand-tags').element as HTMLInputElement).value).toBe('')
  })

  it('AI 请求失败时表单完全保持原状态，不清空', async () => {
    // 设置旧数据
    await wrapper.find('#demand-reward').setValue('10')
    await wrapper.find('#demand-tags').setValue('旧标签')
    await wrapper.find('#demand-category').setValue('TEAM_UP')
    await wrapper.find('#demand-target-count').setValue('3')

    // AI 请求失败（500）
    mockGenerate.mockRejectedValue(new Error('AI 服务暂时不可用，请稍后重试'))
    await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
    await wrapper.find('[data-testid="ai-prompt-input"]').setValue('取快递')
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()

    // 表单完全保持原状态，不能清空
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('10')
    expect((wrapper.find('#demand-tags').element as HTMLInputElement).value).toBe('旧标签')
    expect((wrapper.find('#demand-category').element as HTMLSelectElement).value).toBe('TEAM_UP')
    expect((wrapper.find('#demand-target-count').element as HTMLInputElement).value).toBe('3')
    // 错误提示展示
    expect(wrapper.find('[data-testid="ai-error"]').exists()).toBe(true)
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
