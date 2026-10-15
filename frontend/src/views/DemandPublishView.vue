<script setup lang="ts">
import { computed, nextTick, reactive, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, onBeforeRouteLeave } from 'vue-router'
import { ArrowRight, BookOpenText, Footprints, MessageCircleQuestion, PackageOpen, Shapes, ShoppingBag, Sparkles, UsersRound } from '@lucide/vue'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatDateTime } from '@/utils/format'
import { handleError } from '@/utils/errorHandler'
import { useConfirm } from '@/composables/useDialog'
import { loadDemandDraft, saveDemandDraft, clearDemandDraft } from '@/utils/demandDraft'
import ImageUploader from '@/components/ImageUploader.vue'

const router = useRouter()
const store = useCampusHubStore()
const message = ref('')
const error = ref('')
const rewardError = ref('')
const submitting = ref(false)
const published = ref(false)
const createdDemandId = ref('')
const pageStage = ref<'choose' | 'form'>('choose')
const aiCandidate = ref<import('@/types/campushub').AiDemandDraft | null>(null)
const aiCandidateCategory = ref('')
const aiCandidateMode = ref('')
const aiCandidateIssue = ref('')
const aiCandidateManuallyChanged = ref(false)
const aiCandidateGeneratedAt = ref('')

const draftDiscarded = ref(false)

// 字段顺序用于生成“请完善以下信息：…”提示，并定位第一个错误字段
const FIELD_ORDER = [
  'title',
  'category',
  'campusZone',
  'location',
  'reward',
  'targetParticipantCount',
  'interactionMode',
  'startTime',
  'endTime'
] as const

type FieldKey = (typeof FIELD_ORDER)[number]

const FIELD_LABELS: Record<FieldKey, string> = {
  title: '标题',
  category: '分类',
  campusZone: '校区',
  location: '地点',
  reward: '报酬',
  targetParticipantCount: '目标人数',
  interactionMode: '参与方式',
  startTime: '开始时间',
  endTime: '结束时间'
}

const errors = reactive<Record<FieldKey, string>>({
  title: '',
  category: '',
  location: '',
  reward: '',
  startTime: '',
  endTime: '',
  campusZone: '',
  targetParticipantCount: '',
  interactionMode: ''
})

const form = reactive({
  title: '',
  description: '',
  category: '' as '' | (typeof DEMAND_CATEGORY_OPTIONS)[number],
  campusZone: '' as CampusZone | '',
  location: '',
  startDateTime: '',
  endDateTime: '',
  reward: '',
  targetParticipantCount: '' as string,
  interactionMode: '' as string,
  tags: '',
  images: [] as string[],
  contactInfo: '',
  anonymous: false
})
const previousCategory = ref('')

// 保存草稿时保留字段来源，让用户区分 AI 建议、待补充字段与自己的修改。
const aiFilledFields = ref<Set<string>>(new Set())
const aiPendingFields = ref<Set<string>>(new Set())
const manualFields = ref<Set<string>>(new Set())
const aiAssisted = ref(false)

function markFieldManual(field: string): void {
  aiFilledFields.value.delete(field)
  aiPendingFields.value.delete(field)
  manualFields.value.add(field)
}

function aiFieldStatus(_field: string): string {
  return ''
}

function getSavedDraft() {
  return {
    ...form,
    aiAssisted: aiAssisted.value,
    aiFilledFields: [...aiFilledFields.value],
    aiPendingFields: [...aiPendingFields.value],
    manualFields: [...manualFields.value]
  }
}

async function chooseCategory(category: string): Promise<void> {
  const previous = form.category
  if (previous && previous !== category && hasFormContent.value) {
    const confirmed = await useConfirm('切换需求类型', '切换类型会清除当前类型不适用的参与人数或参与方式。是否继续？', { confirmText: '切换类型', cancelText: '继续填写' })
    if (!confirmed) return
  }
  form.category = category as typeof form.category
  previousCategory.value = category
  markFieldManual('category')
  markFieldManual('interactionMode')
  markFieldManual('targetParticipantCount')
  form.interactionMode = category === 'OTHER' ? '' : modeForCategory(category)
  if (modeForCategory(category, form.interactionMode) !== 'SELECT_MANY') form.targetParticipantCount = ''
  pageStage.value = 'form'
  error.value = ''
  runValidations()
}

async function onCategoryChange(): Promise<void> {
  const nextCategory = form.category
  if (previousCategory.value && previousCategory.value !== nextCategory) {
    const hasModeData = form.targetParticipantCount || form.interactionMode
    if (hasModeData) {
      const confirmed = await useConfirm('切换需求类型', '当前参与方式或目标人数将被清除，是否继续？', { confirmText: '切换类型', cancelText: '保留当前类型' })
      if (!confirmed) {
        form.category = previousCategory.value as typeof form.category
        return
      }
    }
    form.targetParticipantCount = ''
    form.interactionMode = nextCategory === 'OTHER' ? '' : modeForCategory(nextCategory)
    previousCategory.value = nextCategory
    markFieldManual('interactionMode')
    markFieldManual('targetParticipantCount')
  }
  errors.category = ''
  markFieldManual('category')
}

function onAiCategoryChange(): void {
  aiCandidateManuallyChanged.value = true
  aiCandidateIssue.value = ''
  if (aiCandidateCategory.value === 'OTHER') {
    const suggested = aiCandidate.value?.interactionMode ?? ''
    aiCandidateMode.value = ['DIRECT_ACCEPT', 'SELECT_ONE', 'SELECT_MANY'].includes(suggested) ? suggested : ''
  } else {
    aiCandidateMode.value = modeForCategory(aiCandidateCategory.value)
  }
}

function returnToTypePicker(): void {
  pageStage.value = 'choose'
  aiCandidate.value = null
  aiCandidateIssue.value = ''
}

// 业务规则：TEAM_UP 固定为 SELECT_MANY，不需要用户选择 interactionMode
const effectiveInteractionMode = computed<string>(() => {
  if (form.category === 'TEAM_UP') return 'SELECT_MANY'
  if (form.category === 'OTHER') return form.interactionMode || 'DIRECT_ACCEPT'
  if (form.category === 'EXPRESS' || form.category === 'ERRAND') return 'DIRECT_ACCEPT'
  if (form.category === 'SECOND_HAND' || form.category === 'STUDY_TUTORING') return 'SELECT_ONE'
  if (form.category === 'HELP') return 'HELP'
  return 'DIRECT_ACCEPT'
})

function modeForCategory(category: string, otherMode = ''): string {
  if (category === 'TEAM_UP') return 'SELECT_MANY'
  if (category === 'EXPRESS' || category === 'ERRAND') return 'DIRECT_ACCEPT'
  if (category === 'SECOND_HAND' || category === 'STUDY_TUTORING') return 'SELECT_ONE'
  if (category === 'HELP') return 'HELP'
  return otherMode
}

