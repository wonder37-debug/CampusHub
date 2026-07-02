<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { handleError } from '@/utils/errorHandler'
import { useRoute, useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import SkeletonCard from '@/components/SkeletonCard.vue'
import ImageViewer from '@/components/ImageViewer.vue'
import { formatOrderStatus, formatRelativeTime, formatScore, formatCampusZone, statusToneClass } from '@/utils/format'
import { useConfirm } from '@/composables/useDialog'

const route = useRoute()
const router = useRouter()
const store = useCampusHubStore()

const order = computed(() => store.getOrderById(String(route.params.id)))
const isRequester = computed(() => order.value && store.currentUser?.id === order.value.requesterId)
const isProvider = computed(() => order.value && store.currentUser?.id === order.value.serviceProviderId)
const message = ref('')
const error = ref('')
const completionSubmitted = ref(false)
const reviewRating = ref('5')
const reviewComment = ref('')
const loadingOrder = ref(false)
const refreshing = ref(false)
const arbitrationDialogOpen = ref(false)
const arbitrationReason = ref('')

// Image viewer
const showImageViewer = ref(false)
const viewerInitialIndex = ref(0)

function openImageViewer(index: number) {
  viewerInitialIndex.value = index
  showImageViewer.value = true
}

async function refreshOrder(): Promise<void> {
  refreshing.value = true
  try {
    await store.fetchOrderDetail(String(route.params.id))
  } finally {
    refreshing.value = false
  }
}

const relatedReviews = computed(() => {
  if (!order.value) return []
  return order.value.reviews ?? store.reviews.filter((r) => r.orderId === order.value!.id)
})

const hasSubmittedReview = computed(() => {
  if (!order.value || !store.currentUser) return false
  if (typeof order.value.currentUserReviewed === 'boolean') {
    return order.value.currentUserReviewed
  }
  return relatedReviews.value.some((r) => r.reviewerId === store.currentUser?.id)
})

const completionHint = computed(() => order.value?.completionHint ?? '')
const pendingReviewTarget = computed(() => order.value?.pendingReviewTarget ?? '')
const pendingReviewTargetLabel = computed(() => {
  const targetId = pendingReviewTarget.value
  if (!targetId || !order.value) return ''
  // Use order's own participant names rather than an unreliable user lookup
  if (String(targetId) === String(order.value.serviceProviderId)) return order.value.serviceProviderName || '接单方'
  if (String(targetId) === String(order.value.requesterId)) return order.value.requesterName || '需求方'
  return ''
})

const formattedCampusZone = computed(() => {
  const zone = order.value?.demandCampusZone
  if (!zone) return ''
  try {
    return formatCampusZone(zone as Parameters<typeof formatCampusZone>[0])
  } catch {
    return zone
  }
})

function formatTimelineLabel(label: string, operatorId?: string): string {
  if (label === '进行中') return '订单进行中'
  // 新格式文本：根据当前用户角色个性化
  if (label === '接单方确认完成，等待需求方确认') {
    return isProvider.value ? '你已确认完成，等待需求方确认' : '接单方已确认完成，等待你的确认'
  }
  if (label === '需求方确认完成，等待接单方确认') {
    return isRequester.value ? '你已确认完成，等待接单方确认' : '需求方已确认完成，等待你的确认'
  }
  // 仲裁发起人角色个性化
  if (label === '发起仲裁' && operatorId && order.value) {
    if (operatorId === order.value.requesterId) {
      return isRequester.value ? '你发起了争议仲裁' : '需求方发起了争议仲裁'
    }
    if (operatorId === order.value.serviceProviderId) {
      return isProvider.value ? '你发起了争议仲裁' : '接单方发起了争议仲裁'
    }
  }
  // 旧格式文本兼容：根据 operatorId 判断角色后个性化
  if (label === '已确认完成，等待对方确认' && operatorId && order.value) {
    const isProviderAction = operatorId === order.value.serviceProviderId
    if (isProviderAction) {
      return isProvider.value ? '你已确认完成，等待需求方确认' : '接单方已确认完成，等待你的确认'
    } else {
      return isRequester.value ? '你已确认完成，等待接单方确认' : '需求方已确认完成，等待你的确认'
    }
  }
  return label
}

const enhancedTimeline = computed(() => {
  if (!order.value) return []
  const items = order.value.timeline.map((entry) => ({
    ...entry,
    displayLabel: formatTimelineLabel(entry.label, entry.operatorId),
  }))
  const reviews = relatedReviews.value
  if (reviews.length > 0) {
    const requesterReviewed = reviews.some((r) => r.reviewerId === order.value!.requesterId)
    const providerReviewed = reviews.some((r) => r.reviewerId === order.value!.serviceProviderId)
    if (requesterReviewed) {
      const review = reviews.find((r) => r.reviewerId === order.value!.requesterId)!
      items.push({ at: review.createdAt, label: 'reviewer', displayLabel: '需求方已评价' })
    }
    if (providerReviewed) {
      const review = reviews.find((r) => r.reviewerId === order.value!.serviceProviderId)!
      items.push({ at: review.createdAt, label: 'provider', displayLabel: '接单方已评价' })
    }
  }
  return items
})

const hasAvailableActions = computed(() => {
  if (!order.value) return false
  if (order.value.status === 'ACCEPTED' && (isProvider.value || isRequester.value)) return true
  if (order.value.status === 'IN_PROGRESS') return true
  return false
})

const canRequestArbitration = computed(() => {
  if (!order.value) return false
  if (!isProvider.value && !isRequester.value) return false
  return order.value.status === 'ACCEPTED' || order.value.status === 'IN_PROGRESS'
})

const currentUserConfirmedCompletion = computed(() => {
  if (!order.value || !store.currentUser) return false
  return order.value.timeline.some(
    (t) => t.operatorId === store.currentUser!.id
      && (
        t.label.includes('接单方确认完成')
        || t.label.includes('需求方确认完成')
        || t.label.includes('已确认完成，等待需求方确认')
        || t.label.includes('已确认完成，等待接单方确认')
      )
  )
})

const providerConfirmed = computed(() => {
  if (!order.value) return false
  return order.value.timeline.some(
    (t) => t.operatorId === order.value!.serviceProviderId
      && (t.label.includes('接单方确认完成') || t.label.includes('已确认完成，等待需求方确认'))
  )
})

const isDemandAnonymous = computed(() => {
  const demand = order.value?.demandDescription != null ? (order.value as any).demand : null
  return demand?.anonymous === true || order.value?.requesterName?.includes('匿名') === true
})

/** 从时间线中查找仲裁发起人 ID */
const arbitrationInitiatorId = computed(() => {
  if (!order.value) return undefined
  const entry = order.value.timeline.find((t) => t.label === '发起仲裁')
  return entry?.operatorId
})

/** 判断仲裁是否由发布方发起 */
const arbitrationByRequester = computed(() => {
  const id = arbitrationInitiatorId.value
  if (!id || !order.value) return false
  return String(id) === String(order.value.requesterId)
})

/** 判断仲裁是否由接单方发起 */
const arbitrationByProvider = computed(() => {
  const id = arbitrationInitiatorId.value
  if (!id || !order.value) return false
  return String(id) === String(order.value.serviceProviderId)
})

const arbitrationTip = computed(() => {
  if (!order.value) return ''
  if (arbitrationByRequester.value) {
    if (isRequester.value) return '你发起了争议仲裁，当前订单暂停执行，请等待管理员处理。'
    if (isProvider.value) return '需求方发起了争议仲裁，当前订单暂停执行，请配合管理员处理。'
    return '需求方发起了争议仲裁，当前订单暂停执行。'
  }
  if (arbitrationByProvider.value) {
    if (isProvider.value) return '你发起了争议仲裁，当前订单暂停执行，请等待管理员处理。'
    if (isRequester.value) return '接单方发起了争议仲裁，当前订单暂停执行，请配合管理员处理。'
    return '接单方发起了争议仲裁，当前订单暂停执行。'
  }
  // 无法确定发起人时的兼容回退
  if (isRequester.value) return '订单处于争议仲裁中，当前暂停执行，请等待管理员处理。'
  if (isProvider.value) return '订单处于争议仲裁中，当前暂停执行，请配合管理员处理。'
  return '此订单处于争议仲裁中，暂时暂停执行。'
})

function reviewDisplayName(name: string, reviewerId: string): string {
  if (store.currentUser && reviewerId === store.currentUser.id) return '我'
  // 匿名保护：如果需求是匿名发布的，且评价中的名字对应发布方，则显示匿名标识
  if (isDemandAnonymous.value) {
    const publisherId = order.value?.requesterId
    if (publisherId && reviewerId === publisherId) {
      return order.value?.requesterName ?? '匿名校友'
    }
  }
  return name
}

function goBack(): void {
  router.back()
}

async function startOrder(): Promise<void> {
  if (order.value) await store.startOrder(order.value.id)
}

async function completeOrder(): Promise<void> {
  if (!order.value) return

  if (!await useConfirm('确认完成', '确认完成此订单？此操作不可撤销。', { danger: true })) return

  message.value = ''
  error.value = ''

  try {
    const updatedOrder = await store.completeOrder(order.value.id)
    completionSubmitted.value = updatedOrder.status !== 'COMPLETED'
    message.value = updatedOrder.status === 'COMPLETED'
      ? '双方都已确认完成，订单已完成。'
      : '已提交完成确认，等待对方确认。'
  } catch (completeError) {
    error.value = handleError(completeError, '操作失败')
  }
}

async function cancelOrder(): Promise<void> {
  if (!order.value) return

  if (!await useConfirm('确认取消', '确认取消此订单？此操作不可撤销。', { danger: true })) return

  message.value = ''
  error.value = ''
  try {
    await store.cancelOrder(order.value.id)
    message.value = '订单已取消。'
  } catch (cancelError) {
    error.value = handleError(cancelError, '取消失败')
  }
}

function openArbitrationDialog(): void {
  arbitrationReason.value = ''
  arbitrationDialogOpen.value = true
}

function closeArbitrationDialog(): void {
  arbitrationDialogOpen.value = false
  arbitrationReason.value = ''
}

async function submitArbitration(): Promise<void> {
  if (!order.value) return
  if (!arbitrationReason.value.trim()) {
    error.value = '请填写仲裁原因'
    return
  }

  message.value = ''
  error.value = ''
  try {
    await store.requestOrderArbitration(order.value.id, arbitrationReason.value)
    message.value = '已提交仲裁申请，等待管理员处理。'
    closeArbitrationDialog()
  } catch (arbitrationError) {
    error.value = handleError(arbitrationError, '发起仲裁失败')
  }
}

async function submitReview(): Promise<void> {
  if (!order.value) return
  message.value = ''
  error.value = ''
  try {
    await store.submitReview(order.value.id, Number(reviewRating.value), reviewComment.value)
    message.value = '评价已提交。'
    reviewComment.value = ''
  } catch (e) {
    error.value = handleError(e, '评价失败')
  }
}

// (no automatic demand enrichment) keep original simple behavior

onMounted(() => {
  loadingOrder.value = true
  void (async () => {
    try {
      await store.fetchOrderDetail(String(route.params.id))
    } catch {
      try {
        await store.fetchOrders()
      } catch {
        // keep current view state; the empty-state branch will explain the failure
      }
    } finally {
      loadingOrder.value = false
    }
  })()
})
</script>

<template>
  <div>
    <div v-if="loadingOrder" class="page-grid">
    <section class="panel">
      <SkeletonCard />
    </section>
    <section class="panel">
      <SkeletonCard />
    </section>
  </div>

  <div v-else-if="order" class="page-grid order-detail-layout">
    <!-- 左栏：订单主体信息 -->
    <section class="panel order-main">
      <div class="page-head">
        <div style="display: flex; align-items: center; gap: 16px;">
          <button type="button" class="button primary" @click="goBack">← 返回</button>
          <div>
            <p class="eyebrow">订单详情</p>
            <h1 class="page-title">{{ order.demandTitle }}</h1>
            <p class="page-summary">{{ formatRelativeTime(order.createdAt) }}</p>
          </div>
        </div>
        <button type="button" class="button primary" :disabled="refreshing" @click="refreshOrder">
          {{ refreshing ? '刷新中...' : '↻ 刷新' }}
        </button>
      </div>

      <div class="status-row">
        <span class="chip" :class="statusToneClass(order.status)">{{ formatOrderStatus(order.status) }}</span>
        <span v-if="completionHint" class="chip is-warning">{{ completionHint }}</span>
      </div>

      <!-- 仲裁状态横幅 -->
      <div v-if="order.status === 'IN_ARBITRATION'" class="arbitration-banner">
        <div class="arbitration-icon">⚖️</div>
        <div class="arbitration-content">
          <strong>订单处于争议仲裁中</strong>
          <p>{{ arbitrationTip }}</p>
        </div>
      </div>

      <!-- 仲裁裁决结果 -->
      <div v-if="order.arbitrationResult" class="list-card" style="margin-top: 12px; border-color: rgba(180, 120, 40, 0.3);">
        <div class="meta"><strong>⚖️ 裁决结果</strong></div>
        <p style="margin-top: 4px; white-space: pre-wrap;">{{ order.arbitrationResult }}</p>
      </div>

      <!-- 需求描述（突出显示，置于时间信息之前） -->
      <div v-if="order.demandDescription" class="demand-description-highlight">
        <p class="eyebrow">需求描述</p>
        <div class="description-content">{{ order.demandDescription }}</div>
      </div>

      <!-- 时间与地点信息 -->
      <div class="mini-grid">
        <div class="mini-stat"><span class="subtle">校区</span><strong>{{ formattedCampusZone || '未填写' }}</strong></div>
        <div class="mini-stat"><span class="subtle">地点</span><strong>{{ order.demandLocation || '未填写' }}</strong></div>
        <div class="mini-stat"><span class="subtle">开始时间</span><strong>{{ order.demandStartTime ? new Date(order.demandStartTime).toLocaleString() : '—' }}</strong></div>
        <div class="mini-stat"><span class="subtle">结束时间</span><strong>{{ order.demandEndTime ? new Date(order.demandEndTime).toLocaleString() : '—' }}</strong></div>
        <div class="mini-stat"><span class="subtle">报酬</span><strong>{{ order.demandReward ? order.demandReward + ' 元' : '无' }}</strong></div>
      </div>

      <!-- 参与方信息 -->
      <div class="mini-grid">
        <div class="mini-stat"><span class="subtle">需求方</span><strong>{{ order.requesterName }}</strong><div class="meta">信用分 {{ formatScore(order.requesterCreditScore) }}</div></div>
        <div class="mini-stat"><span class="subtle">接单方</span><strong>{{ order.serviceProviderName }}</strong><div class="meta">信用分 {{ formatScore(order.serviceProviderCreditScore) }}</div></div>
      </div>

      <!-- 联系方式（仅接单后可查看） -->
      <div v-if="order.demandContactInfo" class="list-card" style="margin-top: 12px; border-color: rgba(31, 95, 83, 0.2);">
        <div class="meta"><strong>📞 联系方式</strong></div>
        <p style="margin-top: 4px; white-space: pre-wrap;">{{ order.demandContactInfo }}</p>
      </div>

      <!-- 需求关联图片 -->
      <div v-if="order.demandImages && order.demandImages.length > 0" class="order-images">
        <p class="eyebrow">需求图片 ({{ order.demandImages.length }})</p>
        <div class="image-grid">
          <div
            v-for="(imgUrl, imgIdx) in order.demandImages"
            :key="imgUrl"
            class="image-item"
            @click="openImageViewer(imgIdx)"
          >
            <img :src="imgUrl" :alt="`需求图片 ${imgIdx + 1}`" loading="lazy" class="demand-img" />
          </div>
        </div>
      </div>

      <!-- 接单人留言 -->
      <div class="list-card" style="margin-top: 12px;">
        <div class="meta"><strong>💬 接单人留言</strong></div>
        <p style="margin-top: 4px; white-space: pre-wrap;">{{ order.note || '暂无留言' }}</p>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

      <!-- 订单操作 -->
      <div v-if="hasAvailableActions || canRequestArbitration" class="order-actions-section">
        <p class="eyebrow">订单操作</p>
        <div class="card-actions">
          <button v-if="order.status === 'ACCEPTED' && isProvider" type="button" class="button primary" @click="startOrder">开始执行</button>
          <button
            v-if="order.status === 'IN_PROGRESS' && isProvider && !currentUserConfirmedCompletion"
            type="button"
            class="button primary"
            @click="completeOrder"
          >
            提交完成确认
          </button>
          <button
            v-if="order.status === 'IN_PROGRESS' && isRequester && providerConfirmed && !currentUserConfirmedCompletion"
            type="button"
            class="button primary"
            @click="completeOrder"
          >
            确认完成
          </button>
          <span
            v-if="order.status === 'IN_PROGRESS' && ((isProvider && currentUserConfirmedCompletion) || (isRequester && !providerConfirmed) || (!isProvider && !isRequester) || (isRequester && currentUserConfirmedCompletion))"
            class="chip is-warning"
          >{{ completionHint || '等待接单方确认完成' }}</span>
          <button v-if="order.status === 'ACCEPTED' && isRequester" type="button" class="button danger" @click="cancelOrder">取消订单</button>
          <button v-if="canRequestArbitration" type="button" class="button secondary" @click="openArbitrationDialog">发起仲裁</button>
        </div>
      </div>
    </section>

    <!-- 右栏：时间线与评价 -->
    <section class="panel order-sidebar">
      <div class="sidebar-head">
        <p class="eyebrow">订单进度</p>
        <h2 class="section-title">时间线与评价</h2>
      </div>

      <div class="timeline-section">
        <div class="timeline">
          <div v-for="(entry, idx) in enhancedTimeline" :key="`${entry.at}-${entry.displayLabel}-${idx}`" class="timeline-item">
            <span>{{ entry.displayLabel }}</span>
            <span class="meta">{{ formatRelativeTime(entry.at) }}</span>
          </div>
        </div>
      </div>

      <div v-if="order.status === 'COMPLETED'" class="sidebar-reviews">
        <div class="list-card">
          <strong>相关评价</strong>
          <div class="order-review-list">
            <div v-if="relatedReviews.length === 0" class="empty-state">暂无评价记录</div>
            <div v-for="review in relatedReviews" :key="review.id" class="order-review-item">
              <div class="order-review-body">
                <strong>{{ reviewDisplayName(review.reviewerName, review.reviewerId) }} → {{ reviewDisplayName(review.targetName, review.targetId) }}</strong>
                <p v-if="review.comment" class="order-review-comment">{{ review.comment }}</p>
                <p v-else class="order-review-comment meta">（无文字评价）</p>
              </div>
              <span class="chip is-success">{{ review.rating }} 星</span>
            </div>
          </div>
        </div>

        <div class="list-card" v-if="!hasSubmittedReview && (isRequester || isProvider)" style="margin-top: 12px;">
          <strong>提交评价</strong>
          <p v-if="pendingReviewTargetLabel" class="meta">当前待评价对象：{{ pendingReviewTargetLabel }}</p>
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
        </div>
        <div class="list-card" v-else-if="hasSubmittedReview" style="margin-top: 12px;">
          <strong>评价</strong>
          <p class="hero-badge" style="margin-top:8px;">您已提交评价，不可重复评价。</p>
        </div>
      </div>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>未找到订单</strong>
  </div>

  <teleport to="body">
    <div v-if="arbitrationDialogOpen" class="modal-backdrop" @click.self="closeArbitrationDialog">
      <div class="modal-card panel">
        <div class="modal-head">
          <div>
            <p class="eyebrow">订单仲裁</p>
            <h3 class="section-title">提交争议说明</h3>
          </div>
          <button type="button" class="button secondary" @click="closeArbitrationDialog">关闭</button>
        </div>

        <p class="page-summary" style="margin-top: 0;">提交后订单会进入仲裁中状态，双方暂时无法继续推进订单，管理员会根据说明做出处理。</p>
        <div class="field">
          <label for="arbitration-reason">仲裁原因</label>
          <textarea id="arbitration-reason" v-model="arbitrationReason" rows="5" placeholder="请简要说明争议点、当前情况和希望管理员关注的信息"></textarea>
        </div>

        <div class="card-actions" style="justify-content: flex-end;">
          <button type="button" class="button secondary" @click="closeArbitrationDialog">取消</button>
          <button type="button" class="button primary" :disabled="!arbitrationReason.trim()" @click="submitArbitration">提交仲裁</button>
        </div>
      </div>
    </div>
  </teleport>

  <!-- 全屏图片预览 -->
  <ImageViewer
    v-if="showImageViewer && order?.demandImages?.length"
    :images="order.demandImages"
    :initial-index="viewerInitialIndex"
    @close="showImageViewer = false"
  />
  </div>
</template>

<style scoped>
/* ========== 双栏布局 ========== */
.order-detail-layout {
  display: grid;
  grid-template-columns: 1fr 380px;
  gap: 20px;
  align-items: start;
}

.order-main {
  min-width: 0;
}

.order-sidebar {
  position: sticky;
  top: 20px;
  max-height: calc(100vh - 40px);
  overflow-y: auto;
}

/* ========== 仲裁横幅 ========== */
.arbitration-banner {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-top: 16px;
  padding: 16px 20px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(179, 106, 31, 0.12) 0%, rgba(179, 106, 31, 0.06) 100%);
  border: 1px solid rgba(179, 106, 31, 0.28);
  border-left: 4px solid var(--warning);
}

