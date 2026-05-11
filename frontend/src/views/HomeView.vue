<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatDateTime, formatMoney, formatScore, statusToneClass, truncateText } from '@/utils/format'

const store = useCampusHubStore()

const featuredDemands = computed(() => store.demands.slice(0, 4))
const recentOrders = computed(() => store.currentUserOrders.slice(0, 3))
const recentNotifications = computed(() => store.currentUserNotifications.slice(0, 3))
</script>

<template>
  <div class="page-grid">
    <section class="hero">
      <article class="hero-copy">
        <p class="eyebrow">展示层主页</p>
        <h1>让校园互助的注册、需求、订单、通知和后台审核变成一条清晰路径。</h1>
        <p class="lead">
          当前前端已按 P3 API 结构与 P4 Sprint 看板完成核心模块布局，并接入演示态数据仓库，方便你直接演示业务流程。
        </p>

        <div class="hero-actions">
          <RouterLink class="button primary" to="/demands">浏览需求</RouterLink>
          <RouterLink class="button secondary" to="/demands/new">发布需求</RouterLink>
          <RouterLink class="button secondary" to="/orders">查看订单</RouterLink>
        </div>

        <div class="mini-grid">
          <div class="mini-stat">
            <span class="subtle">当前账号</span>
            <strong>{{ store.currentUser?.nickname ?? '演示访客' }}</strong>
          </div>
          <div class="mini-stat">
            <span class="subtle">信用分</span>
            <strong>{{ formatScore(store.currentUser?.creditScore ?? store.dashboardSummary.averageCredit) }}</strong>
          </div>
          <div class="mini-stat">
            <span class="subtle">未读通知</span>
            <strong>{{ store.unreadNotificationCount }}</strong>
          </div>
          <div class="mini-stat">
            <span class="subtle">待审核需求</span>
            <strong>{{ store.pendingApprovals.length }}</strong>
          </div>
        </div>
      </article>

      <aside class="hero-panel hero-side">
        <div class="metric">
          <span>开放中的需求</span>
          <strong>{{ store.dashboardSummary.openDemands }}</strong>
          <span class="muted">用户可以直接浏览并接单的需求数</span>
        </div>
        <div class="metric">
          <span>进行中的订单</span>
          <strong>{{ store.dashboardSummary.activeOrders }}</strong>
          <span class="muted">接单后进入执行流程的任务</span>
        </div>
        <div class="metric">
          <span>管理员待审核</span>
          <strong>{{ store.dashboardSummary.pendingApprovals }}</strong>
          <span class="muted">对应 P4 管理后台需求审核</span>
        </div>
        <div class="metric">
          <span>平均信用分</span>
          <strong>{{ store.dashboardSummary.averageCredit }}</strong>
          <span class="muted">展示当前演示账号整体信用状态</span>
        </div>
      </aside>
    </section>

    <section class="section">
      <div class="section-header">
        <div>
          <p class="eyebrow">关键业务入口</p>
          <h2 class="section-title">从前端展示层直接进入核心流程</h2>
        </div>
        <RouterLink class="button secondary" to="/auth">进入账号中心</RouterLink>
      </div>

      <div class="card-grid three-column">
        <article class="card helper-card">
          <span class="hero-badge">USR / 认证</span>
          <h3>注册、登录、个人资料</h3>
          <p class="section-lead">表单入口已经做好，可以切换身份、修改资料并观察信用分变化。</p>
          <RouterLink class="button secondary" to="/auth">查看认证页</RouterLink>
        </article>

        <article class="card helper-card">
          <span class="hero-badge">DEM / 需求</span>
          <h3>需求发布、浏览与详情</h3>
          <p class="section-lead">支持筛选、推荐排序、需求详情和接单按钮的演示流程。</p>
          <RouterLink class="button secondary" to="/demands">查看需求列表</RouterLink>
        </article>

        <article class="card helper-card">
          <span class="hero-badge">ORD / 通知</span>
          <h3>订单流转与站内消息</h3>
          <p class="section-lead">订单状态变化会同步到通知中心，方便演示完整闭环。</p>
          <RouterLink class="button secondary" to="/notifications">打开通知中心</RouterLink>
        </article>
      </div>
    </section>

    <section class="section two-column page-grid">
      <article class="panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">最近需求</p>
            <h2 class="section-title">平台动态</h2>
          </div>
          <RouterLink class="button secondary" to="/demands">全部需求</RouterLink>
        </div>

        <div class="section-grid">
          <div v-for="demand in featuredDemands" :key="demand.id" class="list-card">
            <div class="status-row">
              <span class="chip" :class="statusToneClass(demand.approvalStatus)">{{ demand.approvalStatus }}</span>
              <span class="chip">{{ demand.category }}</span>
            </div>
            <div class="card-head">
              <h3>{{ demand.title }}</h3>
              <strong>{{ formatMoney(demand.reward) }}</strong>
            </div>
            <p>{{ truncateText(demand.description, 72) }}</p>
            <div class="meta">
              {{ demand.location }} · {{ formatDateTime(demand.startTime) }}
            </div>
          </div>
        </div>
      </article>

      <article class="panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">个人视角</p>
            <h2 class="section-title">当前账号的待办</h2>
          </div>
          <RouterLink class="button secondary" to="/profile">资料页</RouterLink>
        </div>

        <div class="section-grid">
          <div class="list-card">
            <div class="card-head">
              <h3>最近订单</h3>
              <span class="chip is-neutral">{{ recentOrders.length }}</span>
            </div>
            <div v-if="recentOrders.length" class="stack">
              <div v-for="order in recentOrders" :key="order.id" class="timeline-item">
                <div>
                  <strong>{{ order.demandTitle }}</strong>
                  <div class="meta">{{ order.requesterName }} ↔ {{ order.serviceProviderName }}</div>
                </div>
                <span class="chip" :class="statusToneClass(order.status)">{{ order.status }}</span>
              </div>
            </div>
            <div v-else class="empty-state">当前没有订单，去需求页接一单试试。</div>
          </div>

          <div class="list-card">
            <div class="card-head">
              <h3>最近通知</h3>
              <span class="chip is-neutral">{{ recentNotifications.length }}</span>
            </div>
            <div v-if="recentNotifications.length" class="stack">
              <div v-for="notification in recentNotifications" :key="notification.id" class="timeline-item">
                <div>
                  <strong>{{ notification.type }}</strong>
                  <div class="meta">{{ notification.content }}</div>
                </div>
                <span class="chip" :class="notification.isRead ? 'is-success' : 'is-warning'">
                  {{ notification.isRead ? '已读' : '未读' }}
                </span>
              </div>
            </div>
            <div v-else class="empty-state">通知中心尚未产生消息。</div>
          </div>
        </div>
      </article>
    </section>
  </div>
</template>

