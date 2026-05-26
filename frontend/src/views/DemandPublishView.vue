<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
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
  location: '',
  reward: '',
  time: '',
  campusZone: ''
})

const isFormValid = computed(() => {
  return (
    !errors.title &&
    !errors.location &&
    !errors.reward &&
    !errors.time &&
    (form.campusZone as string) !== '' &&
    Boolean(form.title.trim())
  )
})
const forbiddenForAdmin = computed(() => store.currentUser?.role === 'ADMIN')

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

async function submitDemand(): Promise<void> {
  error.value = ''
  message.value = ''

  // run validations
  runValidations()
  if (!isFormValid.value) {
    error.value = '请修正表单中的错误后再提交。'
    return
  }

  if (submitting.value || published.value) {
    return
  }

  submitting.value = true

  if (!form.campusZone) {
    errors.campusZone = '请选择校区'
    error.value = '请选择校区后再发布需求'
    submitting.value = false
    return
  }

  try {
    await store.createDemand(form)
    // 显示发布成功，并展示单独成功界面后跳转到首页，避免用户重复发布
    message.value = `发布成功，等待审核` 
    published.value = true
    // 1.5s 后返回首页
    setTimeout(() => {
      router.push('/')
    }, 1500)
  } catch (submitError) {
    error.value = handleError(submitError, '发布失败')
  }
}

async function checkRewardBalance(): Promise<void> {
  rewardError.value = ''
  const amount = Number(form.reward || 0)
  // allow 0 reward; only validate when amount > 0
  if (Number.isNaN(amount) || amount <= 0) {
    // clear any existing reward errors when amount is zero or invalid number
    rewardError.value = ''
    errors.reward = ''
    return
  }
  try {
    // fetchBalance uses backend to get the most recent available balance
    let available = Number(await store.fetchBalance())
    // if backend returned 0 but we have a currentProfile that may contain balance, refresh it
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

function runValidations(): void {
  errors.title = ''
  errors.location = ''
  errors.reward = ''
  errors.time = ''
  errors.campusZone = ''

  if (!form.title || form.title.trim().length < 3) {
    errors.title = '标题至少 3 个字符'
  }

  if (!form.location || form.location.trim().length < 2) {
    errors.location = '请填写地点，例如：图书馆/宿舍区'
  }

  const amount = Number(form.reward || 0)
  if (Number.isNaN(amount) || amount < 0) {
    errors.reward = '请输入有效的报酬（可为 0）'
  }

  if (form.startTime && form.endTime) {
    try {
      const s = new Date(form.startTime).getTime()
      const e = new Date(form.endTime).getTime()
      if (Number.isNaN(s) || Number.isNaN(e) || s >= e) {
        errors.time = '请确保开始时间早于结束时间'
      }
    } catch {
      errors.time = '时间格式不正确'
    }
  }

  if (!form.campusZone) {
    errors.campusZone = '请选择校区'
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
          <label for="demand-title">标题</label>
          <input id="demand-title" v-model="form.title" placeholder="例如：帮取快递并送到宿舍" @input="errors.title = ''" />
          <p v-if="errors.title" class="input-help" style="color: var(--danger)">{{ errors.title }}</p>
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="demand-description">描述</label>
          <textarea id="demand-description" v-model="form.description" placeholder="补充时间、地点和需求细节"></textarea>
        </div>
        <div class="field">
          <label for="demand-category">分类</label>
          <select id="demand-category" v-model="form.category">
            <option value="">请选择</option>
            <option v-for="category in DEMAND_CATEGORY_OPTIONS" :key="category" :value="category">{{ formatDemandCategory(category) }}</option>
          </select>
        </div>
        <div class="field">
          <label for="demand-zone">校区</label>
          <select id="demand-zone" v-model="form.campusZone">
            <option v-for="zone in campusZoneOptions()" :key="zone.value" :value="zone.value">{{ zone.label }}</option>
          </select>
        </div>
        <div class="field">
          <label for="demand-location">地点</label>
          <input id="demand-location" v-model="form.location" placeholder="北区、南区、图书馆" @input="errors.location = ''" />
          <p v-if="errors.location" class="input-help" style="color: var(--danger)">{{ errors.location }}</p>
        </div>
        <div class="field">
          <label for="demand-start">开始时间</label>
          <div style="display:flex; gap:8px; align-items:center;">
            <input id="demand-start" v-model="form.startTime" type="datetime-local" />
            <button type="button" class="button small" @click="runValidations">确认时间</button>
          </div>
        </div>
        <div class="field">
          <label for="demand-end">结束时间</label>
          <div style="display:flex; gap:8px; align-items:center;">
            <input id="demand-end" v-model="form.endTime" type="datetime-local" />
            <button type="button" class="button small" @click="runValidations">确认时间</button>
          </div>
        </div>
        <div class="field">
          <label for="demand-reward">报酬</label>
          <input id="demand-reward" v-model="form.reward" type="number" min="0" step="1" @blur="checkRewardBalance" @input="errors.reward = ''" />
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
        <button type="button" class="button primary" style="grid-column: 1 / -1;" @click="submitDemand" :disabled="submitting || !isFormValid">
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

      <div class="section-grid">
        <div class="list-card">
          <strong>发布后效果</strong>
          <p>发布后需求会出现在列表里，方便其他同学浏览和接单。</p>
        </div>
        <div class="list-card">
          <strong>审核说明</strong>
          <p>部分需求会先经过确认，审核通过后就能更快被接单。</p>
        </div>
      </div>
    </section>
  </div>
</template>