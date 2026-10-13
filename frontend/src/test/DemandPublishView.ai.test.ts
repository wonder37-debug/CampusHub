import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import type { AiDemandDraft } from '@/types/campushub'
import DemandPublishView from '@/views/DemandPublishView.vue'

const { mockStore, mockGenerate, mockCreate, mockPush, mockConfirm } = vi.hoisted(() => {
  const generate = vi.fn()
  const create = vi.fn()
  const push = vi.fn()
  const confirm = vi.fn()
  return {
    mockStore: { currentUser: { role: 'USER', balance: 100, frozenBalance: 0 }, generateDemandDraft: generate, createDemand: create },
    mockGenerate: generate,
    mockCreate: create,
    mockPush: push,
    mockConfirm: confirm
  }
})

vi.mock('@/stores/campusHub', () => ({ useCampusHubStore: () => mockStore }))
vi.mock('@/composables/useDialog', () => ({ useConfirm: (...args: unknown[]) => mockConfirm(...args), useAlert: vi.fn() }))
vi.mock('@/components/ImageUploader.vue', () => ({ default: { name: 'ImageUploader', template: '<div />' } }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush, back: vi.fn() }),
  onBeforeRouteLeave: vi.fn()
}))

function buildDraft(overrides: Partial<AiDemandDraft> = {}): AiDemandDraft {
  return {
    title: '代取快递并送到南区宿舍', description: '下午帮我取快递', category: 'EXPRESS',
    campusZone: 'XIANLIN', location: '南区宿舍', startTime: '2026-10-08T15:00:00',
    endTime: '2026-10-08T17:00:00', reward: 10, tags: ['快递'], interactionMode: 'DIRECT_ACCEPT',
    targetParticipantCount: null, contactInfo: null, anonymous: null, missingFields: [], ...overrides
  }
}

async function openAi(wrapper: VueWrapper, prompt = '明天下午帮我取快递'): Promise<void> {
  await wrapper.find('[data-testid="ai-publish-entry"]').trigger('click')
  await wrapper.find('[data-testid="ai-prompt-input"]').setValue(prompt)
}

async function generate(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
  await flushPromises()
}

async function acceptAiType(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('[data-testid="ai-type-confirm"]').trigger('click')
  await flushPromises()
}

