<script setup lang="ts">
import { computed, ref } from 'vue'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatDemandCategory, formatDemandStatus, formatScore, formatUserRole, formatUserStatus, statusToneClass } from '@/utils/format'

const store = useCampusHubStore()
const message = ref('')
const error = ref('')

const isAdmin = computed(() => store.currentUser?.role === 'ADMIN')

function approveDemand(demandId: string): void {
  try {
    store.approveDemand(demandId, true)
    message.value = '需求已通过审核。'
  } catch (approveError) {
    error.value = approveError instanceof Error ? approveError.message : '审核失败'
  }
}

function rejectDemand(demandId: string): void {
  try {
    store.approveDemand(demandId, false)
    message.value = '需求已拒绝。'
  } catch (rejectError) {
    error.value = rejectError instanceof Error ? rejectError.message : '审核失败'
  }
}
</script>

<template>
  <div v-if="isAdmin" class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">ADM-05 / ADM-04</p>
          <h1 class="page-title">管理后台看板</h1>
          <p class="page-summary">展示需求审核、用户列表与统计信息，符合 P4 管理后台页面要求。</p>
        </div>
      </div>

      <div class="stats-grid four-column">
        <div class="metric"><span>平均信用分</span><strong>{{ store.dashboardSummary.averageCredit }}</strong></div>
        <div class="metric"><span>待审核需求</span><strong>{{ store.dashboardSummary.pendingApprovals }}</strong></div>
        <div class="metric"><span>开放需求</span><strong>{{ store.dashboardSummary.openDemands }}</strong></div>
        <div class="metric"><span>活跃订单</span><strong>{{ store.dashboardSummary.activeOrders }}</strong></div>
      </div>
    </section>

    <section class="two-column page-grid">
      <article class="panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">审核队列</p>
            <h2 class="section-title">待审核需求</h2>
          </div>
        </div>

        <div v-if="store.pendingApprovals.length" class="section-grid">
          <div v-for="demand in store.pendingApprovals" :key="demand.id" class="list-card">
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
        <div v-for="stat in store.categoryStats" :key="stat.category" class="chart-row">
          <strong>{{ stat.category }}</strong>
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