const categoryCards = [
  { value: 'EXPRESS', title: '快递代取', description: '取件、送件，写清地点和时间', icon: PackageOpen },
  { value: 'ERRAND', title: '跑腿代办', description: '校园内代办与临时跑腿', icon: Footprints },
  { value: 'SECOND_HAND', title: '二手交易', description: '发布物品，等待同学响应', icon: ShoppingBag },
  { value: 'STUDY_TUTORING', title: '学习辅导', description: '寻找课程辅导与学习伙伴', icon: BookOpenText },
  { value: 'TEAM_UP', title: '活动组队', description: '招募成员，一起完成活动', icon: UsersRound },
  { value: 'HELP', title: '互助问答', description: '提出问题，收集同学回答', icon: MessageCircleQuestion },
  { value: 'OTHER', title: '其他需求', description: '选择最符合需求的参与方式', icon: Shapes }
]

function formatCampusCoins(amount: number): string {
  return `${Number(amount || 0).toFixed(0)} 校邻币`
}

// 是否需要展示“目标人数”输入框（仅 SELECT_MANY 模式需要，包含 TEAM_UP 与 OTHER+SELECT_MANY）
const showTargetParticipantCount = computed(() => effectiveInteractionMode.value === 'SELECT_MANY')

// 是否需要展示“参与方式”选择框（仅 OTHER 分类需要用户手动选择）
const showInteractionMode = computed(() => form.category === 'OTHER')
const typeFormCopy = computed(() => {
  switch (form.category) {
    case 'EXPRESS': return { title: '任务标题', description: '取件与送达要求', hint: '写清取件地点、送达位置与可执行时间。' }
    case 'ERRAND': return { title: '代办事项', description: '代办要求', hint: '说明需要代办的事项、交接地点和时间。' }
    case 'SECOND_HAND': return { title: '物品名称', description: '物品描述', hint: '介绍物品现状与交接安排；不要把校邻币奖励当作商品售价。' }
    case 'STUDY_TUTORING': return { title: '学习主题', description: '学习目标与辅导要求', hint: '说明课程内容、希望解决的问题和辅导时间。' }
    case 'TEAM_UP': return { title: '活动名称', description: '活动内容与组队要求', hint: '目标人数表示最终希望选定的成员数，不是当前报名数。' }
    case 'HELP': return { title: '问题标题', description: '问题与已尝试的方法', hint: '描述具体问题，其他同学可以提交回答；本流程不会创建正式订单。' }
    default: return { title: '需求标题', description: '需求描述', hint: '补充时间、地点和需求细节。' }
  }
})

const titlePlaceholder = computed(() => {
  switch (form.category) {
    case 'EXPRESS': return '如：帮取快递送到宿舍'
    case 'ERRAND': return '如：代买午餐'
    case 'SECOND_HAND': return '如：高等数学教材'
    case 'STUDY_TUTORING': return '如：高数辅导'
    case 'TEAM_UP': return '如：足球组队'
    case 'HELP': return '如：高数作业求助'
    default: return '简要描述你的需求'
  }
})

const locationPlaceholder = computed(() => {
  switch (form.category) {
    case 'EXPRESS': return '取件地点/送达地点'
    case 'ERRAND': return '代办地点'
    case 'SECOND_HAND': return '交接地点'
    case 'STUDY_TUTORING': return '辅导地点'
    case 'TEAM_UP': return '活动地点'
    case 'HELP': return '如：线上/图书馆'
    default: return '如：北区、图书馆'
  }
})

// datetime-local 输入格式为 YYYY-MM-DDTHH:MM，与 startTime/endTime 兼容
const startTime = computed(() => form.startDateTime)
const endTime = computed(() => form.endDateTime)

const forbiddenForAdmin = computed(() => store.currentUser?.role === 'ADMIN')
const canSubmit = computed(() => !submitting.value && !published.value && !forbiddenForAdmin.value)

function getRewardText(): string {
  return String(form.reward ?? '').trim()
}

function isEmpty(value: string): boolean {
  return !value || !value.trim()
}

function setFieldError(field: FieldKey, messageText: string): void {
  errors[field] = messageText
}

function clearAllErrors(): void {
  for (const key of FIELD_ORDER) {
    errors[key] = ''
  }
}

// 唯一的校验入口：所有校验逻辑集中在此处，避免 isFormValid 与 runValidations 各自维护一套规则
function runValidations(): void {
  clearAllErrors()

  if (isEmpty(form.title)) {
    setFieldError('title', '请填写标题')
  } else if (form.title.trim().length < 3) {
    setFieldError('title', '标题至少 3 个字符')
  } else if (form.title.trim().length > 200) {
    setFieldError('title', '标题不能超过 200 个字符')
  }

  if (isEmpty(form.category)) {
    setFieldError('category', '请选择分类')
  }

  if (isEmpty(form.campusZone)) {
    setFieldError('campusZone', '请选择校区')
  }

  if (isEmpty(form.location)) {
    setFieldError('location', '请填写地点，例如：图书馆/宿舍区')
  }

  const rewardText = getRewardText()
  if (!rewardText) {
    setFieldError('reward', '请填写报酬')
  } else {
    const amount = Number(rewardText)
    if (Number.isNaN(amount) || amount < 0) {
      setFieldError('reward', '请输入有效的报酬（可为 0）')
    }
  }

  if (effectiveInteractionMode.value === 'SELECT_MANY') {
    const countText = String(form.targetParticipantCount ?? '').trim()
    if (!countText) {
      setFieldError('targetParticipantCount', '请填写目标人数')
    } else {
      const count = Number(countText)
      if (Number.isNaN(count)) {
        setFieldError('targetParticipantCount', '目标人数必须是整数')
      } else if (!Number.isInteger(count)) {
        setFieldError('targetParticipantCount', '目标人数必须是整数，不能为小数')
      } else if (count < 1) {
        setFieldError('targetParticipantCount', '目标人数必须为不小于 1 的正整数')
      } else if (count > 100) {
        setFieldError('targetParticipantCount', '目标人数不能超过 100')
      }
    }
  }

  if (form.category === 'OTHER') {
    if (!form.interactionMode) {
      setFieldError('interactionMode', '请选择参与方式')
    } else if (!['DIRECT_ACCEPT', 'SELECT_ONE', 'SELECT_MANY'].includes(form.interactionMode)) {
      setFieldError('interactionMode', '请选择有效的参与方式')
    }
  }

  if (!form.startDateTime) {
    setFieldError('startTime', '请选择开始时间')
  }
  if (!form.endDateTime) {
    setFieldError('endTime', '请选择结束时间')
  }

  // 验证开始和结束时间
  if (startTime.value && endTime.value) {
    const start = new Date(startTime.value).getTime()
    const end = new Date(endTime.value).getTime()
    if (Number.isNaN(start) || Number.isNaN(end) || start >= end) {
      setFieldError('startTime', '请确保开始时间早于结束时间')
      setFieldError('endTime', '请确保开始时间早于结束时间')
    }
  }
}

