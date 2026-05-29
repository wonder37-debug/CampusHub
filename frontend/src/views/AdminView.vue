<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { handleError } from '@/utils/errorHandler'
import { DEMAND_CATEGORY_OPTIONS, type DemandCategory } from '@/types/campushub'
import { formatDemandCategory, formatDemandStatus, formatScore, formatUserRole, formatUserStatus, statusToneClass } from '@/utils/format'

const store = useCampusHubStore()
const message = ref('')
const error = ref('')
const userQuery = ref('')
const userSearchField = ref('all')
const creditSort = ref<'none' | 'asc' | 'desc'>('none')
const demandQuery = ref('')
const demandCategory = ref('')
const rejectDialogOpen = ref(false)
const rejectingDemandId = ref('')
const rejectingDemandTitle = ref('')
const rejectReason = ref('')

const isAdmin = computed(() => store.currentUser?.role === 'ADMIN')
const adminDashboard = computed(() => store.adminDashboard)
const pendingDemands = computed(() => store.adminPendingDemands)
const adminCategoryOptions = DEMAND_CATEGORY_OPTIONS.map((category) => ({
  value: category,
  label: formatDemandCategory(category)
}))

const filteredAccounts = computed(() => {
  const q = (userQuery.value || '').trim().toLowerCase()
  let list = store.adminUsers.slice()
  if (q) {
    list = list.filter((u) => {
      if (userSearchField.value === 'studentId') return (u.studentId || '').toLowerCase().includes(q)
      if (userSearchField.value === 'email') return (u.email || '').toLowerCase().includes(q)
      if (userSearchField.value === 'nickname') return (u.nickname || '').toLowerCase().includes(q)
      return (u.studentId || '').toLowerCase().includes(q) || (u.email || '').toLowerCase().includes(q) || (u.nickname || '').toLowerCase().includes(q)
    })
  }

  if (creditSort.value === 'desc') {
    list.sort((a, b) => b.creditScore - a.creditScore)
  } else if (creditSort.value === 'asc') {
    list.sort((a, b) => a.creditScore - b.creditScore)
  }

  return list
})

const route = useRoute()