.arbitration-icon {
  font-size: 28px;
  line-height: 1;
  flex-shrink: 0;
}

.arbitration-content strong {
  display: block;
  color: var(--warning);
  font-size: 1.05em;
  margin-bottom: 4px;
}

.arbitration-content p {
  margin: 0;
  color: var(--text);
  font-size: 0.9em;
  line-height: 1.5;
}

/* ========== 需求描述突出显示 ========== */
.demand-description-highlight {
  margin-top: 16px;
  padding: 18px 22px;
  border-radius: 14px;
  background: var(--accent-soft);
  border: 1px solid rgba(31, 95, 83, 0.2);
  border-left: 4px solid var(--accent);
}

.demand-description-highlight .eyebrow {
  margin-bottom: 8px;
  color: var(--accent);
  font-weight: 700;
}

.description-content {
  font-size: 1.02em;
  line-height: 1.7;
  color: var(--text-strong);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ========== 订单操作区域 ========== */
.order-actions-section {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--panel-border);
}

.order-actions-section .eyebrow {
  margin-bottom: 10px;
}

/* ========== 侧栏标题与内容 ========== */
.sidebar-head {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--panel-border);
  margin-bottom: 16px;
}

.sidebar-head .eyebrow {
  margin-bottom: 4px;
}

.sidebar-head .section-title {
  margin: 0;
  font-size: 1.15em;
  font-weight: 700;
  color: var(--text-strong);
}

.timeline-section {
  margin-bottom: 16px;
}

.sidebar-reviews {
  margin-top: 4px;
}

/* ========== 需求图片 ========== */
.order-images {
  margin-top: 16px;
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
  margin-top: 8px;
}

.image-item {
  border-radius: 10px;
  overflow: hidden;
  aspect-ratio: 1;
  cursor: pointer;
  border: 1px solid rgba(0, 0, 0, 0.08);
  transition: transform 0.15s, box-shadow 0.15s;
}

.image-item:hover {
  transform: scale(1.03);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
}

.demand-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

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
/* ========== 响应式：移动端切换为单栏 ========== */
@media (max-width: 960px) {
  .order-detail-layout {
    grid-template-columns: 1fr;
  }

  .order-sidebar {
    position: static;
    max-height: none;
    overflow-y: visible;
  }
}
</style>
