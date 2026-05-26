<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { DEMAND_CATEGORY_OPTIONS, type DemandCategory } from '@/types/campushub'
import { formatDemandCategory, formatDemandStatus, formatScore, formatUserRole, formatUserStatus, statusToneClass } from '@/utils/format'

const store = useCampusHubStore()
const message = ref('')
const error = ref('')
const userQuery = ref('')
const demandQuery = ref('')
const demandCategory = ref('')

const isAdmin = computed(() => store.currentUser?.role === 'ADMIN')
const adminDashboard = computed(() => store.adminDashboard)
const pendingDemands = computed(() => store.adminPendingDemands)
const adminCategoryOptions = DEMAND_CATEGORY_OPTIONS.map((category) => ({
  value: category,
  label: formatDemandCategory(category)
}))

const route = useRoute()

const focusedDemandId = ref<string | null>(null)

async function refreshAdminData(): Promise<void> {
  if (!isAdmin.value) {
    return
  }

  message.value = ''
  error.value = ''

  await Promise.all([
    store.fetchAdminDashboard(),
    store.fetchAdminUsers(userQuery.value),
    store.fetchAdminPendingDemands(demandQuery.value, demandCategory.value)
  ])
}

onMounted(() => {
  void refreshAdminData().then(() => {
    // 如果路由指定了管理员 review 跳转，则聚焦对应待审需求
    const tab = String(route.query.tab ?? '')
    const demandId = String(route.query.demandId ?? '')
    if (tab === 'review' && demandId) {
      focusedDemandId.value = demandId
      // 尝试滚动到该元素
      setTimeout(() => {
        const el = document.getElementById(`pending-${demandId}`)
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      }, 200)
    }
  })
})

async function approveDemand(demandId: string): Promise<void> {
  try {
    await store.approveDemand(demandId, true)
    message.value = '需求已通过审核。'
    await refreshAdminData()
  } catch (approveError) {
    error.value = approveError instanceof Error ? approveError.message : '审核失败'
  }
}

async function rejectDemand(demandId: string): Promise<void> {
  try {
    await store.approveDemand(demandId, false)
    message.value = '需求已拒绝。'
    await refreshAdminData()
  } catch (rejectError) {
    error.value = rejectError instanceof Error ? rejectError.message : '审核失败'
  }
}

async function toggleUserStatus(userId: string, banned: boolean): Promise<void> {
  try {
    if (banned) {
      await store.unbanUser(userId)
      message.value = '用户已启用。'
    } else {
      await store.banUser(userId)
      message.value = '用户已禁用。'
    }
    await refreshAdminData()
  } catch (toggleError) {
    error.value = toggleError instanceof Error ? toggleError.message : '用户状态更新失败'
  }
}

async function promoteToAdmin(userId: string): Promise<void> {
  try {
    await store.changeUserRole(userId, 'ADMIN')
    message.value = '用户已提升为管理员'
    await refreshAdminData()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败'
  }
}

async function demoteFromAdmin(userId: string): Promise<void> {
  try {
    await store.changeUserRole(userId, 'USER')
    message.value = '用户已降级为普通用户'
    await refreshAdminData()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败'
  }
}
</script>

