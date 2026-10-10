<script setup lang="ts">
import { computed, nextTick, reactive, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, onBeforeRouteLeave } from 'vue-router'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatMoney, formatDateTime } from '@/utils/format'
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
  interactionMode: '互动模式',
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
  reward: '10',
  targetParticipantCount: '' as string,
  interactionMode: '' as string,
  tags: '',
  images: [] as string[],
  contactInfo: '',
  anonymous: false
})

// AI 字段来源追踪：记录哪些字段是 AI 自动填写的（非用户手动修改）
// 新一轮 AI 生成时，上一轮 AI 自动填写的字段如果新 AI 返回 null，应清空而非残留
const aiFilledFields = ref<Set<string>>(new Set())

function markFieldManual(field: string): void {
  aiFilledFields.value.delete(field)
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

// 是否需要展示“目标人数”输入框（仅 SELECT_MANY 模式需要，包含 TEAM_UP 与 OTHER+SELECT_MANY）
const showTargetParticipantCount = computed(() => effectiveInteractionMode.value === 'SELECT_MANY')

// 是否需要展示“互动模式”选择框（仅 OTHER 分类需要用户手动选择）
const showInteractionMode = computed(() => form.category === 'OTHER')

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
      setFieldError('interactionMode', '请选择互动模式')
    } else if (!['DIRECT_ACCEPT', 'SELECT_ONE', 'SELECT_MANY'].includes(form.interactionMode)) {
      setFieldError('interactionMode', '互动模式无效')
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
  (String(form.reward ?? '').trim() && String(form.reward ?? '').trim() !== '10') ||
  form.tags.trim()
)

watch(form, () => {
  if (hasFormContent.value && !published.value) {
    saveDemandDraft({ ...form })
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
    saveDemandDraft({ ...form })
  }
})

// 恢复草稿：targetParticipantCount 等字段统一用 String() 转换，兼容旧草稿中保存的数字
onMounted(() => {
  const draft = loadDemandDraft()
  if (draft) {
    form.title = String(draft.title ?? '')
    form.description = String(draft.description ?? '')
    form.category = (draft.category || '') as typeof form.category
    form.campusZone = (draft.campusZone || '') as typeof form.campusZone
    form.location = String(draft.location ?? '')
    form.startDateTime = String(draft.startDateTime ?? '')
    form.endDateTime = String(draft.endDateTime ?? '')
    form.reward = String(draft.reward ?? '10')
    form.targetParticipantCount = String(draft.targetParticipantCount ?? '')
    form.interactionMode = String(draft.interactionMode ?? '')
    form.tags = String(draft.tags ?? '')
    form.images = Array.isArray(draft.images) ? draft.images.map((url: any) => String(url)) : []
    form.contactInfo = String(draft.contactInfo ?? '')
    form.anonymous = Boolean(draft.anonymous ?? false)
  }
})

