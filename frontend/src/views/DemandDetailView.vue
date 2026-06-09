<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { handleError } from '@/utils/errorHandler'
import { useRoute } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import SkeletonCard from '@/components/SkeletonCard.vue'
import { formatAcceptDisabledReason, formatCampusZone, formatDateTime, formatDemandCategory, formatDemandStatus, formatMoney, formatOrderStatus, formatScore, statusToneClass } from '@/utils/format'

const route = useRoute()
const store = useCampusHubStore()
const note = ref('')
const reviewRating = ref('5')
const reviewComment = ref('')
const message = ref('')
const error = ref('')
const completionSubmitted = ref(false)
const loadingDemand = ref(false)

const demand = computed(() => store.getDemandById(String(route.params.id)))
const relatedOrder = computed(() => store.orders.find((order) => order.demandId === demand.value?.id))
const canAccept = computed(() => {
  if (!demand.value) return false
  if (typeof demand.value.canAccept === 'boolean') return demand.value.canAccept
  if (relatedOrder.value) return false
  if (demand.value.status !== 'PENDING') return false
  if (!store.currentUser) return false
  if (store.currentUser.role === 'ADMIN') return false
  if (store.currentUser.id === demand.value.publisherId) return false
  return true
})

const acceptDisabledReason = computed(() => {
  if (!demand.value) return '未找到需求'
  if (demand.value.acceptDisabledReason) return formatAcceptDisabledReason(demand.value.acceptDisabledReason)
  if (relatedOrder.value) {
    const s = relatedOrder.value.status
    if (s === 'ACCEPTED') return '该需求已被接单，来晚了一步。'
    if (s === 'COMPLETED' || s === 'CANCELLED') return '该需求已结束。'
  }
  if (demand.value.status === 'REVIEWING') return '该需求仍在审核中，暂时无法接单。'
  if (demand.value.status === 'EXPIRED') return '该需求已过期，无法接单。'
  if (!store.currentUser) return '请先登录后再接单。'
  if (store.currentUser.id === demand.value.publisherId) return '这是您自己发布的需求，不能自行接单'
  if (store.currentUser.role === 'ADMIN') return '管理员账号无法接单'
  return null
})
const canStartExecution = computed(() => {
  if (!demand.value) return false
  if (typeof demand.value.canStartExecution === 'boolean') return demand.value.canStartExecution
  return relatedOrder.value?.status === 'ACCEPTED' && store.currentUser?.id === relatedOrder.value.serviceProviderId
})
const canViewAcceptNote = computed(() => demand.value?.canViewAcceptNote !== false)
const canSubmitAcceptNote = computed(() => demand.value?.canSubmitAcceptNote !== false)
const relatedReviews = computed(() => store.reviews.filter((review) => review.orderId === relatedOrder.value?.id))
const hasSubmittedReview = computed(() => {
  if (!relatedOrder.value || !store.currentUser) return false
  if (typeof relatedOrder.value.currentUserReviewed === 'boolean') {
    return relatedOrder.value.currentUserReviewed
  }
  return relatedReviews.value.some((r) => r.reviewerId === store.currentUser?.id)
})

async function acceptCurrentDemand(): Promise<void> {
  if (!demand.value) {
    return
  }

  error.value = ''
  message.value = ''

  if (store.currentUser?.role === 'ADMIN') {
    error.value = '管理员账号无法接单'
    return
  }

  if (!window.confirm('确认接单？接单后将生成订单。')) return

  try {
    const order = await store.acceptDemand(demand.value.id, note.value)
    message.value = `已接单，生成订单 ${order.id}`
  } catch (acceptError) {
    error.value = handleError(acceptError, '接单失败')
  }
}

async function startOrder(): Promise<void> {
  if (!relatedOrder.value) {
    return
  }

  try {
    await store.startOrder(relatedOrder.value.id)
    message.value = '订单已进入进行中状态。'
  } catch (startError) {
    error.value = handleError(startError, '操作失败')
  }
}

async function completeOrder(): Promise<void> {
  if (!relatedOrder.value) {
    return
  }

  if (!window.confirm('确认完成此订单？此操作不可撤销。')) return

  try {
    const updatedOrder = await store.completeOrder(relatedOrder.value.id)
    completionSubmitted.value = updatedOrder.status !== 'COMPLETED'
    message.value = updatedOrder.status === 'COMPLETED'
      ? '双方都已确认完成，订单已完成。'
      : '已提交完成确认，等待对方确认。'
  } catch (completeError) {
    error.value = handleError(completeError, '操作失败')
  }
}