describe('DemandPublishView - 发布入口与 AI 草稿', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockStore.currentUser = { role: 'USER', balance: 100, frozenBalance: 0 }
    mockConfirm.mockResolvedValue(true)
    mockCreate.mockResolvedValue({ id: 'd-42' })
    wrapper = mount(DemandPublishView)
  })

  afterEach(() => { wrapper.unmount(); vi.useRealTimers() })

  it('先展示清晰的类型选择入口，人工选择后使用预设参与模式', async () => {
    expect(wrapper.find('#demand-title').exists()).toBe(false)
    for (const [category, mode] of [['EXPRESS', 'DIRECT_ACCEPT'], ['ERRAND', 'DIRECT_ACCEPT'], ['SECOND_HAND', 'SELECT_ONE'], ['STUDY_TUTORING', 'SELECT_ONE'], ['TEAM_UP', 'SELECT_MANY'], ['HELP', 'HELP']] as const) {
      if (wrapper.find('#demand-title').exists()) await wrapper.find('[data-testid="change-type"]').trigger('click')
      await wrapper.find(`[data-testid="category-card-${category}"]`).trigger('click')
      expect((wrapper.find('#demand-category').element as HTMLSelectElement).value).toBe(category)
      if (category === 'TEAM_UP') expect(wrapper.find('#demand-target-count').exists()).toBe(true)
      else expect(wrapper.find('#demand-target-count').exists()).toBe(false)
      expect(mode).toBeTruthy()
    }
    await wrapper.find('[data-testid="change-type"]').trigger('click')
    await wrapper.find('[data-testid="category-card-OTHER"]').trigger('click')
    expect(wrapper.find('#demand-interaction-mode').exists()).toBe(true)
    expect(wrapper.find('#demand-interaction-mode').findAll('option').map((option) => option.element.value)).not.toContain('HELP')
  })

  it('AI 草稿先供确认类型，确认后才进入共同表单', async () => {
    mockGenerate.mockResolvedValue(buildDraft())
    await openAi(wrapper)
    await generate(wrapper)
    expect(wrapper.find('[data-testid="ai-candidate"]').exists()).toBe(true)
    expect(wrapper.find('#demand-title').exists()).toBe(false)
    await acceptAiType(wrapper)
    expect(wrapper.find('#demand-title').exists()).toBe(true)
    expect((wrapper.find('#demand-title').element as HTMLInputElement).value).toBe('代取快递并送到南区宿舍')
    expect(wrapper.find('.ai-form-legend').text()).toContain('AI 草稿仅供参考')
    await wrapper.find('#demand-title').setValue('我再次检查并修改了标题')
    expect(mockCreate).not.toHaveBeenCalled()
  })

  it('AI 未返回开始时间时按当前时间填写，不把缺失团队人数伪装成默认值', async () => {
    mockGenerate.mockResolvedValue(buildDraft({ category: 'TEAM_UP', interactionMode: 'SELECT_MANY', startTime: null, targetParticipantCount: null, missingFields: ['targetParticipantCount'] }))
    await openAi(wrapper, '需要 5 人一起组队')
    await generate(wrapper)
    await acceptAiType(wrapper)
    expect((wrapper.find('#demand-start-datetime').element as HTMLInputElement).value).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
    expect((wrapper.find('#demand-target-count').element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('.ai-form-legend').text()).toContain('AI 草稿仅供参考')
  })

  it('缺失开始时间时采用生成请求发起时的当前时间', async () => {
    await openAi(wrapper)
    mockGenerate.mockResolvedValue(buildDraft({ startTime: null }))
    const beforeGenerate = Date.now()
    await generate(wrapper)
    const afterGenerate = Date.now()
    await acceptAiType(wrapper)
    const startValue = (wrapper.find('#demand-start-datetime').element as HTMLInputElement).value
    expect(startValue).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
    const startMs = new Date(startValue).getTime()
    expect(Number.isNaN(startMs)).toBe(false)
    // startDateTime 应在生成请求发起前后范围内，而非 applyAiDraft（确认）阶段
    expect(startMs).toBeGreaterThanOrEqual(beforeGenerate - 60_000)
    expect(startMs).toBeLessThanOrEqual(afterGenerate + 60_000)
  })

  it('AI 重生成完整替换上一版字段；空奖励不继承旧草稿值', async () => {
    mockGenerate.mockResolvedValueOnce(buildDraft({ contactInfo: 'QQ: 123456', tags: ['旧标签'] }))
    mockGenerate.mockResolvedValueOnce(buildDraft({ title: '第二版需求', description: null, location: null, reward: null, tags: [], contactInfo: null }))
    await openAi(wrapper)
    await generate(wrapper)
    await wrapper.find('[data-testid="ai-generate-button"]').trigger('click')
    await flushPromises()
    await acceptAiType(wrapper)
    expect((wrapper.find('#demand-title').element as HTMLInputElement).value).toBe('第二版需求')
    expect((wrapper.find('#demand-description').element as HTMLTextAreaElement).value).toBe('')
    expect((wrapper.find('#demand-location').element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('#demand-tags').element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('#demand-contact').element as HTMLInputElement).value).toBe('')
  })

  it('AI 失败时保留自然语言输入并允许重试，不进入表单', async () => {
    mockGenerate.mockRejectedValueOnce(new Error('服务暂时不可用'))
    await openAi(wrapper, '帮我找人辅导高数，奖励 20 校邻币')
    await generate(wrapper)
    expect(wrapper.find('[data-testid="ai-error"]').exists()).toBe(true)
    expect((wrapper.find('[data-testid="ai-prompt-input"]').element as HTMLTextAreaElement).value).toContain('辅导高数')
    expect(wrapper.find('#demand-title').exists()).toBe(false)
    mockGenerate.mockResolvedValueOnce(buildDraft())
    await generate(wrapper)
    expect(wrapper.find('[data-testid="ai-candidate"]').exists()).toBe(true)
  })

  it('OTHER 不允许 AI 的 HELP 参与方式，要求用户补选合法方式', async () => {
    mockGenerate.mockResolvedValue(buildDraft({ category: 'OTHER', interactionMode: 'HELP' }))
    await openAi(wrapper, '其他需求')
    await generate(wrapper)
    expect(wrapper.find('[data-testid="ai-candidate-issue"]').text()).toContain('参与方式')
    await acceptAiType(wrapper)
    expect(wrapper.find('#demand-title').exists()).toBe(false)
    await wrapper.find('#ai-mode-confirm').setValue('SELECT_ONE')
    await acceptAiType(wrapper)
    expect(wrapper.find('#demand-title').exists()).toBe(true)
  })

  it('联系方式映射到独立字段，奖励明确使用校邻币单位', async () => {
    mockGenerate.mockResolvedValue(buildDraft({ contactInfo: 'QQ: 25984515619', reward: null, missingFields: ['reward'] }))
    await openAi(wrapper)
    await generate(wrapper)
    await acceptAiType(wrapper)
    expect((wrapper.find('#demand-contact').element as HTMLInputElement).value).toBe('QQ: 25984515619')
    expect(wrapper.text()).toContain('奖励（校邻币）')
    expect((wrapper.find('#demand-reward').element as HTMLInputElement).value).toBe('')
  })

  it('恢复已保存草稿后保留人工编辑，并以创建结果 ID 导航到详情', async () => {
    await wrapper.find('[data-testid="category-card-EXPRESS"]').trigger('click')
    await wrapper.find('#demand-title').setValue('帮我取一份文件')
    await wrapper.find('#demand-description').setValue('下午前往图书馆')
    await wrapper.find('#demand-category').setValue('EXPRESS')
    await wrapper.find('#demand-zone').setValue('GULOU')
    await wrapper.find('#demand-location').setValue('图书馆')
    await wrapper.find('#demand-reward').setValue('0')
    await wrapper.find('#demand-start-datetime').setValue('2026-10-08T10:00')
    await wrapper.find('#demand-end-datetime').setValue('2026-10-08T11:00')
    await wrapper.find('[data-testid="submit-demand"]').trigger('click')
    await flushPromises()
    expect(mockCreate).toHaveBeenCalledTimes(1)
    expect(mockPush).toHaveBeenCalledWith({ name: 'DemandDetail', params: { id: 'd-42' } })
  })

  it('AI 确认不会静默覆盖已填写的人工草稿', async () => {
    await wrapper.find('[data-testid="category-card-EXPRESS"]').trigger('click')
    await wrapper.find('#demand-title').setValue('我手动写的标题')
    await wrapper.find('[data-testid="change-type"]').trigger('click')
    mockGenerate.mockResolvedValue(buildDraft({ title: 'AI 新标题' }))
    mockConfirm.mockResolvedValue(false)
    await openAi(wrapper)
    await generate(wrapper)
    await acceptAiType(wrapper)
    expect(wrapper.find('#demand-title').exists()).toBe(false)
    const savedDraft = JSON.parse(localStorage.getItem('campushub.demand.draft') ?? '{}')
    expect(savedDraft.title).toBe('我手动写的标题')
    expect(savedDraft.manualFields).toContain('title')
    expect(mockCreate).not.toHaveBeenCalled()
  })
})