// 仅基于 errors 对象判断表单是否有效，避免重复规则
const isFormValid = computed(() => {
  for (const key of FIELD_ORDER) {
    if (errors[key]) return false
  }
  return true
})

// 当前缺失/错误字段的标签列表，用于生成“请完善以下信息：…”提示
const missingFieldLabels = computed<string[]>(() => {
  const labels: string[] = []
  for (const key of FIELD_ORDER) {
    if (errors[key]) {
      labels.push(FIELD_LABELS[key])
    }
  }
  return labels
})

const firstErrorField = computed<FieldKey | null>(() => {
  for (const key of FIELD_ORDER) {
    if (errors[key]) return key
  }
  return null
})

// 自动滚动并聚焦第一个错误字段
async function focusFirstError(): Promise<void> {
  const field = firstErrorField.value
  if (!field) return
  await nextTick()
  const el = document.getElementById(getFieldElementId(field))
  if (!el) return
  try {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  } catch {
    // 某些环境不支持 scrollIntoView 平滑滚动
    el.scrollIntoView()
  }
  // 给滚动一点时间后再聚焦，避免被 scrollIntoView 中断
  setTimeout(() => {
    try {
      ;(el as HTMLElement).focus?.()
    } catch {
      // ignore focus errors
    }
  }, 240)
}

function getFieldElementId(field: FieldKey): string {
  switch (field) {
    case 'title': return 'demand-title'
    case 'category': return 'demand-category'
    case 'campusZone': return 'demand-zone'
    case 'location': return 'demand-location'
    case 'reward': return 'demand-reward'
    case 'targetParticipantCount': return 'demand-target-count'
    case 'interactionMode': return 'demand-interaction-mode'
    case 'startTime': return 'demand-start-datetime'
    case 'endTime': return 'demand-end-datetime'
  }
}

// 草稿自动保存：任一字段变化时写入 localStorage
const hasFormContent = computed(() =>
  form.title.trim() || form.description.trim() || form.location.trim() ||
  form.category || form.campusZone || form.startDateTime || form.endDateTime ||
  String(form.reward ?? '').trim() ||
  form.tags.trim()
)

watch(form, () => {
  if (hasFormContent.value && !published.value) {
    saveDemandDraft(getSavedDraft())
  }
}, { deep: true })

// 路由离开前询问是否保留草稿
onBeforeRouteLeave(async (_to, _from, next) => {
  if (!hasFormContent.value || published.value || draftDiscarded.value) {
    clearDemandDraft()
    next()
    return
  }
  const keep = await useConfirm('保留草稿', '你填写的内容尚未发布，是否保留为草稿？离开后可以恢复已填写的内容。', { confirmText: '保留草稿', cancelText: '不保留' })
  if (!keep) {
    draftDiscarded.value = true
    clearDemandDraft()
  }
  next()
})

// 页面关闭 / 刷新时自动保留草稿（仅当用户未明确丢弃草稿时）
onBeforeUnmount(() => {
  if (hasFormContent.value && !published.value && !draftDiscarded.value) {
    saveDemandDraft(getSavedDraft())
  }
})

// 恢复草稿：targetParticipantCount 等字段统一用 String() 转换，兼容旧草稿中保存的数字
onMounted(() => {
  const draft = loadDemandDraft()
  if (draft) {
    form.title = String(draft.title ?? '')
    form.description = String(draft.description ?? '')
    form.category = (isLegalCategory(draft.category) ? draft.category : '') as typeof form.category
    form.campusZone = (isLegalCampusZone(draft.campusZone) ? draft.campusZone : '') as typeof form.campusZone
    form.location = String(draft.location ?? '')
    form.startDateTime = String(draft.startDateTime ?? '')
    form.endDateTime = String(draft.endDateTime ?? '')
    form.reward = String(draft.reward ?? '')
    const restoredMode = form.category === 'OTHER' && ['DIRECT_ACCEPT', 'SELECT_ONE', 'SELECT_MANY'].includes(draft.interactionMode)
      ? draft.interactionMode
      : form.category ? modeForCategory(form.category) : ''
    form.interactionMode = restoredMode
    form.targetParticipantCount = restoredMode === 'SELECT_MANY' ? String(draft.targetParticipantCount ?? '') : ''
    form.tags = String(draft.tags ?? '')
    form.images = Array.isArray(draft.images) ? draft.images.map((url: any) => String(url)) : []
    form.contactInfo = String(draft.contactInfo ?? '')
    form.anonymous = draft.anonymous === true
    aiAssisted.value = draft.aiAssisted === true
    aiFilledFields.value = new Set(Array.isArray(draft.aiFilledFields) ? draft.aiFilledFields : [])
    aiPendingFields.value = new Set(Array.isArray(draft.aiPendingFields) ? draft.aiPendingFields : [])
    manualFields.value = new Set(Array.isArray(draft.manualFields) ? draft.manualFields : [])
    previousCategory.value = form.category
    pageStage.value = 'form'
  }
})

async function submitDemand(): Promise<void> {
  if (submitting.value || published.value) return
  error.value = ''
  message.value = ''

  runValidations()
  if (!isFormValid.value) {
    const labels = missingFieldLabels.value
    error.value = labels.length > 0
      ? `请完善以下信息：${labels.join('、')}`
      : '请先填写所有必填项后再提交。'
    void focusFirstError()
    return
  }

  submitting.value = true
  try {
    if (!await useConfirm('确认发布', '确认发布此需求？发布后将进入审核流程。')) return
    // 构建提交数据，使用合并后的 datetime
    const submitData = {
      title: form.title,
      description: form.description,
      category: form.category,
      campusZone: form.campusZone,
      location: form.location,
      startTime: form.startDateTime,
      endTime: form.endDateTime,
      reward: form.reward,
      tags: form.tags,
      images: form.images,
      contactInfo: form.contactInfo,
      anonymous: form.anonymous,
      targetParticipantCount: effectiveInteractionMode.value === 'SELECT_MANY'
        ? Number(form.targetParticipantCount) || null
        : null,
      interactionMode: form.category === 'OTHER' ? form.interactionMode : null
    }
    const created = await store.createDemand(submitData)
    clearDemandDraft()
    message.value = '发布成功，等待审核'
    published.value = true
    createdDemandId.value = created.id
    try {
      await router.push({ name: 'DemandDetail', params: { id: created.id } })
    } catch {
      error.value = '需求已发布，但暂时无法打开详情；可稍后从需求列表进入。'
    }
  } catch (submitError) {
    error.value = handleError(submitError, '发布失败')
  } finally {
    submitting.value = false
  }
}

