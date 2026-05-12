<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { DEMAND_CATEGORY_OPTIONS, type CampusZone } from '@/types/campushub'
import { useCampusHubStore } from '@/stores/campusHub'
import { campusZoneOptions, formatCampusZone, formatDemandCategory, formatMoney } from '@/utils/format'

const router = useRouter()
const store = useCampusHubStore()
const message = ref('')
const error = ref('')

const form = reactive({
  title: '',
  description: '',
  category: '' as '' | (typeof DEMAND_CATEGORY_OPTIONS)[number],
  campusZone: 'XIANLIN' as CampusZone,
  location: '',
  startTime: '',
  endTime: '',
  reward: '10',
  tags: '',
  anonymous: false
})

function submitDemand(): void {
  error.value = ''
  message.value = ''

  try {
    const demand = store.createDemand(form)
    message.value = `已创建需求“${demand.title}”。`
    router.push(`/demands/${demand.id}`)
  } catch (submitError) {
    error.value = submitError instanceof Error ? submitError.message : '发布失败'
  }
}
</script>

<template>
  <div class="page-grid two-column">
    <section class="form-panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">发布需求</p>
          <h1 class="page-title">发布需求表单</h1>
          <p class="page-summary">填写标题、地点和时间后即可发布，让同学更快看到你的需求。</p>
        </div>
      </div>

      <div class="form-grid two-column">
        <div class="field" style="grid-column: 1 / -1;">
          <label for="demand-title">标题</label>
          <input id="demand-title" v-model="form.title" placeholder="例如：帮取快递并送到宿舍" />
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
          <input id="demand-location" v-model="form.location" placeholder="北区、南区、图书馆" />
        </div>
        <div class="field">
          <label for="demand-start">开始时间</label>
          <input id="demand-start" v-model="form.startTime" type="datetime-local" />
        </div>
        <div class="field">
          <label for="demand-end">结束时间</label>
          <input id="demand-end" v-model="form.endTime" type="datetime-local" />
        </div>
        <div class="field">
          <label for="demand-reward">报酬</label>
          <input id="demand-reward" v-model="form.reward" type="number" min="0" step="1" />
        </div>
        <div class="field">
          <label for="demand-tags">标签</label>
          <input id="demand-tags" v-model="form.tags" placeholder="近距离, 宿舍楼下" />
        </div>
        <label class="chip" style="grid-column: 1 / -1; width: fit-content;">
          <input v-model="form.anonymous" type="checkbox" style="margin: 0 8px 0 0;" />
          匿名发布
        </label>
        <button type="button" class="button primary" style="grid-column: 1 / -1;" @click="submitDemand">发布需求</button>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
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
        <div class="meta">{{ formatCampusZone(form.campusZone) }} · {{ form.location || '未填写地点' }}</div>
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