<template>
  <div v-if="isAdmin" class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">管理中心</p>
          <h1 class="page-title">管理后台看板</h1>
          <p class="page-summary">在这里可以查看待审核需求、用户情况和平台统计，方便统一处理日常事务。</p>
        </div>
      </div>

      <div class="stats-grid four-column">
        <div class="metric"><span>今日活跃用户</span><strong>{{ adminDashboard?.dailyActiveUsers ?? 0 }}</strong></div>
        <div class="metric"><span>用户总数</span><strong>{{ adminDashboard?.totalUsers ?? 0 }}</strong></div>
        <div class="metric"><span>待审需求</span><strong>{{ adminDashboard?.pendingReviewDemands ?? 0 }}</strong></div>
        <div class="metric"><span>已完成订单</span><strong>{{ adminDashboard?.completedOrders ?? 0 }}</strong></div>
      </div>
    </section>

    <section class="two-column page-grid">
      <article class="panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">审核队列</p>
            <h2 class="section-title">待审核需求</h2>
          </div>
          <div class="inline-actions">
            <input v-model="demandQuery" class="input" type="search" placeholder="搜索标题/地点" @keyup.enter="refreshAdminData" />
            <select v-model="demandCategory" class="input" @change="refreshAdminData">
              <option value="">全部分类</option>
              <option v-for="option in adminCategoryOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
            <button type="button" class="button secondary" @click="refreshAdminData">刷新</button>
          </div>
        </div>

        <div v-if="pendingDemands.length" class="section-grid">
          <div v-for="demand in pendingDemands" :key="demand.id" :id="`pending-${demand.id}`" class="list-card" :style="demand.id === focusedDemandId ? 'box-shadow: 0 0 0 3px rgba(66,133,244,0.12)' : ''">
            <div class="status-row">
              <span class="chip" :class="statusToneClass(demand.status)">{{ formatDemandStatus(demand.status) }}</span>
              <span class="chip">{{ formatDemandCategory(demand.category) }}</span>
            </div>
            <div class="card-head">
              <h3>{{ demand.title }}</h3>
              <strong>{{ demand.reward }} 元</strong>
            </div>
            <p>{{ demand.description }}</p>
              <div class="card-actions">
              <button type="button" class="button primary" @click="approveDemand(demand.id)">通过</button>
              <button type="button" class="button secondary" @click="rejectDemand(demand.id)">拒绝</button>
            </div>
          </div>
        </div>
        <div v-else class="empty-state">当前没有待审核需求。</div>
      </article>

      <article class="panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">用户管理</p>
            <h2 class="section-title">平台用户列表</h2>
          </div>
          <div class="inline-actions">
            <input v-model="userQuery" class="input" type="search" placeholder="搜索学号/邮箱/昵称" @keyup.enter="refreshAdminData" />
            <button type="button" class="button secondary" @click="refreshAdminData">查询</button>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>用户</th>
                <th>学号</th>
                <th>邮箱</th>
                <th>信用分</th>
                <th>角色</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="user in store.accountOptions" :key="user.id">
                <td>
                  <div class="avatar-row">
                    <img :src="user.avatarUrl" :alt="user.nickname" class="avatar" />
                    <strong>{{ user.nickname }}</strong>
                  </div>
                </td>
                <td>{{ user.studentId }}</td>
                <td>{{ user.email }}</td>
                <td>{{ formatScore(user.creditScore) }}</td>
                <td><span class="chip" :class="user.role === 'ADMIN' ? 'is-neutral' : 'is-success'">{{ formatUserRole(user.role) }}</span></td>
                <td><span class="chip" :class="statusToneClass(user.status)">{{ formatUserStatus(user.status) }}</span></td>
                <td>
                  <button
                    v-if="user.role !== 'ADMIN'"
                    type="button"
                    class="button secondary"
                    @click="toggleUserStatus(user.id, user.status === 'BANNED')"
                  >
                    {{ user.status === 'BANNED' ? '启用' : '禁用' }}
                  </button>
                  <button
                    v-if="user.role !== 'ADMIN'"
                    type="button"
                    class="button primary"
                    style="margin-left:8px"
                    @click="promoteToAdmin(user.id)"
                  >
                    设为管理员
                  </button>
                  <button
                    v-else-if="user.role === 'ADMIN' && user.id !== store.currentUser?.id"
                    type="button"
                    class="button secondary"
                    style="margin-left:8px"
                    @click="demoteFromAdmin(user.id)"
                  >
                    降级为普通用户
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <p class="eyebrow">分类统计</p>
          <h2 class="section-title">需求分布图</h2>
        </div>
      </div>

      <div class="chart-list">
        <div v-for="stat in adminDashboard?.categoryDistribution ?? []" :key="stat.category" class="chart-row">
          <strong>{{ formatDemandCategory(stat.category as DemandCategory) }}</strong>
          <div class="progress-bar"><span :style="{ width: `${Math.max(stat.total * 18, 8)}%` }"></span></div>
          <span>{{ stat.total }}</span>
        </div>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>管理后台仅对管理员开放</strong>
    <p>请使用顶部的身份切换按钮切换到管理员账号后再查看。</p>
  </div>
</template>