// 清除错误状态（datetime-local 单字段无需额外同步）
function clearStartTimeError(): void {
  errors.startTime = ''
}

function clearEndTimeError(): void {
  errors.endTime = ''
}

async function checkRewardBalance(): Promise<void> {
  rewardError.value = ''
  const rewardText = getRewardText()

  if (!rewardText) {
    rewardError.value = '请填写报酬'
    errors.reward = rewardError.value
    return
  }

  const amount = Number(rewardText)
  if (Number.isNaN(amount) || amount < 0) {
    rewardError.value = '请输入有效的报酬（可为 0）'
    errors.reward = rewardError.value
    return
  }

  if (amount === 0) {
    errors.reward = ''
    return
  }

  try {
    const balance = Number(store.currentUser?.balance ?? 0)
    const frozen = Number(store.currentUser?.frozenBalance ?? 0)
    const available = Math.max(0, balance - frozen)
    if (amount > available) {
      rewardError.value = `奖励不能超过当前可用余额 ${formatCampusCoins(available)}`
      errors.reward = rewardError.value
    }
  } catch {
    // ignore balance check errors
  }
}

// ========== AI 帮我发布 ==========
const AI_DIALOG_FIELD_LABELS: Record<string, string> = {
  title: '标题',
  description: '描述',
  category: '分类',
  campusZone: '校区',
  location: '地点',
  startTime: '开始时间',
  endTime: '结束时间',
  reward: '报酬',
  tags: '标签',
  interactionMode: '参与方式',
  targetParticipantCount: '目标人数'
}

const aiDialogOpen = ref(false)
const aiPrompt = ref('')
const aiLoading = ref(false)
const aiError = ref('')
const aiMissingHint = ref('')

function openAiDialog(): void {
  aiError.value = ''
  aiMissingHint.value = ''
  aiPrompt.value = ''
  aiCandidate.value = null
  aiCandidateIssue.value = ''
  aiDialogOpen.value = true
}

function closeAiDialog(): void {
  if (aiLoading.value) return
  aiDialogOpen.value = false
}

function toDateTimeLocal(iso: string | null): string {
  if (!iso) return ''
  const normalized = iso.trim().replace(' ', 'T')
  if (normalized.length >= 16) {
    return normalized.substring(0, 16)
  }
  return normalized
}

function isLegalCategory(value: string | null): value is typeof DEMAND_CATEGORY_OPTIONS[number] {
  return !!value && (DEMAND_CATEGORY_OPTIONS as readonly string[]).includes(value)
}

function isLegalCampusZone(value: string | null): value is CampusZone {
  if (!value) return false
  return (['GULOU', 'XIANLIN', 'SUZHOU'] as const).includes(value as CampusZone)
}

