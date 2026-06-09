<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatMoney } from '@/utils/format'
import { handleError } from '@/utils/errorHandler'

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
  startTime: '',
  endTime: '',
  reward: '10',
  tags: '',
  anonymous: false
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
    Boolean(form.startTime) &&
    Boolean(form.endTime) &&
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

  if (isEmpty(form.startTime)) {
    markRequiredError('startTime', '请填写开始时间')
  }

  if (isEmpty(form.endTime)) {
    markRequiredError('endTime', '请填写结束时间')
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

  if (form.startTime && form.endTime) {
    try {
      const start = new Date(form.startTime).getTime()
      const end = new Date(form.endTime).getTime()
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

  if (!window.confirm('确认发布此需求？发布后将进入审核流程。')) return

  submitting.value = true

  try {
    await store.createDemand(form)
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
          </div>

          <div class="field">
            <label for="demand-start">开始时间 <span class="required-mark">*</span></label>
            <div style="display:flex; gap:8px; align-items:center;">
              <input id="demand-start" v-model="form.startTime" type="datetime-local" @input="errors.startTime = ''" />
              <button type="button" class="button small" @click="runValidations">确认时间</button>
            </div>
            <p v-if="errors.startTime" class="input-help" style="color: var(--danger)">{{ errors.startTime }}</p>
          </div>

          <div class="field">
            <label for="demand-end">结束时间 <span class="required-mark">*</span></label>
            <div style="display:flex; gap:8px; align-items:center;">
              <input id="demand-end" v-model="form.endTime" type="datetime-local" @input="errors.endTime = ''" />
              <button type="button" class="button small" @click="runValidations">确认时间</button>
            </div>
            <p v-if="errors.endTime" class="input-help" style="color: var(--danger)">{{ errors.endTime }}</p>
          </div>

          <div class="field">
            <label for="demand-reward">报酬 <span class="required-mark">*</span></label>
            <input id="demand-reward" v-model="form.reward" type="number" min="0" step="1" @blur="checkRewardBalance" @input="errors.reward = ''" />
            <p class="input-help" style="margin-top:4px;">可用余额：{{ formatMoney(store.currentUser?.balance ?? 0) }}</p>
            <p v-if="rewardError || errors.reward" style="color: var(--danger); margin-top: 6px">{{ rewardError || errors.reward }}</p>
          </div>

          <div class="field">
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

    <section class="panel">
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
        <div class="meta">{{ form.campusZone ? formatCampusZone(form.campusZone) : '请选择校区' }} · {{ form.location || '未填写地点' }}</div>
        <div class="tag-row">
          <span v-for="tag in form.tags.split(/[，,]/).filter(Boolean)" :key="tag" class="badge is-neutral">{{ tag.trim() }}</span>
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
</style>
