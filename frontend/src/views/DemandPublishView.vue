<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatMoney, formatDateTime } from '@/utils/format'
import { handleError } from '@/utils/errorHandler'
import { useConfirm } from '@/composables/useDialog'

const router = useRouter()
const store = useCampusHubStore()
const message = ref('')
const error = ref('')
const rewardError = ref('')
const submitting = ref(false)
const published = ref(false)

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
  startDate: '',
  startTimeValue: '',
  endDate: '',
  endTimeValue: '',
  reward: '10',
  tags: '',
  anonymous: false
})

// 计算属性：合并后的完整 datetime 字符串
const startTime = computed(() => {
  if (!form.startDate || !form.startTimeValue) return ''
  return `${form.startDate}T${form.startTimeValue}`
})

const endTime = computed(() => {
  if (!form.endDate || !form.endTimeValue) return ''
  return `${form.endDate}T${form.endTimeValue}`
})

// 获取今天的日期（YYYY-MM-DD格式）
const todayDate = computed(() => {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
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
    Boolean(form.startDate) &&
    Boolean(form.startTimeValue) &&
    Boolean(form.endDate) &&
    Boolean(form.endTimeValue) &&
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

  if (isEmpty(form.startDate)) {
    markRequiredError('startTime', '请选择开始日期')
  }

  if (isEmpty(form.startTimeValue)) {
    markRequiredError('startTime', '请选择开始时间')
  }

  if (isEmpty(form.endDate)) {
    markRequiredError('endTime', '请选择结束日期')
  }

  if (isEmpty(form.endTimeValue)) {
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
      startTime: startTime.value,
      endTime: endTime.value
    }
    await store.createDemand(submitData)
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

// 更新日期或时间时同步到完整的 datetime
function updateStartTime(): void {
  errors.startTime = ''
}

function updateEndTime(): void {
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
            <p class="input-help" style="margin-top:4px;">可用余额：{{ formatMoney(store.currentUser?.balance ?? 0) }}</p>
            <p v-if="rewardError || errors.reward" style="color: var(--danger); margin-top: 6px">{{ rewardError || errors.reward }}</p>
          </div>

          <div class="field">
            <label for="demand-start-date">开始时间 <span class="required-mark">*</span></label>
            <div class="datetime-input-group">
              <input 
                id="demand-start-date" 
                v-model="form.startDate" 
                type="date" 
                :min="todayDate"
                @change="updateStartTime" 
                placeholder="选择日期"
              />
              <input 
                id="demand-start-time" 
                v-model="form.startTimeValue" 
                type="time" 
                @change="updateStartTime" 
                placeholder="选择时间"
              />
            </div>
            <p v-if="errors.startTime" class="input-help" style="color: var(--danger)">{{ errors.startTime }}</p>
          </div>

          <div class="field">
            <label for="demand-end-date">结束时间 <span class="required-mark">*</span></label>
            <div class="datetime-input-group">
              <input 
                id="demand-end-date" 
                v-model="form.endDate" 
                type="date" 
                :min="form.startDate || todayDate"
                @change="updateEndTime" 
                placeholder="选择日期"
              />
              <input 
                id="demand-end-time" 
                v-model="form.endTimeValue" 
                type="time" 
                @change="updateEndTime" 
                placeholder="选择时间"
              />
            </div>
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

/* 日期时间输入组样式 */
.datetime-input-group {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.datetime-input-group input[type="date"],
.datetime-input-group input[type="time"] {
  width: 100%;
  border: 1px solid rgba(0, 0, 0, 0.12);
  background: rgba(255, 255, 255, 0.84);
  color: var(--text-strong);
  border-radius: 16px;
  padding: 12px 14px;
  outline: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
  font-size: 14px;
}

.datetime-input-group input[type="date"]:focus,
.datetime-input-group input[type="time"]:focus {
  border-color: rgba(31, 95, 83, 0.46);
  box-shadow: 0 0 0 4px rgba(31, 95, 83, 0.12);
}

.datetime-input-group input[type="date"]::placeholder,
.datetime-input-group input[type="time"]::placeholder {
  color: var(--muted);
}

/* 移动端适配：堆叠布局 */
@media (max-width: 780px) {
  .datetime-input-group {
    grid-template-columns: 1fr;
  }
}
</style>