function currentLocalDateTime(): string {
  const now = new Date()
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function applyAiDraft(draft: import('@/types/campushub').AiDemandDraft): void {
  // 每次确认都以当前 AI 结果完整替换草稿，避免上一版的字段残留。
  aiAssisted.value = true
  aiFilledFields.value = new Set()
  aiPendingFields.value = new Set(draft.missingFields ?? [])
  manualFields.value = new Set()
  form.title = draft.title ?? ''
  form.description = draft.description ?? ''
  form.location = draft.location ?? ''
  form.startDateTime = draft.startTime ? toDateTimeLocal(draft.startTime) : aiCandidateGeneratedAt.value || currentLocalDateTime()
  if (draft.startTime) aiFilledFields.value.add('startTime')
  else aiPendingFields.value.add('startTime')
  form.endDateTime = draft.endTime ? toDateTimeLocal(draft.endTime) : ''
  // reward：null → 清空（不保留默认 '10'，避免用户误提交 10 校邻币）；0 → '0'（有效值，不误判为空）
  form.reward = draft.reward == null ? '' : String(draft.reward)
  // tags：数组完全替换，[] → ''，['打印','资料'] → '打印,资料'（不 append 旧值）
  form.tags = Array.isArray(draft.tags) ? draft.tags.join(',') : ''
  // category/campusZone：合法值覆盖，非法/null → 清空（整体替换，避免旧 category 拘留）
  form.category = (isLegalCategory(aiCandidateCategory.value) ? aiCandidateCategory.value : '') as typeof form.category
  previousCategory.value = form.category
  form.campusZone = (isLegalCampusZone(draft.campusZone) ? draft.campusZone : '') as typeof form.campusZone
  // interactionMode/targetParticipantCount：完全由本次 AI 决定，null → 清空（避免旧 category 的依赖字段残留）
  form.interactionMode = form.category === 'OTHER' ? aiCandidateMode.value : modeForCategory(form.category)
  form.targetParticipantCount = effectiveInteractionMode.value === 'SELECT_MANY' && draft.targetParticipantCount != null
    ? String(draft.targetParticipantCount)
    : ''
  form.images = []
  form.contactInfo = draft.contactInfo ?? ''
  form.anonymous = draft.anonymous === true

  if (draft.title) aiFilledFields.value.add('title')
  if (draft.description) aiFilledFields.value.add('description')
  if (draft.location) aiFilledFields.value.add('location')
  if (draft.endTime) aiFilledFields.value.add('endTime')
  if (draft.reward != null) aiFilledFields.value.add('reward')
  if (draft.tags?.length) aiFilledFields.value.add('tags')
  if (draft.campusZone && isLegalCampusZone(draft.campusZone)) aiFilledFields.value.add('campusZone')
  if (isLegalCategory(draft.category) && draft.category === form.category) aiFilledFields.value.add('category')
  else manualFields.value.add('category')
  if (form.category === 'OTHER') {
    if (draft.interactionMode && draft.interactionMode === form.interactionMode) aiFilledFields.value.add('interactionMode')
    else if (!form.interactionMode) aiPendingFields.value.add('interactionMode')
  }
  if (form.targetParticipantCount) aiFilledFields.value.add('targetParticipantCount')
  if (draft.contactInfo) aiFilledFields.value.add('contactInfo')
  if (draft.anonymous != null) aiFilledFields.value.add('anonymous')
  if (draft.missingFields?.includes('category')) aiPendingFields.value.delete('category')

  // missingFields 提示：转换为中文标签，引导用户补充
  if (draft.missingFields && draft.missingFields.length > 0) {
    const labels = draft.missingFields
      .map((f) => AI_DIALOG_FIELD_LABELS[f] || f)
      .filter(Boolean)
    aiMissingHint.value = labels.length > 0
      ? 'AI 草稿有未完成项，请按字段标记补充或确认。'
      : ''
  } else {
    aiMissingHint.value = ''
  }

  // 回填后触发一次校验，让现有表单校验接管
  runValidations()
  pageStage.value = 'form'
  aiCandidate.value = null
  aiDialogOpen.value = false
}

async function generateAiDraft(): Promise<void> {
  aiError.value = ''
  aiMissingHint.value = ''
  const prompt = aiPrompt.value.trim()
  if (!prompt) {
    aiError.value = '请先描述你想发布的需求'
    return
  }
  if (aiLoading.value) return
  if (aiCandidate.value && aiCandidateManuallyChanged.value) {
    const confirmed = await useConfirm('重新生成草稿', '重新生成会替换你刚确认的需求类型或参与方式。是否继续？', { confirmText: '重新生成', cancelText: '保留当前选择' })
    if (!confirmed) return
  }
  aiLoading.value = true
  aiCandidate.value = null
  aiCandidateIssue.value = ''
  aiCandidateManuallyChanged.value = false
  aiCandidateGeneratedAt.value = currentLocalDateTime()
  try {
    const draft = await store.generateDemandDraft(prompt)
    if (!draft || typeof draft !== 'object') throw new Error('AI 返回内容无效，请保留描述后重试')
    aiCandidate.value = draft
    aiCandidateCategory.value = isLegalCategory(draft.category) ? draft.category : ''
    const expectedMode = modeForCategory(aiCandidateCategory.value)
    aiCandidateMode.value = aiCandidateCategory.value === 'OTHER'
      ? (['DIRECT_ACCEPT', 'SELECT_ONE', 'SELECT_MANY'].includes(draft.interactionMode ?? '') ? draft.interactionMode! : '')
      : expectedMode
    aiCandidateIssue.value = !aiCandidateCategory.value
      ? 'AI 未能确认需求类型，请先选择类型。'
      : aiCandidateCategory.value === 'OTHER' && !aiCandidateMode.value
        ? '其他需求还需要你确认参与方式。'
        : expectedMode && draft.interactionMode && draft.interactionMode !== expectedMode
          ? 'AI 识别的参与方式与该类型规则不一致，继续前请确认需求类型。'
          : ''
    const labels = (draft.missingFields ?? []).map((field) => AI_DIALOG_FIELD_LABELS[field] || field).filter(Boolean)
    aiMissingHint.value = labels.length ? `待补充：${labels.join('、')}` : ''
  } catch (err) {
    aiError.value = handleError(err, 'AI 服务暂时不可用，请稍后重试')
  } finally {
    aiLoading.value = false
  }
}

async function confirmAiType(): Promise<void> {
  if (!aiCandidate.value || !isLegalCategory(aiCandidateCategory.value)) {
    aiCandidateIssue.value = '请选择有效的需求类型。'
    return
  }
  if (aiCandidateCategory.value === 'OTHER' && !['DIRECT_ACCEPT', 'SELECT_ONE', 'SELECT_MANY'].includes(aiCandidateMode.value)) {
    aiCandidateIssue.value = '请选择一种允许的参与方式。'
    return
  }
  if (hasFormContent.value) {
    const confirmed = await useConfirm('替换当前草稿', '确认类型后，AI 草稿会替换当前表单内容。你可以取消并继续编辑现有草稿。', { confirmText: '使用 AI 草稿', cancelText: '返回编辑' })
    if (!confirmed) return
  }
  applyAiDraft(aiCandidate.value)
}
</script>

<template>
  <div class="page-grid two-column">
    <section class="form-panel">
      <template v-if="forbiddenForAdmin">
        <div class="page-head">
          <div>
            <h2 class="page-title">发布需求</h2>
            <p class="page-summary">管理员账号无法发布需求</p>
          </div>
          <button type="button" class="button primary" @click="router.back()">← 返回</button>
        </div>
        <div style="padding:24px">
          <p class="hero-badge">管理员账号不能发布需求。如需执行管理操作，请前往管理后台。</p>
        </div>
      </template>
      <template v-else-if="published">
        <div class="page-head">
          <div>
            <h2 class="page-title">发布需求</h2>
            <p class="page-summary">发布成功，等待审核</p>
          </div>
          <button type="button" class="button primary" @click="createdDemandId && router.push({ name: 'DemandDetail', params: { id: createdDemandId } })">查看需求详情</button>
        </div>
        <div style="padding: 24px;">
          <p class="hero-badge">发布成功，需求已进入审核流程。</p>
          <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
        </div>
      </template>
      <template v-else-if="pageStage === 'choose'">
        <div class="page-head">
          <div>
            <h2 class="page-title">发布需求</h2>
            <p class="page-summary">先选一种最贴近你需求的类型，下一步会只展示相关填写项。</p>
          </div>
          <button type="button" class="button" @click="router.back()">返回</button>
        </div>

        <button type="button" class="ai-choice" data-testid="ai-publish-entry" @click="openAiDialog">
          <span class="ai-choice-mark" aria-hidden="true"><Sparkles :size="21" /></span>
          <span><strong>AI 帮我整理需求</strong><small>直接描述想做的事，检查草稿并确认类型后继续</small></span>
          <ArrowRight class="ai-choice-arrow" :size="19" aria-hidden="true" />
        </button>
        <div class="type-choice-grid" aria-label="选择需求类型">
          <button v-for="item in categoryCards" :key="item.value" type="button" class="type-choice-card"
            :data-testid="`category-card-${item.value}`" @click="chooseCategory(item.value)">
            <span class="type-choice-icon" aria-hidden="true"><component :is="item.icon" :size="21" :stroke-width="1.8" /></span>
            <span class="type-choice-copy"><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span>
            <span class="type-choice-arrow" aria-hidden="true">↗</span>
          </button>
        </div>
        <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

        <div v-if="aiDialogOpen" class="ai-modal-mask" data-testid="ai-modal" @click.self="closeAiDialog">
          <div class="ai-modal" role="dialog" aria-modal="true" aria-labelledby="ai-modal-title">
            <div class="ai-modal-head">
              <h3 id="ai-modal-title">AI 帮我整理需求</h3>
              <button type="button" class="ai-modal-close" :disabled="aiLoading" @click="closeAiDialog">×</button>
            </div>
            <div class="ai-modal-body">
              <label for="ai-prompt-input" class="ai-modal-label">描述你想发布的内容</label>
              <textarea id="ai-prompt-input" v-model="aiPrompt" class="ai-prompt-input" rows="4" maxlength="1000"
                placeholder="例如：明天下午帮我从驿站取快递送到宿舍，奖励 10 校邻币" :disabled="aiLoading" data-testid="ai-prompt-input"></textarea>
              <p class="input-help">AI 只生成草稿，不会替你发布。奖励单位为校邻币；人民币等其他货币需要你在表单中确认。</p>
              <p v-if="aiError" class="ai-error" data-testid="ai-error">{{ aiError }}</p>
              <div v-if="aiCandidate" class="ai-candidate" data-testid="ai-candidate">
                <p class="ai-candidate-label">草稿已生成 · 请检查并确认需求类型</p>
                <dl class="ai-candidate-summary">
                  <div><dt>标题</dt><dd>{{ aiCandidate.title || '待补充' }}</dd></div>
                  <div><dt>描述</dt><dd>{{ aiCandidate.description || '未填写' }}</dd></div>
                  <div><dt>地点与时间</dt><dd>{{ [aiCandidate.campusZone, aiCandidate.location, aiCandidate.startTime, aiCandidate.endTime].filter(Boolean).join(' · ') || '待补充' }}</dd></div>
                  <div><dt>奖励</dt><dd>{{ aiCandidate.reward == null ? '未识别校邻币奖励 · 请确认；不会换算其他货币' : `${aiCandidate.reward} 校邻币` }}</dd></div>
                  <div v-if="aiCandidate.targetParticipantCount != null"><dt>目标人数</dt><dd>{{ aiCandidate.targetParticipantCount }} 人</dd></div>
                </dl>
                <label for="ai-category-confirm">需求类型</label>
                <select id="ai-category-confirm" v-model="aiCandidateCategory" @change="onAiCategoryChange">
                  <option value="">请选择类型</option>
                  <option v-for="category in DEMAND_CATEGORY_OPTIONS" :key="category" :value="category">{{ formatDemandCategory(category) }}</option>
                </select>
                <template v-if="aiCandidateCategory === 'OTHER'">
                  <label for="ai-mode-confirm">参与方式</label>
                  <select id="ai-mode-confirm" v-model="aiCandidateMode" @change="aiCandidateManuallyChanged = true">
                    <option value="">请选择参与方式</option>
                    <option value="DIRECT_ACCEPT">直接接单</option>
                    <option value="SELECT_ONE">选择一人</option>
                    <option value="SELECT_MANY">组队选择</option>
                  </select>
                </template>
                <p v-if="aiCandidateIssue" class="ai-error" data-testid="ai-candidate-issue">{{ aiCandidateIssue }}</p>
                <p v-if="aiMissingHint" class="input-help" data-testid="ai-missing-hint">{{ aiMissingHint }}</p>
                <button type="button" class="button primary" data-testid="ai-type-confirm" @click="confirmAiType">确认类型并检查草稿</button>
              </div>
            </div>
            <div class="ai-modal-foot">
              <button type="button" class="button" :disabled="aiLoading" @click="closeAiDialog">取消</button>
              <button type="button" class="button primary" :disabled="aiLoading || !aiPrompt.trim()" data-testid="ai-generate-button" @click="generateAiDraft">
                {{ aiLoading ? '正在整理…' : aiCandidate ? '重新生成草稿' : '生成需求草稿' }}
              </button>
            </div>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="page-head">
          <div>
            <h2 class="page-title">发布需求</h2>
            <p class="page-summary">{{ typeFormCopy.hint }} 检查填写内容后，再由你确认发布。</p>
          </div>
          <button type="button" class="button" data-testid="change-type" @click="returnToTypePicker">更换类型</button>
        </div>

        <div class="form-grid two-column">
          <p v-if="aiAssisted" class="ai-form-legend" style="grid-column: 1 / -1;" aria-live="polite">
            AI 草稿仅供参考，请逐项检查后再发布。
          </p>
          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-title">{{ typeFormCopy.title }} <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('title')" class="field-origin">{{ aiFieldStatus('title') }}</p>
            <input id="demand-title" v-model="form.title" maxlength="200" :placeholder="titlePlaceholder" @input="errors.title = ''; markFieldManual('title')" />
            <p v-if="errors.title" class="input-help" style="color: var(--danger)">{{ errors.title }}</p>
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-description">{{ typeFormCopy.description }}</label>
            <p v-if="aiFieldStatus('description')" class="field-origin">{{ aiFieldStatus('description') }}</p>
            <textarea id="demand-description" v-model="form.description" :placeholder="typeFormCopy.hint" @input="markFieldManual('description')"></textarea>
          </div>

          <div class="field">
            <label for="demand-category">分类 <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('category')" class="field-origin">{{ aiFieldStatus('category') }}</p>
            <select id="demand-category" v-model="form.category" @change="onCategoryChange">
              <option value="">请选择</option>
              <option v-for="category in DEMAND_CATEGORY_OPTIONS" :key="category" :value="category">{{ formatDemandCategory(category) }}</option>
            </select>
            <p v-if="errors.category" class="input-help" style="color: var(--danger)">{{ errors.category }}</p>
          </div>

          <div class="field">
            <label for="demand-zone">校区 <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('campusZone')" class="field-origin">{{ aiFieldStatus('campusZone') }}</p>
            <select id="demand-zone" v-model="form.campusZone" @change="errors.campusZone = ''; markFieldManual('campusZone')">
              <option value="">请选择</option>
              <option v-for="zone in campusZoneOptions()" :key="zone.value" :value="zone.value">{{ zone.label }}</option>
            </select>
            <p v-if="errors.campusZone" class="input-help" style="color: var(--danger)">{{ errors.campusZone }}</p>
          </div>

          <div class="field">
            <label for="demand-location">地点 <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('location')" class="field-origin">{{ aiFieldStatus('location') }}</p>
            <input id="demand-location" v-model="form.location" :placeholder="locationPlaceholder" @input="errors.location = ''; markFieldManual('location')" />
            <p v-if="errors.location" class="input-help" style="color: var(--danger)">{{ errors.location }}</p>
            <p class="input-help" style="visibility: hidden;">&nbsp;</p>
          </div>

          <div class="field">
            <label for="demand-reward">奖励（校邻币） <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('reward')" class="field-origin">{{ aiFieldStatus('reward') }}</p>
            <input id="demand-reward" v-model="form.reward" type="number" min="0" step="1" @blur="checkRewardBalance" @input="errors.reward = ''; markFieldManual('reward')" />
            <p class="input-help" style="margin-top:4px;">可用余额：{{ formatCampusCoins(Math.max(0, Number(store.currentUser?.balance ?? 0) - Number(store.currentUser?.frozenBalance ?? 0))) }}</p>
            <p v-if="rewardError || errors.reward" style="color: var(--danger); margin-top: 6px">{{ rewardError || errors.reward }}</p>
          </div>

          <div v-if="showTargetParticipantCount" class="field" style="grid-column: 1 / -1;">
            <label for="demand-target-count">目标人数 <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('targetParticipantCount')" class="field-origin">{{ aiFieldStatus('targetParticipantCount') }}</p>
            <input
              id="demand-target-count"
              v-model="form.targetParticipantCount"
              type="number"
              min="1"
              max="100"
              step="1"
              placeholder="需要几人"
              @input="errors.targetParticipantCount = ''; markFieldManual('targetParticipantCount')"
            />
            <p v-if="errors.targetParticipantCount" class="input-help" style="color: var(--danger)">{{ errors.targetParticipantCount }}</p>
          </div>

          <div v-if="showInteractionMode" class="field" style="grid-column: 1 / -1;">
            <label for="demand-interaction-mode">参与方式 <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('interactionMode')" class="field-origin">{{ aiFieldStatus('interactionMode') }}</p>
            <select id="demand-interaction-mode" v-model="form.interactionMode" @change="errors.interactionMode = ''; markFieldManual('interactionMode')">
              <option value="">请选择</option>
              <option value="DIRECT_ACCEPT">直接接单</option>
              <option value="SELECT_ONE">选择一人</option>
              <option value="SELECT_MANY">组队选择</option>
            </select>
            <p v-if="errors.interactionMode" class="input-help" style="color: var(--danger)">{{ errors.interactionMode }}</p>
          </div>

          <div class="field">
            <label for="demand-start-datetime">开始时间 <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('startTime')" class="field-origin">{{ aiFieldStatus('startTime') }}</p>
            <input
              id="demand-start-datetime"
              v-model="form.startDateTime"
              type="datetime-local"
              @change="clearStartTimeError(); markFieldManual('startTime')"
            />
            <p v-if="errors.startTime" class="input-help" style="color: var(--danger)">{{ errors.startTime }}</p>
          </div>

          <div class="field">
            <label for="demand-end-datetime">结束时间 <span class="required-mark">*</span></label>
            <p v-if="aiFieldStatus('endTime')" class="field-origin">{{ aiFieldStatus('endTime') }}</p>
            <input
              id="demand-end-datetime"
              v-model="form.endDateTime"
              type="datetime-local"
              :min="form.startDateTime || undefined"
              @change="clearEndTimeError(); markFieldManual('endTime')"
            />
            <p v-if="errors.endTime" class="input-help" style="color: var(--danger)">{{ errors.endTime }}</p>
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-tags">标签</label>
            <p v-if="aiFieldStatus('tags')" class="field-origin">{{ aiFieldStatus('tags') }}</p>
            <input id="demand-tags" v-model="form.tags" placeholder="近距离, 宿舍楼下" @input="markFieldManual('tags')" />
            <p class="input-help">标签用于表示任务特点，例如：加急、近距离。多个标签用逗号分隔。</p>
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label>{{ form.category === 'SECOND_HAND' ? '上传真实物品图片' : '上传图片（可选）' }}</label>
            <p v-if="aiFieldStatus('images')" class="field-origin">{{ aiFieldStatus('images') }}</p>
            <ImageUploader v-model="form.images" :max-count="6" :max-size-m-b="10" @update:modelValue="markFieldManual('images')" />
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-contact">联系方式（可选）</label>
            <p v-if="aiFieldStatus('contactInfo')" class="field-origin">{{ aiFieldStatus('contactInfo') }}</p>
            <input id="demand-contact" v-model="form.contactInfo" maxlength="200" placeholder="电话/微信/QQ/邮箱，接单后对方可见" @input="markFieldManual('contactInfo')" />
            <p class="input-help">填写后仅接单人可见，方便线下沟通。</p>
          </div>

          <label class="chip" style="grid-column: 1 / -1; width: fit-content;">
            <span v-if="aiFieldStatus('anonymous')" class="field-origin">{{ aiFieldStatus('anonymous') }}</span>
            <input v-model="form.anonymous" type="checkbox" style="margin: 0 8px 0 0;" @change="markFieldManual('anonymous')" />
            匿名发布
          </label>

          <button type="button" class="button primary" style="grid-column: 1 / -1;" data-testid="submit-demand" @click="submitDemand" :disabled="!canSubmit">
            {{ submitting ? '发布中...' : '发布需求' }}
          </button>
        </div>

        <p v-if="message" class="hero-badge">{{ message }}</p>
        <p v-if="aiMissingHint" class="hero-badge ai-success-hint" data-testid="ai-missing-hint">{{ aiMissingHint }}</p>
        <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

      </template>
    </section>

    <section v-if="pageStage === 'form'" class="preview-panel">
      <p class="eyebrow">实时预览</p>
      <h2 class="section-title">卡片展示</h2>
      <div class="list-card">
        <div class="status-row">
          <span class="chip is-warning">{{ form.anonymous ? '匿名发布' : '实名发布' }}</span>
          <span class="chip">{{ form.category ? formatDemandCategory(form.category as typeof DEMAND_CATEGORY_OPTIONS[number]) : '未选择分类' }}</span>
        </div>
        <div class="card-head">
          <h3>{{ form.title || '需求标题预览' }}</h3>
          <strong>{{ formatCampusCoins(Number(form.reward || 0)) }}</strong>
        </div>
        <p>{{ form.description || '这里会显示需求描述与执行细节。' }}</p>
        <div class="meta">地点：{{ form.location || '未填写地点' }}</div>
        <div class="meta" style="margin-top:6px">
          <span v-if="startTime || endTime">时间：{{ formatDateTime(startTime || '') }} - {{ formatDateTime(endTime || '') }}</span>
        </div>
        <!-- 图片预览 -->
        <div v-if="form.images.length > 0" class="card-thumb">
          <img :src="form.images[0]" alt="首张预览" class="thumb-img" />
          <span v-if="form.images.length > 1" class="thumb-count">+{{ form.images.length - 1 }}</span>
        </div>
        <div class="tag-row">
          <span class="badge is-neutral">{{ form.campusZone ? formatCampusZone(form.campusZone) : '请选择校区' }}</span>
          <template v-if="form.tags && form.tags.trim()">
            <span v-for="tag in form.tags.split(/[，,]/).filter(Boolean)" :key="tag" class="badge is-neutral">{{ tag.trim() }}</span>
          </template>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.required-mark {
  color: var(--danger);
  font-weight: 700;
  margin-left: 4px;
}

.preview-panel {
  align-self: start;
  position: sticky;
  top: 24px;
}

/* datetime-local 输入框样式 */
input[type="datetime-local"] {
  width: 100%;
  border: 1px solid rgba(0, 0, 0, 0.12);
  background: rgba(255, 255, 255, 0.84);
  color: var(--text-strong);
  border-radius: 16px;
  padding: 12px 14px;
  outline: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
  font-size: 14px;
  cursor: pointer;
}

input[type="datetime-local"]:focus {
  border-color: rgba(31, 95, 83, 0.46);
  box-shadow: 0 0 0 4px rgba(31, 95, 83, 0.12);
}

input[type="datetime-local"]::-webkit-calendar-picker-indicator {
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.18s ease;
}

input[type="datetime-local"]:hover::-webkit-calendar-picker-indicator {
  opacity: 0.8;
}

.card-thumb {
  position: relative;
  width: 100px;
  height: 100px;
  overflow: hidden;
  border-radius: 10px;
  margin: 8px 0;
}

.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-count {
  position: absolute;
  bottom: 6px;
  right: 6px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
}

/* ========== AI 帮我发布 ========== */
.ai-entry {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 24px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.ai-entry-hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted, rgba(0, 0, 0, 0.55));
}

