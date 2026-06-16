<script setup lang="ts">
import { computed, reactive, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, onBeforeRouteLeave } from 'vue-router'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatMoney, formatDateTime } from '@/utils/format'
import { handleError } from '@/utils/errorHandler'
import { useConfirm } from '@/composables/useDialog'
import { loadDemandDraft, saveDemandDraft, clearDemandDraft } from '@/utils/demandDraft'

const router = useRouter()
const store = useCampusHubStore()
const message = ref('')
const error = ref('')
const rewardError = ref('')
const submitting = ref(false)
const published = ref(false)

const draftDiscarded = ref(false)

const errors = reactive({
  title: '',
  category: '',
  location: '',
  reward: '',
  startTime: '',
  endTime: '',
  campusZone: ''
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
  tags: '',
  anonymous: false
})

// datetime-local 输入格式为 YYYY-MM-DDTHH:MM，与 startTime/endTime 兼容
const startTime = computed(() => form.startDateTime)
const endTime = computed(() => form.endDateTime)

// 获取当前时间（YYYY-MM-DDTHH:MM 格式，用于 min 约束）
const minDateTime = computed(() => {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  const hours = String(now.getHours()).padStart(2, '0')
  const minutes = String(now.getMinutes()).padStart(2, '0')
  return `${year}-${month}-${day}T${hours}:${minutes}`
})

const isFormValid = computed(() => {
  return (
    !errors.title &&
    !errors.category &&
    !errors.location &&
    !errors.reward &&
    !errors.startTime &&
    !errors.endTime &&
    !errors.campusZone &&
    Boolean(form.title.trim()) &&
    Boolean(form.category) &&
    Boolean(form.campusZone) &&
    Boolean(form.location.trim()) &&
    Boolean(form.startDateTime) &&
    Boolean(form.endDateTime) &&
    Boolean(String(form.reward ?? '').trim())
  )
})

const forbiddenForAdmin = computed(() => store.currentUser?.role === 'ADMIN')
const canSubmit = computed(() => !submitting.value && !published.value && !forbiddenForAdmin.value)

function getRewardText(): string {
  return String(form.reward ?? '').trim()
}

function isEmpty(value: string): boolean {
  return !value || !value.trim()
}

function markRequiredError(field: keyof typeof errors, messageText: string): void {
  errors[field] = messageText
}

function runValidations(): void {
  errors.title = ''
  errors.category = ''
  errors.location = ''
  errors.reward = ''
  errors.startTime = ''
  errors.endTime = ''
  errors.campusZone = ''

  if (isEmpty(form.title)) {
    markRequiredError('title', '请填写标题')
  } else if (form.title.trim().length < 3) {
    markRequiredError('title', '标题至少 3 个字符')
  } else if (form.title.trim().length > 200) {
    markRequiredError('title', '标题不能超过 200 个字符')
  }

  if (isEmpty(form.category)) {
    markRequiredError('category', '请选择分类')
  }

  if (isEmpty(form.campusZone)) {
    markRequiredError('campusZone', '请选择校区')
  }

  if (isEmpty(form.location)) {
    markRequiredError('location', '请填写地点，例如：图书馆/宿舍区')
  }

  if (!form.startDateTime) {
    markRequiredError('startTime', '请选择开始时间')
  }

  if (!form.endDateTime) {
    markRequiredError('endTime', '请选择结束时间')
  }

  const rewardText = getRewardText()
  if (!rewardText) {
    markRequiredError('reward', '请填写报酬')
  } else {
    const amount = Number(rewardText)
    if (Number.isNaN(amount) || amount < 0) {
      markRequiredError('reward', '请输入有效的报酬（可为 0）')
    }
  }

  // 验证开始和结束时间
  if (startTime.value && endTime.value) {
    try {
      const start = new Date(startTime.value).getTime()
      const end = new Date(endTime.value).getTime()
      if (Number.isNaN(start) || Number.isNaN(end) || start >= end) {
        errors.startTime = '请确保开始时间早于结束时间'
        errors.endTime = '请确保开始时间早于结束时间'
      }
    } catch {
      errors.startTime = '时间格式不正确'
      errors.endTime = '时间格式不正确'
    }
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

// 恢复草稿
onMounted(() => {
  const draft = loadDemandDraft()
  if (draft) {
    form.title = draft.title ?? ''
    form.description = draft.description ?? ''
    form.category = (draft.category || '') as typeof form.category
    form.campusZone = (draft.campusZone || '') as typeof form.campusZone
    form.location = draft.location ?? ''
    form.startDateTime = draft.startDateTime ?? ''
    form.endDateTime = draft.endDateTime ?? ''
    form.reward = draft.reward ?? '10'
    form.tags = draft.tags ?? ''
    form.anonymous = draft.anonymous ?? false
  }
})

async function submitDemand(): Promise<void> {
  error.value = ''
  message.value = ''

  runValidations()
  if (!isFormValid.value) {
    error.value = '请先填写所有必填项后再提交。'
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
      endTime: form.endDateTime
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
    let available = Number(await store.fetchBalance())
    if (available === 0 && store.currentUser) {
      try {
        await store.fetchProfile()
        available = Number(store.currentUser?.balance ?? available)
      } catch {
        // ignore
      }
    }

    if (amount > available) {
      rewardError.value = `报酬不能超过当前可用余额 ${formatMoney(available)}`
      errors.reward = rewardError.value
    }
  } catch {
    // ignore balance check errors
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

          <div class="field">
            <label for="demand-start-datetime">开始时间 <span class="required-mark">*</span></label>
            <input
              id="demand-start-datetime"
              v-model="form.startDateTime"
              type="datetime-local"
              :min="minDateTime"
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
              :min="form.startDateTime || minDateTime"
              @change="clearEndTimeError"
            />
            <p v-if="errors.endTime" class="input-help" style="color: var(--danger)">{{ errors.endTime }}</p>
          </div>

          <div class="field" style="grid-column: 1 / -1;">
            <label for="demand-tags">标签</label>
            <input id="demand-tags" v-model="form.tags" placeholder="近距离, 宿舍楼下" />
            <p class="input-help">标签用于表示任务特点，例如：加急、近距离。多个标签用逗号分隔。</p>
          </div>

          <label class="chip" style="grid-column: 1 / -1; width: fit-content;">
            <input v-model="form.anonymous" type="checkbox" style="margin: 0 8px 0 0;" />
            匿名发布
          </label>

          <button type="button" class="button primary" style="grid-column: 1 / -1;" @click="submitDemand" :disabled="!canSubmit">
            {{ submitting ? '发布中...' : '发布需求' }}
          </button>
        </div>

        <p v-if="message" class="hero-badge">{{ message }}</p>
        <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
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
</style>