async function submitDemand(): Promise<void> {
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

  if (submitting.value || published.value) {
    return
  }

  if (!await useConfirm('确认发布', '确认发布此需求？发布后将进入审核流程。')) return

  submitting.value = true

  try {
    // 构建提交数据，使用合并后的 datetime
    const submitData = {
      ...form,
      startTime: form.startDateTime,
      endTime: form.endDateTime,
      images: form.images,
      // TEAM_UP 与 OTHER+SELECT_MANY 都属于 effectiveInteractionMode === SELECT_MANY
      targetParticipantCount: effectiveInteractionMode.value === 'SELECT_MANY'
        ? Number(form.targetParticipantCount) || null
        : null,
      // 仅 OTHER 分类由用户选择 interactionMode，其余分类后端会按规则推导
      interactionMode: form.category === 'OTHER' ? form.interactionMode : null
    }
    await store.createDemand(submitData)
    clearDemandDraft()
    message.value = '发布成功，等待审核'
    published.value = true
    setTimeout(() => {
      router.push('/')
    }, 1500)
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
      rewardError.value = `报酬不能超过当前可用余额 ${formatMoney(available)}`
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
  interactionMode: '互动模式',
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

function applyAiDraft(draft: import('@/types/campushub').AiDemandDraft): void {
  // AI-managed fields 完全替换：每次 AI 成功生成的 DemandDraft 当作全新草稿，不是旧草稿的 patch。
  // AI null → 清空对应字段；AI [] → 清空数组/字符串字段；AI 有值 → 直接写入。
  form.title = draft.title ?? ''
  form.description = draft.description ?? ''
  form.location = draft.location ?? ''
  form.startDateTime = draft.startTime ? toDateTimeLocal(draft.startTime) : ''
  form.endDateTime = draft.endTime ? toDateTimeLocal(draft.endTime) : ''
  // reward：null → 清空（不保留默认 '10'，避免用户误提交 10 校邻币）；0 → '0'（有效值，不误判为空）
  form.reward = draft.reward == null ? '' : String(draft.reward)
  // tags：数组完全替换，[] → ''，['打印','资料'] → '打印,资料'（不 append 旧值）
  form.tags = Array.isArray(draft.tags) ? draft.tags.join(',') : ''
  // category/campusZone：合法值覆盖，非法/null → 清空（整体替换，避免旧 category 拘留）
  form.category = (isLegalCategory(draft.category) ? draft.category : '') as typeof form.category
  form.campusZone = (isLegalCampusZone(draft.campusZone) ? draft.campusZone : '') as typeof form.campusZone
  // interactionMode/targetParticipantCount：完全由本次 AI 决定，null → 清空（避免旧 category 的依赖字段残留）
  form.interactionMode = draft.interactionMode ?? ''
  form.targetParticipantCount = draft.targetParticipantCount == null ? '' : String(draft.targetParticipantCount)

  // contactInfo：AI 有值 → 覆盖 + 标记为 AI 填写；AI null → 清除上一轮 AI 填写但不覆盖用户手动值
  if (draft.contactInfo != null) {
    form.contactInfo = draft.contactInfo
    aiFilledFields.value.add('contactInfo')
  } else if (aiFilledFields.value.has('contactInfo')) {
    // 上一轮 AI 自动填写的联系方式，新一轮 AI 未识别到，应清空不残留
    form.contactInfo = ''
    aiFilledFields.value.delete('contactInfo')
  }

  // anonymous：AI true → 开启 + 标记为 AI 填写；AI null/false → 清除上一轮 AI 填写但不覆盖用户手动值
  if (draft.anonymous === true) {
    form.anonymous = true
    aiFilledFields.value.add('anonymous')
  } else if (aiFilledFields.value.has('anonymous')) {
    // 上一轮 AI 自动开启的匿名，新一轮 AI 未提及，应重置为 false
    form.anonymous = false
    aiFilledFields.value.delete('anonymous')
  }

  // missingFields 提示：转换为中文标签，引导用户补充
  if (draft.missingFields && draft.missingFields.length > 0) {
    const labels = draft.missingFields
      .map((f) => AI_DIALOG_FIELD_LABELS[f] || f)
      .filter(Boolean)
    aiMissingHint.value = labels.length > 0
      ? `AI 已帮你填写部分内容，请补充：${labels.join('、')}`
      : ''
  } else {
    aiMissingHint.value = ''
  }

  // 回填后触发一次校验，让现有表单校验接管
  runValidations()
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
  aiLoading.value = true
  try {
    const draft = await store.generateDemandDraft(prompt)
    applyAiDraft(draft)
    aiDialogOpen.value = false
  } catch (err) {
    aiError.value = handleError(err, 'AI 服务暂时不可用，请稍后重试')
  } finally {
    aiLoading.value = false
  }
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
          <button type="button" class="button primary" @click="router.push('/demands')">← 返回列表</button>
        </div>
        <div style="padding: 24px;">
          <p class="hero-badge">发布成功，等待审核</p>
        </div>
      </template>
      <template v-else>
        <div class="page-head">
          <div>
            <h2 class="page-title">发布需求</h2>
            <p class="page-summary">填写标题、地点和时间后即可发布，让同学更快看到你的需求。</p>
          </div>
          <button type="button" class="button primary" @click="router.back()">← 返回</button>
        </div>

        <div class="ai-entry">
          <button type="button" class="button ai-button" data-testid="ai-publish-entry" @click="openAiDialog">
            ✨ AI 帮我发布
          </button>
          <p class="ai-entry-hint">用一句话描述需求，AI 帮你生成草稿，再检查后发布。</p>
        </div>

        <div class="form-grid two-column">
          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-title">标题 <span class="required-mark">*</span></label>
            <input id="demand-title" v-model="form.title" maxlength="200" placeholder="例如：帮取快递并送到宿舍" @input="errors.title = ''" />
            <p v-if="errors.title" class="input-help" style="color: var(--danger)">{{ errors.title }}</p>
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-description">描述</label>
            <textarea id="demand-description" v-model="form.description" placeholder="补充时间、地点和需求细节"></textarea>
          </div>

          <div class="field">
            <label for="demand-category">分类 <span class="required-mark">*</span></label>
            <select id="demand-category" v-model="form.category" @change="errors.category = ''">
              <option value="">请选择</option>
              <option v-for="category in DEMAND_CATEGORY_OPTIONS" :key="category" :value="category">{{ formatDemandCategory(category) }}</option>
            </select>
            <p v-if="errors.category" class="input-help" style="color: var(--danger)">{{ errors.category }}</p>
          </div>

          <div class="field">
            <label for="demand-zone">校区 <span class="required-mark">*</span></label>
            <select id="demand-zone" v-model="form.campusZone" @change="errors.campusZone = ''">
              <option value="">请选择</option>
              <option v-for="zone in campusZoneOptions()" :key="zone.value" :value="zone.value">{{ zone.label }}</option>
            </select>
            <p v-if="errors.campusZone" class="input-help" style="color: var(--danger)">{{ errors.campusZone }}</p>
          </div>

          <div class="field">
            <label for="demand-location">地点 <span class="required-mark">*</span></label>
            <input id="demand-location" v-model="form.location" placeholder="北区、南区、图书馆" @input="errors.location = ''" />
            <p v-if="errors.location" class="input-help" style="color: var(--danger)">{{ errors.location }}</p>
            <p class="input-help" style="visibility: hidden;">&nbsp;</p>
          </div>

          <div class="field">
            <label for="demand-reward">报酬 <span class="required-mark">*</span></label>
            <input id="demand-reward" v-model="form.reward" type="number" min="0" step="1" @blur="checkRewardBalance" @input="errors.reward = ''" />
            <p class="input-help" style="margin-top:4px;">可用余额：{{ formatMoney((store.currentUser?.balance ?? 0) - (store.currentUser?.frozenBalance ?? 0)) }}</p>
            <p v-if="rewardError || errors.reward" style="color: var(--danger); margin-top: 6px">{{ rewardError || errors.reward }}</p>
          </div>

          <div v-if="showTargetParticipantCount" class="field" style="grid-column: 1 / -1;">
            <label for="demand-target-count">目标人数 <span class="required-mark">*</span></label>
            <input
              id="demand-target-count"
              v-model="form.targetParticipantCount"
              type="number"
              min="1"
              max="100"
              step="1"
              placeholder="需要几人"
              @input="errors.targetParticipantCount = ''"
            />
            <p v-if="errors.targetParticipantCount" class="input-help" style="color: var(--danger)">{{ errors.targetParticipantCount }}</p>
          </div>

          <div v-if="showInteractionMode" class="field" style="grid-column: 1 / -1;">
            <label for="demand-interaction-mode">互动模式 <span class="required-mark">*</span></label>
            <select id="demand-interaction-mode" v-model="form.interactionMode" @change="errors.interactionMode = ''">
              <option value="">请选择</option>
              <option value="DIRECT_ACCEPT">直接接单</option>
              <option value="SELECT_ONE">选择一人</option>
              <option value="SELECT_MANY">组队选择</option>
            </select>
            <p v-if="errors.interactionMode" class="input-help" style="color: var(--danger)">{{ errors.interactionMode }}</p>
          </div>

          <div class="field">
            <label for="demand-start-datetime">开始时间 <span class="required-mark">*</span></label>
            <input
              id="demand-start-datetime"
              v-model="form.startDateTime"
              type="datetime-local"
              @change="clearStartTimeError"
            />
            <p v-if="errors.startTime" class="input-help" style="color: var(--danger)">{{ errors.startTime }}</p>
          </div>

          <div class="field">
            <label for="demand-end-datetime">结束时间 <span class="required-mark">*</span></label>
            <input
              id="demand-end-datetime"
              v-model="form.endDateTime"
              type="datetime-local"
              :min="form.startDateTime || undefined"
              @change="clearEndTimeError"
            />
            <p v-if="errors.endTime" class="input-help" style="color: var(--danger)">{{ errors.endTime }}</p>
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-tags">标签</label>
            <input id="demand-tags" v-model="form.tags" placeholder="近距离, 宿舍楼下" />
            <p class="input-help">标签用于表示任务特点，例如：加急、近距离。多个标签用逗号分隔。</p>
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label>上传图片</label>
            <ImageUploader v-model="form.images" :max-count="6" :max-size-m-b="10" />
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-contact">联系方式（可选）</label>
            <input id="demand-contact" v-model="form.contactInfo" maxlength="200" placeholder="电话/微信/QQ/邮箱，接单后对方可见" @input="markFieldManual('contactInfo')" />
            <p class="input-help">填写后仅接单人可见，方便线下沟通。</p>
          </div>

          <label class="chip" style="grid-column: 1 / -1; width: fit-content;">
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

        <!-- AI 帮我发布 Dialog -->
        <div v-if="aiDialogOpen" class="ai-modal-mask" data-testid="ai-modal" @click.self="closeAiDialog">
          <div class="ai-modal" role="dialog" aria-modal="true" aria-labelledby="ai-modal-title">
            <div class="ai-modal-head">
              <h3 id="ai-modal-title">✨ AI 帮我发布</h3>
              <button type="button" class="ai-modal-close" :disabled="aiLoading" @click="closeAiDialog">×</button>
            </div>
            <div class="ai-modal-body">
              <label for="ai-prompt-input" class="ai-modal-label">描述一下你想发布的需求</label>
              <textarea
                id="ai-prompt-input"
                v-model="aiPrompt"
                class="ai-prompt-input"
                rows="4"
                maxlength="1000"
                placeholder="例如：明天下午三点帮我从菜鸟驿站取快递送到南区宿舍，给10元"
                :disabled="aiLoading"
                data-testid="ai-prompt-input"
              ></textarea>
              <p v-if="aiError" class="ai-error" data-testid="ai-error">{{ aiError }}</p>
            </div>
            <div class="ai-modal-foot">
              <button type="button" class="button" :disabled="aiLoading" @click="closeAiDialog">取消</button>
              <button
                type="button"
                class="button primary"
                :disabled="aiLoading || !aiPrompt.trim()"
                data-testid="ai-generate-button"
                @click="generateAiDraft"
              >
                {{ aiLoading ? '生成中...' : '生成需求草稿' }}
              </button>
            </div>
          </div>
        </div>
      </template>
    </section>

    <section class="preview-panel">
      <p class="eyebrow">实时预览</p>
      <h2 class="section-title">卡片展示</h2>
      <div class="list-card">
        <div class="status-row">
          <span class="chip is-warning">{{ form.anonymous ? '匿名发布' : '实名发布' }}</span>
          <span class="chip">{{ form.category ? formatDemandCategory(form.category as typeof DEMAND_CATEGORY_OPTIONS[number]) : '未选择分类' }}</span>
        </div>
        <div class="card-head">
          <h3>{{ form.title || '需求标题预览' }}</h3>
          <strong>{{ formatMoney(Number(form.reward || 0)) }}</strong>
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