.ai-button {
  background: linear-gradient(135deg, rgba(31, 95, 83, 0.92), rgba(46, 125, 110, 0.92));
  color: #fff;
  border: none;
  border-radius: 14px;
  padding: 8px 16px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
  white-space: nowrap;
}

.ai-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 14px rgba(31, 95, 83, 0.22);
}

.ai-modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 16px;
}

.ai-modal {
  width: 100%;
  max-width: 520px;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.22);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.ai-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.ai-modal-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.ai-modal-close {
  background: none;
  border: none;
  font-size: 22px;
  line-height: 1;
  color: rgba(0, 0, 0, 0.45);
  cursor: pointer;
  padding: 0 4px;
}

.ai-modal-close:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.ai-modal-body {
  padding: 20px;
  max-height: min(72vh, 680px);
  overflow-y: auto;
}

.ai-choice {
  width: 100%;
  display: grid;
  grid-template-columns: 48px 1fr 24px;
  align-items: center;
  gap: 16px;
  margin: 8px 0 24px;
  padding: 20px 22px;
  text-align: left;
  color: var(--text-strong);
  background: #edf5f1;
  border: 1px solid #cbded4;
  border-radius: 16px;
  cursor: pointer;
}

.ai-choice:hover { background: #e5f0ea; border-color: #9eb9aa; }
.ai-choice:focus-visible, .type-choice-card:focus-visible { outline: 3px solid #88ad98; outline-offset: 3px; }
.ai-choice-mark { width: 44px; height: 44px; display: grid; place-items: center; color: #286b53; background: #d9e9df; border-radius: 12px; }
.ai-choice strong, .ai-choice small, .type-choice-copy strong, .type-choice-copy small { display: block; }
.ai-choice strong { font-size: 16px; font-weight: 650; }
.ai-choice small { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
.ai-choice-arrow { color: #286b53; }

.type-choice-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.type-choice-card {
  display: grid;
  grid-template-columns: 42px 1fr 18px;
  align-items: center;
  gap: 13px;
  min-height: 84px;
  padding: 16px;
  text-align: left;
  color: var(--text-strong);
  background: #fff;
  border: 1px solid var(--border, #e3e8e4);
  border-radius: 14px;
  cursor: pointer;
  transition: border-color .16s ease, background-color .16s ease;
}
.type-choice-card:hover { background: #fbfcfb; border-color: #9eb9aa; }
.type-choice-icon { width: 40px; height: 40px; display: grid; place-items: center; color: #366d57; background: #edf4ef; border-radius: 11px; }
.type-choice-copy strong { font-size: 14px; font-weight: 650; }
.type-choice-copy small { margin-top: 4px; color: var(--text-muted); font-size: 12px; line-height: 1.45; }
.type-choice-arrow { color: #91a197; font-size: 16px; }

.ai-candidate { display: grid; gap: 8px; margin-top: 18px; padding: 16px; background: #f6f8f6; border: 1px solid #e1e9e3; border-radius: 14px; }
.ai-candidate-label { margin: 0 0 2px; color: #286b53; font-size: 13px; font-weight: 650; }
.ai-candidate-summary { display: grid; gap: 7px; margin: 0 0 6px; padding: 12px; background: #fff; border: 1px solid #e7ece8; border-radius: 10px; }
.ai-candidate-summary div { display: grid; grid-template-columns: 88px 1fr; gap: 8px; font-size: 12px; line-height: 1.5; }
.ai-candidate-summary dt { color: var(--text-muted); }
.ai-candidate-summary dd { min-width: 0; margin: 0; color: var(--text-strong); overflow-wrap: anywhere; }
.ai-form-legend { margin: 0; padding: 10px 12px; color: #355f49; background: #edf5ef; border-radius: 9px; font-size: 12px; line-height: 1.5; }
.field-origin { margin: 0 0 5px; color: #54735e; font-size: 11px; line-height: 1.35; }
.ai-candidate label { margin-top: 4px; font-size: 13px; font-weight: 600; }
.ai-candidate select { width: 100%; padding: 10px 32px 10px 12px; color: var(--text-strong); background: #fff; border: 1px solid #d8e0da; border-radius: 9px; background-image: var(--select-arrow); background-repeat: no-repeat; background-position: right 10px center; background-size: 12px 8px; }
.ai-candidate .button { justify-self: start; margin-top: 6px; }

@media (min-width: 1200px) {
  .type-choice-card:last-child { grid-column: 1 / -1; }
}

.ai-modal-label {
  display: block;
  font-size: 13px;
  color: var(--text-strong, rgba(0, 0, 0, 0.85));
  margin-bottom: 8px;
}

.ai-prompt-input {
  width: 100%;
  border: 1px solid rgba(0, 0, 0, 0.12);
  background: rgba(255, 255, 255, 0.92);
  border-radius: 14px;
  padding: 12px 14px;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
  outline: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.ai-prompt-input:focus {
  border-color: rgba(31, 95, 83, 0.46);
  box-shadow: 0 0 0 4px rgba(31, 95, 83, 0.12);
}

.ai-missing-hint {
  margin: 12px 0 0;
  padding: 10px 12px;
  background: rgba(245, 158, 11, 0.12);
  color: #92400e;
  border-radius: 10px;
  font-size: 13px;
}

.ai-error {
  margin: 12px 0 0;
  padding: 10px 12px;
  background: rgba(181, 71, 71, 0.12);
  color: var(--danger, #b54747);
  border-radius: 10px;
  font-size: 13px;
}

.ai-success-hint {
  background: rgba(245, 158, 11, 0.14);
  color: #92400e;
}

.ai-modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
</style>