function gotoPendingDemands(): void {
  const el = document.getElementById('pending-section')
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function gotoUsers(): void {
  const el = document.getElementById('users-section')
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

const focusedDemandId = ref<string | null>(null)

async function refreshAdminData(): Promise<void> {
  if (!isAdmin.value) {
    return
  }

  message.value = ''
  error.value = ''

  await Promise.all([
    store.fetchAdminDashboard(),
    store.fetchAdminUsers(
      userQuery.value,
      userSearchField.value === 'all' ? '' : userSearchField.value,
      creditSort.value === 'none' ? '' : 'creditScore',
      creditSort.value === 'none' ? '' : creditSort.value
    ),
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
    error.value = handleError(approveError, '审核失败')
  }
}

function openRejectDialog(demandId: string, demandTitle: string): void {
  rejectingDemandId.value = demandId
  rejectingDemandTitle.value = demandTitle
  rejectReason.value = ''
  rejectDialogOpen.value = true
}

function closeRejectDialog(): void {
  rejectDialogOpen.value = false
  rejectingDemandId.value = ''
  rejectingDemandTitle.value = ''
  rejectReason.value = ''
}

async function submitRejectDemand(): Promise<void> {
  const demandId = rejectingDemandId.value
  const reason = rejectReason.value.trim()

  if (!demandId) {
    closeRejectDialog()
    return
  }

  if (!reason) {
    error.value = '请填写拒绝理由后再提交'
    return
  }

  try {
    await store.approveDemand(demandId, false, reason)
    message.value = '需求已拒绝，已向申请方发送拒绝理由。'
    closeRejectDialog()
    await refreshAdminData()
  } catch (rejectError) {
    error.value = handleError(rejectError, '需求驳回失败')
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
    error.value = handleError(toggleError, '用户状态更新失败')
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
        <div class="metric" role="button" tabindex="0" @click.prevent="gotoUsers"> <span>今日活跃用户</span><strong>{{ adminDashboard?.dailyActiveUsers ?? 0 }}</strong></div>
        <div class="metric" role="button" tabindex="0" @click.prevent="gotoUsers"> <span>用户总数</span><strong>{{ adminDashboard?.totalUsers ?? 0 }}</strong></div>
        <div class="metric" role="button" tabindex="0" @click.prevent="gotoPendingDemands"> <span>待审需求</span><strong>{{ adminDashboard?.pendingReviewDemands ?? 0 }}</strong></div>
        <div class="metric" role="button" tabindex="0" @click.prevent="refreshAdminData"> <span>已完成订单</span><strong>{{ adminDashboard?.completedOrders ?? 0 }}</strong></div>
      </div>
    </section>

    <section class="two-column page-grid">
      <article id="pending-section" class="panel">
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
              <button type="button" class="button secondary" @click="openRejectDialog(demand.id, demand.title)">拒绝</button>
            </div>
          </div>
        </div>
        <div v-else class="empty-state">当前没有待审核需求。</div>
      </article>

      <article id="users-section" class="panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">用户管理</p>
            <h2 class="section-title">平台用户列表</h2>
          </div>
          <div class="inline-actions">
            <select v-model="userSearchField" class="input" style="width:140px;">
              <option value="all">学号/邮箱/昵称</option>
              <option value="studentId">学号</option>
              <option value="email">邮箱</option>
              <option value="nickname">昵称</option>
            </select>
            <input v-model="userQuery" class="input" type="search" :placeholder="userSearchField==='all'? '搜索学号/邮箱/昵称' : (userSearchField==='studentId'? '搜索学号' : userSearchField==='email'? '搜索邮箱' : '搜索昵称')" @keyup.enter="refreshAdminData" />
            <button type="button" class="button secondary" @click="refreshAdminData">查询</button>
            <button type="button" class="button" style="margin-left:8px" @click="creditSort = creditSort === 'desc' ? 'asc' : 'desc'">信用分 {{ creditSort === 'desc' ? '↓' : creditSort === 'asc' ? '↑' : '' }}</button>
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
              <tr v-for="user in filteredAccounts" :key="user.id">
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
                  <span v-else class="muted" style="margin-left:8px;">管理员角色不可调整</span>
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

  <teleport to="body">
    <div v-if="rejectDialogOpen" class="modal-backdrop" @click.self="closeRejectDialog">
      <div class="modal-card panel">
        <div class="modal-head">
          <div>
            <p class="eyebrow">拒绝审核</p>
            <h3 class="section-title">填写拒绝理由</h3>
          </div>
          <button type="button" class="button secondary" @click="closeRejectDialog">关闭</button>
        </div>

        <p class="page-summary" style="margin-top: 0;">拒绝后，这段理由会通过后端自动发送给需求申请方。</p>
        <div class="field">
          <label for="reject-reason">拒绝理由</label>
          <textarea id="reject-reason" v-model="rejectReason" rows="5" :placeholder="`请输入 ${rejectingDemandTitle || '该需求'} 的拒绝理由`"></textarea>
          <p class="input-help">建议填写具体原因，方便申请方修改后重新提交。</p>
        </div>

        <div class="card-actions" style="justify-content: flex-end;">
          <button type="button" class="button secondary" @click="closeRejectDialog">取消</button>
          <button type="button" class="button primary" :disabled="!rejectReason.trim()" @click="submitRejectDemand">确认拒绝</button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(31, 26, 23, 0.46);
  backdrop-filter: blur(6px);
}

.modal-card {
  width: min(640px, 100%);
  padding: 22px;
}

.modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.modal-card textarea {
  min-height: 140px;
  resize: vertical;
}
</style>