async function submitReview(): Promise<void> {
  if (!relatedOrder.value) {
    return
  }

  error.value = ''
  message.value = ''

  try {
    await store.submitReview(relatedOrder.value.id, Number(reviewRating.value), reviewComment.value)
    message.value = '评价已提交。'
    reviewComment.value = ''
  } catch (reviewError) {
    error.value = handleError(reviewError, '评价失败')
  }
}

onMounted(() => {
  loadingDemand.value = true
  void (async () => {
    try {
      await store.fetchDemandDetail(String(route.params.id))
      if (store.currentUser) {
        await store.fetchOrders()
      }
    } catch {
      try {
        await store.fetchDemands()
      } catch {
        // keep current view state; the empty-state branch will explain the failure
      }
    } finally {
      loadingDemand.value = false
    }
  })()
})
</script>

<template>
  <div>
    <div v-if="loadingDemand" class="page-grid two-column">
    <section class="panel">
      <SkeletonCard />
    </section>
    <section class="panel">
      <SkeletonCard />
    </section>
  </div>

  <div v-else-if="demand" class="page-grid two-column">
    <section class="panel">
      <div class="status-row">
        <span class="chip" :class="relatedOrder ? statusToneClass(relatedOrder.status) : statusToneClass(demand.status)">
          {{ relatedOrder ? formatOrderStatus(relatedOrder.status) : formatDemandStatus(demand.status) }}
        </span>
        <span class="chip">{{ formatDemandCategory(demand.category) }}</span>
        <span class="chip">{{ formatCampusZone(demand.campusZone) }}</span>
        <span v-if="demand.anonymous" class="chip is-warning">匿名</span>
      </div>

      <div class="page-head">
        <div>
          <p class="eyebrow">需求详情</p>
          <h1 class="page-title">{{ demand.title }}</h1>
          <p class="page-summary">{{ demand.description }}</p>
          <p class="page-meta">时间：{{ formatDateTime(demand.startTime || '') }} - {{ formatDateTime(demand.endTime || '') }}</p>
          <p class="page-meta">地点：{{ demand.location || '未填写' }}</p>
        </div>
        <strong class="page-title">{{ formatMoney(demand.reward) }}</strong>
      </div>

      <div class="avatar-row">
        <img :src="demand.publisher?.avatarUrl ?? demand.publisherAvatar" :alt="demand.publisher?.nickname ?? demand.publisherName" class="avatar large" />
        <div>
          <strong>{{ demand.anonymous ? demand.anonymousCode ?? '匿名发布' : (demand.publisher?.nickname ?? demand.publisherName) }}</strong>
          <p class="subtle">信用分：{{ demand.publisher ? formatScore(demand.publisher.creditScore) : '未知' }}</p>
          <p class="meta">发布者学号：{{ demand.publisherIdentityVisible === false ? (demand.publisherStudentIdMasked || '已隐藏') : (demand.anonymous ? (demand.publisher?.studentId ? String(demand.publisher.studentId).slice(0,3) + '***' + String(demand.publisher.studentId).slice(-2) : '匿名') : (demand.publisher?.studentId ?? '未知')) }}</p>
          <p class="meta">发布于 {{ formatDateTime(demand.createdAt) }}</p>
        </div>
      </div>

      <div class="tag-row">
        <span class="badge is-neutral">{{ demand.anonymous ? '匿名发布' : '实名发布' }}</span>
      </div>

      <div v-if="demand.status === 'CANCELLED' && demand.reviewReason" class="list-card" style="margin-top: 16px;">
        <strong>审核原因</strong>
        <p style="margin-top: 8px; color: var(--danger);">{{ demand.reviewReason }}</p>
      </div>

      <div class="tag-row">
        <span v-for="tag in demand.tags" :key="tag" class="badge is-neutral">{{ tag }}</span>
      </div>

      <div class="list-card">
        <strong>接单留言</strong>
        <div v-if="canAccept || (canSubmitAcceptNote && canViewAcceptNote)" class="field">
          <label for="accept-note">留言内容</label>
          <textarea id="accept-note" v-model="note" placeholder="给发布者留一句话"></textarea>
        </div>
        <p v-else-if="!canAccept && relatedOrder" class="meta">接单留言仅对参与者可见。</p>
        <div class="card-actions">
          <button
            v-if="canAccept"
            type="button"
            class="button primary"
            @click="acceptCurrentDemand"
          >
            立即接单
          </button>
          <span v-else class="chip is-warning">{{ acceptDisabledReason || '当前需求暂时不可接单' }}</span>

          <button v-if="canStartExecution" type="button" class="button secondary" @click="startOrder">开始执行</button>
          <button
            v-if="relatedOrder?.status === 'IN_PROGRESS' && store.currentUser?.id === relatedOrder.serviceProviderId && !completionSubmitted"
            type="button"
            class="button secondary"
            @click="completeOrder"
          >
            提交完成确认
          </button>
          <button
            v-else-if="relatedOrder?.status === 'IN_PROGRESS' && store.currentUser?.id === relatedOrder.requesterId && !completionSubmitted"
            type="button"
            class="button secondary"
            @click="completeOrder"
          >
            确认完成
          </button>
          <span v-else-if="relatedOrder?.status === 'IN_PROGRESS'" class="chip is-warning">等待对方确认完成</span>
        </div>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
    </section>

    <section class="panel">
      <p class="eyebrow">关联信息</p>
      <h2 class="section-title">订单、时间线与评价</h2>

      <div v-if="relatedOrder" class="section-grid">
        <div class="list-card">
          <div class="status-row">
            <span class="chip" :class="statusToneClass(relatedOrder.status)">{{ formatOrderStatus(relatedOrder.status) }}</span>
            <span class="chip">订单 {{ relatedOrder.id }}</span>
          </div>
          <div class="avatar-row">
            <img :src="relatedOrder.serviceProviderAvatar" :alt="relatedOrder.serviceProviderName" class="avatar" />
            <div>
              <strong>{{ relatedOrder.serviceProviderName }}</strong>
              <div class="meta">接单方 · 信用分 {{ formatScore(relatedOrder.serviceProviderCreditScore) }}</div>
            </div>
          </div>
          <div class="meta" style="margin-top: 8px;">需求方 · 信用分 {{ formatScore(relatedOrder.requesterCreditScore) }}</div>
          <p>{{ relatedOrder.note || '暂无留言' }}</p>
        </div>

        <div class="list-card">
          <strong>流程时间线</strong>
          <div class="timeline">
            <div v-for="entry in relatedOrder.timeline" :key="`${entry.at}-${entry.label}`" class="timeline-item">
              <span>{{ entry.label }}</span>
              <span class="meta">{{ formatDateTime(entry.at) }}</span>
            </div>
          </div>
        </div>

        <div v-if="relatedOrder.status === 'COMPLETED'" class="list-card">
          <template v-if="hasSubmittedReview">
            <strong>评价</strong>
            <p class="hero-badge" style="margin-top:8px;">您已提交评价，不可重复评价。</p>
          </template>
          <template v-else-if="store.currentUser && (store.currentUser.id === relatedOrder.requesterId || store.currentUser.id === relatedOrder.serviceProviderId)">
            <strong>提交评价</strong>
            <div class="field">
              <label for="review-rating">评分</label>
              <select id="review-rating" v-model="reviewRating">
                <option value="5">5 星</option>
                <option value="4">4 星</option>
                <option value="3">3 星</option>
                <option value="2">2 星</option>
                <option value="1">1 星</option>
              </select>
            </div>
            <div class="field">
              <label for="review-comment">评价</label>
              <textarea id="review-comment" v-model="reviewComment" placeholder="分享你的体验"></textarea>
            </div>
            <button type="button" class="button primary" @click="submitReview">提交评价</button>
          </template>
        </div>

        <div v-if="relatedReviews.length" class="list-card">
          <strong>相关评价</strong>
          <div class="review-grid">
            <div v-for="review in relatedReviews" :key="review.id" class="timeline-item">
              <div>
                <strong>{{ review.reviewerName }} → {{ review.targetName }}</strong>
                <div class="meta">{{ review.comment }}</div>
              </div>
              <span class="chip is-success">{{ review.rating }} 星</span>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="empty-state" style="--empty-icon:'📦'">
        <strong>当前还没有订单记录</strong>
        <p>你可以先在该需求上完成接单操作，再回到这里查看订单与评价流程。</p>
      </div>
    </section>
  </div>

  <div v-else class="empty-state" style="--empty-icon:'🔍'">
    <strong>未找到需求</strong>
    <p>请返回需求列表重新选择一个条目。</p>
  </div>
  </div>
</template>