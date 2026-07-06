<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { handleError } from '@/utils/errorHandler'
import { useRoute, useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import SkeletonCard from '@/components/SkeletonCard.vue'
import ImageViewer from '@/components/ImageViewer.vue'
import { formatAcceptDisabledReason, formatCampusZone, formatDateTime, formatDemandCategory, formatDemandStatus, formatMoney, formatOrderStatus, formatScore, statusToneClass } from '@/utils/format'
import { useConfirm } from '@/composables/useDialog'

const route = useRoute()
const router = useRouter()
const store = useCampusHubStore()
const note = ref('')
const reviewRating = ref('5')
const reviewComment = ref('')
const message = ref('')
const error = ref('')
const completionSubmitted = ref(false)
const loadingDemand = ref(false)
const refreshing = ref(false)

// Image viewer
const showImageViewer = ref(false)
const viewerInitialIndex = ref(0)

function openImageViewer(index: number) {
  viewerInitialIndex.value = index
  showImageViewer.value = true
}

async function refreshDemand(): Promise<void> {
  refreshing.value = true
  try {
    await store.fetchDemandDetail(String(route.params.id))
    if (store.currentUser) {
      await store.fetchOrders()
      const demandId = String(route.params.id)
      const hasRelatedOrder = store.orders.some((o) => o.demandId === demandId)
      if (!hasRelatedOrder) {
        await store.fetchOrderByDemandId(demandId)
      }
    }
  } finally {
    refreshing.value = false
  }
}

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
  // 优先展示订单状态相关的真实情况
  if (relatedOrder.value) {
    const s = relatedOrder.value.status
    if (s === 'COMPLETED') return '订单已完成，无法再次操作'
    if (s === 'CANCELLED') return '订单已取消，无法再次操作'
    // 区分身份：接单方 vs 发布方 vs 第三方
    const isAcceptor = store.currentUser?.id === relatedOrder.value.serviceProviderId
    const isPublisher = store.currentUser?.id === relatedOrder.value.requesterId
    if (s === 'ACCEPTED') {
      if (isAcceptor) return '您已接单，准备开始执行'
      if (isPublisher) return '需求已被接单，等待服务完成'
      return '该需求已被接单，来晚了一步。'
    }
    if (s === 'IN_PROGRESS') {
      if (isAcceptor) return '您已接单，订单正在执行中'
      if (isPublisher) return '订单正在执行中，等待服务完成'
      return '订单正在执行中。'
    }
  }
  // 后端提供的不可接单原因
  if (demand.value.acceptDisabledReason) return formatAcceptDisabledReason(demand.value.acceptDisabledReason)
  if (demand.value.status === 'REVIEWING') return '该需求仍在审核中，暂时无法接单。'
  if (demand.value.status === 'EXPIRED') return '该需求已过期，无法接单。'
  if (!store.currentUser) return '请先登录后再接单。'
  if (store.currentUser.id === demand.value.publisherId) return '这是您自己发布的需求，不能自行接单。'
  if (store.currentUser.role === 'ADMIN') return '管理员账号无法接单。'
  return null
})
// 个性化状态提示（由后端根据用户身份返回）
const acceptStatusHint = computed(() => demand.value?.acceptStatusHint ?? null)
const canStartExecution = computed(() => {
  if (!demand.value) return false
  if (typeof demand.value.canStartExecution === 'boolean') return demand.value.canStartExecution
  return relatedOrder.value?.status === 'ACCEPTED' && store.currentUser?.id === relatedOrder.value.serviceProviderId
})
const relatedReviews = computed(() => {
  const orderId = relatedOrder.value?.id
  if (!orderId) return []
  const merged = new Map<string, any>()
  // 从订单响应中获取评价（包含双方评价，第三方用户也能看到）
  if (relatedOrder.value?.reviews) {
    for (const r of relatedOrder.value.reviews) merged.set(r.id, r)
  }
  // 从 store 中补充（当前用户提交的评价）
  for (const r of store.reviews.filter((review) => review.orderId === orderId)) {
    merged.set(r.id, r)
  }
  return Array.from(merged.values())
})
const hasSubmittedReview = computed(() => {
  if (!relatedOrder.value || !store.currentUser) return false
  if (typeof relatedOrder.value.currentUserReviewed === 'boolean') {
    return relatedOrder.value.currentUserReviewed
  }
  return relatedReviews.value.some((r) => r.reviewerId === store.currentUser?.id)
})

const currentUserConfirmedCompletion = computed(() => {
  if (!relatedOrder.value || !store.currentUser) return false
  return relatedOrder.value.timeline.some(
    (t) => t.operatorId === store.currentUser!.id
      && (
        t.label.includes('接单方确认完成')
        || t.label.includes('需求方确认完成')
        || t.label.includes('已确认完成，等待需求方确认')
        || t.label.includes('已确认完成，等待接单方确认')
      )
  )
})

// 接单方是否已在时间线中确认完成（需求方需等待接单方先确认）
const providerConfirmed = computed(() => {
  if (!relatedOrder.value) return false
  return relatedOrder.value.timeline.some(
    (t) => t.operatorId === relatedOrder.value!.serviceProviderId
      && (t.label.includes('接单方确认完成') || t.label.includes('已确认完成，等待需求方确认'))
  )
})

// ========== 角色判断 ==========
const isRequester = computed(() => {
  if (!relatedOrder.value || !store.currentUser) return false
  return store.currentUser.id === relatedOrder.value.requesterId
})

const isProvider = computed(() => {
  if (!relatedOrder.value || !store.currentUser) return false
  return store.currentUser.id === relatedOrder.value.serviceProviderId
})

// ========== 仲裁功能 ==========
const arbitrationDialogOpen = ref(false)
const arbitrationReason = ref('')

/** 从时间线中查找仲裁发起人 ID */
const arbitrationInitiatorId = computed(() => {
  if (!relatedOrder.value) return undefined
  const entry = relatedOrder.value.timeline.find((t) => t.label === '发起仲裁')
  return entry?.operatorId
})

const arbitrationByRequester = computed(() => {
  const id = arbitrationInitiatorId.value
  if (!id || !relatedOrder.value) return false
  return String(id) === String(relatedOrder.value.requesterId)
})

const arbitrationByProvider = computed(() => {
  const id = arbitrationInitiatorId.value
  if (!id || !relatedOrder.value) return false
  return String(id) === String(relatedOrder.value.serviceProviderId)
})

const arbitrationTip = computed(() => {
  if (!relatedOrder.value) return ''
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
  if (isRequester.value) return '订单处于争议仲裁中，当前暂停执行，请等待管理员处理。'
  if (isProvider.value) return '订单处于争议仲裁中，当前暂停执行，请配合管理员处理。'
  return '此订单处于争议仲裁中，暂时暂停执行。'
})

const canRequestArbitration = computed(() => {
  if (!relatedOrder.value) return false
  if (!isProvider.value && !isRequester.value) return false
  return relatedOrder.value.status === 'ACCEPTED' || relatedOrder.value.status === 'IN_PROGRESS'
})

// ========== 匿名保护 ==========
const isDemandAnonymous = computed(() => {
  return demand.value?.anonymous === true || (demand.value?.anonymousCode != null && demand.value.anonymousCode !== '')
})

function reviewDisplayName(name: string, reviewerId: string): string {
  if (store.currentUser && reviewerId === store.currentUser.id) return '我'
  if (isDemandAnonymous.value) {
    const publisherId = relatedOrder.value?.requesterId
    if (publisherId && reviewerId === publisherId) {
      return demand.value?.anonymousCode ?? '匿名校友'
    }
  }
  return name
}

/** 学号脱敏：保留前3位，其余用*替代 */
function maskStudentId(id: string | undefined): string {
  if (!id) return '未知'
  if (id.length <= 3) return id + '****'
  return id.slice(0, 3) + '****'
}

// ========== 时间线增强 ==========
function formatTimelineLabel(label: string, operatorId?: string): string {
  if (!relatedOrder.value) return label
  if (label === '进行中') return '订单进行中'
  if (label === '接单方确认完成，等待需求方确认') {
    return isProvider.value ? '你已确认完成，等待需求方确认' : '接单方已确认完成，等待你的确认'
  }
  if (label === '需求方确认完成，等待接单方确认') {
    return isRequester.value ? '你已确认完成，等待接单方确认' : '需求方已确认完成，等待你的确认'
  }
  if (label === '发起仲裁' && operatorId && relatedOrder.value) {
    if (operatorId === relatedOrder.value.requesterId) {
      return isRequester.value ? '你发起了争议仲裁' : '需求方发起了争议仲裁'
    }
    if (operatorId === relatedOrder.value.serviceProviderId) {
      return isProvider.value ? '你发起了争议仲裁' : '接单方发起了争议仲裁'
    }
  }
  if (label === '已确认完成，等待对方确认' && operatorId && relatedOrder.value) {
    const isProviderAction = operatorId === relatedOrder.value.serviceProviderId
    if (isProviderAction) {
      return isProvider.value ? '你已确认完成，等待需求方确认' : '接单方已确认完成，等待你的确认'
    } else {
      return isRequester.value ? '你已确认完成，等待接单方确认' : '需求方已确认完成，等待你的确认'
    }
  }
  return label
}

const enhancedTimeline = computed(() => {
  if (!relatedOrder.value) return []
  // 映射并按时间正序排列（从上到下自然阅读顺序）
  const items = [...relatedOrder.value.timeline]
    .map((entry) => ({
      ...entry,
      displayLabel: formatTimelineLabel(entry.label, entry.operatorId)
    }))
    .sort((a, b) => a.at.localeCompare(b.at))

  // 追加评价条目
  const reviews = relatedReviews.value
  if (reviews.length > 0) {
    const requesterReviewed = reviews.some((r) => r.reviewerId === relatedOrder.value!.requesterId)
    const providerReviewed = reviews.some((r) => r.reviewerId === relatedOrder.value!.serviceProviderId)
    if (requesterReviewed) {
      const review = reviews.find((r) => r.reviewerId === relatedOrder.value!.requesterId)!
      items.push({ at: review.createdAt, label: 'reviewer', displayLabel: '需求方已评价' })
    }
    if (providerReviewed) {
      const review = reviews.find((r) => r.reviewerId === relatedOrder.value!.serviceProviderId)!
      items.push({ at: review.createdAt, label: 'provider', displayLabel: '接单方已评价' })
    }
  }
  return items
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

  if (!await useConfirm('确认接单', '确认接单？接单后将生成订单。')) return

  try {
    const order = await store.acceptDemand(demand.value.id, note.value)
    message.value = `已接单，生成订单 ${order.id}`
  } catch (acceptError) {
    error.value = handleError(acceptError, '接单失败')
  }
}

async function withdrawDemand(): Promise<void> {
  if (!demand.value) return
  const confirmed = await useConfirm(
    '撤回需求',
    `确认撤回需求“${demand.value.title}”？此操作不可撤销。`,
    { danger: true, confirmText: '确认撤回' }
  )
  if (!confirmed) return
  error.value = ''
  message.value = ''
  try {
    await store.withdrawDemand(demand.value.id)
    message.value = '需求已撤回'
    setTimeout(() => router.push('/'), 800)
  } catch (e) {
    error.value = handleError(e, '撤回失败')
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

  if (!await useConfirm('确认完成', '确认完成此订单？此操作不可撤销。', { danger: true })) return

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

function openArbitrationDialog(): void {
  arbitrationReason.value = ''
  arbitrationDialogOpen.value = true
}

function closeArbitrationDialog(): void {
  arbitrationDialogOpen.value = false
  arbitrationReason.value = ''
}

async function submitArbitration(): Promise<void> {
  if (!relatedOrder.value) return
  if (!arbitrationReason.value.trim()) {
    error.value = '请填写仲裁原因'
    return
  }

  message.value = ''
  error.value = ''
  try {
    await store.requestOrderArbitration(relatedOrder.value.id, arbitrationReason.value)
    message.value = '已提交仲裁申请，等待管理员处理。'
    closeArbitrationDialog()
  } catch (arbitrationError) {
    error.value = handleError(arbitrationError, '发起仲裁失败')
  }
}

onMounted(() => {
  loadingDemand.value = true
  void (async () => {
    try {
      await store.fetchDemandDetail(String(route.params.id))
      if (store.currentUser) {
        await store.fetchOrders()
        // 第三方用户可能不在 store.orders 中，通过 demandId 单独获取关联订单
        const demandId = String(route.params.id)
        const hasRelatedOrder = store.orders.some((o) => o.demandId === demandId)
        if (!hasRelatedOrder) {
          await store.fetchOrderByDemandId(demandId)
        }
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

  <div v-else-if="demand" class="page-grid demand-detail-layout">
    <section class="panel">
      <div class="page-head" style="margin-bottom: 12px;">
        <button type="button" class="button primary" @click="router.back()">← 返回</button>
        <button type="button" class="button primary" :disabled="refreshing" @click="refreshDemand">
          {{ refreshing ? '刷新中...' : '↻ 刷新' }}
        </button>
      </div>

      <!-- 标题 + 报酬（置顶，左右对齐） -->
      <div class="page-head" style="margin-top: 4px;">
        <div>
          <h1 class="page-title">{{ demand.title }}</h1>
        </div>
        <strong class="page-title">{{ formatMoney(demand.reward) }}</strong>
      </div>

      <!-- 发布者信息 -->
      <div class="avatar-row">
        <img :src="demand.publisher?.avatarUrl ?? demand.publisherAvatar" :alt="demand.publisher?.nickname ?? demand.publisherName" class="avatar large" />
        <div>
          <strong>发布者：{{ demand.anonymous ? (demand.anonymousCode ?? '匿名发布') : (demand.publisher?.nickname ?? demand.publisherName) }}</strong>
          <p class="subtle">信用分：{{ demand.publisher ? formatScore(demand.publisher.creditScore) : '未知' }}</p>
          <p class="meta" v-if="!demand.anonymous && demand.publisher?.studentId">发布者学号：{{ demand.publisherIdentityVisible === false ? (demand.publisherStudentIdMasked || '已隐藏') : demand.publisher.studentId }}</p>
          <p class="meta">发布于 {{ formatDateTime(demand.createdAt) }}</p>
        </div>
      </div>

      <!-- 状态与标签 -->
      <div class="status-row" style="margin-top: 12px;">
        <span class="chip" :class="relatedOrder ? statusToneClass(relatedOrder.status) : statusToneClass(demand.status)">
          {{ relatedOrder ? formatOrderStatus(relatedOrder.status) : formatDemandStatus(demand.status) }}
        </span>
        <span class="chip">{{ formatDemandCategory(demand.category) }}</span>
        <span class="chip">{{ formatCampusZone(demand.campusZone) }}</span>
        <span v-if="demand.anonymous" class="chip is-warning">匿名</span>
      </div>

      <!-- 仲裁状态横幅 -->
      <div v-if="relatedOrder?.status === 'IN_ARBITRATION'" class="arbitration-banner">
        <div class="arbitration-icon">⚖️</div>
        <div class="arbitration-content">
          <strong>订单处于争议仲裁中</strong>
          <p>{{ arbitrationTip }}</p>
        </div>
      </div>

      <!-- 需求详情（突出显示，置于时间信息之前） -->
      <div v-if="demand.description" class="demand-description-highlight">
        <p class="eyebrow demand-detail-label">需求详情</p>
        <div class="description-content">{{ demand.description }}</div>
      </div>

      <!-- 时间 -->
      <div class="list-card" style="margin-top: 12px;">
        <div class="meta"><strong>⏰ 时间</strong></div>
        <p style="margin-top: 4px;">{{ formatDateTime(demand.startTime || '') }} — {{ formatDateTime(demand.endTime || '') }}</p>
      </div>

      <!-- 地点 -->
      <div class="list-card" style="margin-top: 8px;">
        <div class="meta"><strong>📍 地点</strong></div>
        <p style="margin-top: 4px;">{{ demand.location || '未填写' }}</p>
      </div>

      <!-- 联系方式（仅接单人/发布者/管理员可见） -->
      <div v-if="demand.contactInfo" class="list-card" style="margin-top: 8px; border-color: rgba(31, 95, 83, 0.2);">
        <div class="meta"><strong>📞 联系方式</strong></div>
        <p style="margin-top: 4px; white-space: pre-wrap;">{{ demand.contactInfo }}</p>
      </div>

      <!-- 需求图片 -->
      <div v-if="demand.images && demand.images.length > 0" class="demand-images" style="margin-top: 12px;">
        <p class="eyebrow">需求图片 ({{ demand.images.length }})</p>
        <div class="image-grid">
          <div
            v-for="(imgUrl, imgIdx) in demand.images"
            :key="imgUrl"
            class="image-item"
            @click="openImageViewer(imgIdx)"
          >
            <img :src="imgUrl" :alt="`需求图片 ${imgIdx + 1}`" loading="lazy" class="demand-img" />
          </div>
        </div>
      </div>

      <!-- 标签 -->
      <div class="tag-row" style="margin-top: 12px;">
        <span class="badge is-neutral">{{ demand.anonymous ? '匿名发布' : '实名发布' }}</span>
        <span v-for="tag in demand.tags" :key="tag" class="badge is-neutral">{{ tag }}</span>
      </div>

      <div v-if="demand.status === 'CANCELLED' && demand.reviewReason" class="list-card" style="margin-top: 16px;">
        <strong>审核原因</strong>
        <p style="margin-top: 8px; color: var(--danger);">{{ demand.reviewReason }}</p>
      </div>

      <div v-if="demand.publisherId === store.currentUser?.id && (demand.status === 'REVIEWING' || demand.status === 'PENDING')" class="list-card" style="margin-top: 16px;">
        <strong>管理你的需求</strong>
        <p class="meta">{{ demand.status === 'REVIEWING' ? '该需求仍在审核中。' : '该需求已开放接单。' }}</p>
        <div class="card-actions">
          <button type="button" class="button danger" @click="withdrawDemand">撤回需求</button>
        </div>
      </div>

      <div class="list-card">
        <strong>接单留言</strong>
        <!-- 接单后留言已确定，只读显示；接单前显示可编辑的 textarea -->
        <p v-if="relatedOrder" class="meta" style="margin-top: 4px;">{{ relatedOrder.note || '暂无留言' }}</p>
        <div v-if="canAccept" class="field">
          <label for="accept-note">留言内容</label>
          <textarea id="accept-note" v-model="note" placeholder="给发布者留一句话"></textarea>
        </div>
        <div class="card-actions">
          <button
            v-if="canAccept"
            type="button"
            class="button primary"
            @click="acceptCurrentDemand"
          >
            立即接单
          </button>

          <button v-if="canStartExecution" type="button" class="button primary" @click="startOrder">开始执行</button>
          <button
            v-if="relatedOrder?.status === 'IN_PROGRESS' && store.currentUser?.id === relatedOrder.serviceProviderId && !currentUserConfirmedCompletion"
            type="button"
            class="button primary"
            @click="completeOrder"
          >
            提交完成确认
          </button>
          <button
            v-else-if="relatedOrder?.status === 'IN_PROGRESS' && store.currentUser?.id === relatedOrder.requesterId && providerConfirmed && !currentUserConfirmedCompletion"
            type="button"
            class="button primary"
            @click="completeOrder"
          >
            确认完成
          </button>
          <span v-else-if="relatedOrder?.status === 'IN_PROGRESS' && !completionSubmitted" class="chip is-warning">{{ currentUserConfirmedCompletion || providerConfirmed ? '等待对方确认完成' : '等待接单方确认完成' }}</span>
          <button v-if="canRequestArbitration" type="button" class="button secondary" @click="openArbitrationDialog">发起仲裁</button>
        </div>
      </div>

      <span v-if="!canAccept && acceptDisabledReason" class="chip is-warning">{{ acceptDisabledReason }}</span>
      <span v-if="acceptStatusHint" class="chip is-warning">{{ acceptStatusHint }}</span>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
    </section>

    <section class="panel demand-sidebar">
      <div class="sidebar-head">
        <p class="eyebrow">关联信息</p>
        <h2 class="section-title">订单、时间线与评价</h2>
      </div>

      <div v-if="relatedOrder" class="section-grid">
        <div class="list-card">
          <div class="status-row">
            <span class="chip" :class="statusToneClass(relatedOrder.status)">{{ formatOrderStatus(relatedOrder.status) }}</span>
            <span class="chip">订单 {{ relatedOrder.id }}</span>
          </div>
          <div class="avatar-row">
            <img :src="relatedOrder.serviceProviderAvatar" :alt="relatedOrder.serviceProviderName" class="avatar" />
            <div>
              <strong>接单方：{{ relatedOrder.serviceProviderName }}</strong>
              <div class="meta">接单方学号：{{ isDemandAnonymous ? maskStudentId(relatedOrder.serviceProviderStudentId) : (relatedOrder.serviceProviderStudentId || '未知') }}</div>
              <div class="meta">信用分 {{ formatScore(relatedOrder.serviceProviderCreditScore) }}</div>
            </div>
          </div>
          <p>接单留言：{{ relatedOrder.note || '暂无留言' }}</p>
        </div>

        <div class="list-card">
          <strong>流程时间线</strong>
          <div class="timeline">
            <div v-for="(entry, idx) in enhancedTimeline" :key="`${entry.at}-${entry.label}-${idx}`" class="timeline-item">
              <span>{{ entry.displayLabel }}</span>
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
          <div class="review-list">
            <div v-for="review in relatedReviews" :key="review.id" class="timeline-item">
              <div>
                <strong>{{ reviewDisplayName(review.reviewerName, review.reviewerId) }} → {{ reviewDisplayName(review.targetName, review.targetId) }}</strong>
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

  <!-- 全屏图片预览 -->
  <ImageViewer
    v-if="showImageViewer && demand?.images?.length"
    :images="demand.images"
    :initial-index="viewerInitialIndex"
    @close="showImageViewer = false"
  />

  <!-- 仲裁对话框 -->
  <teleport to="body">
    <div v-if="arbitrationDialogOpen" class="modal-backdrop" @click.self="closeArbitrationDialog">
      <div class="modal-card panel">
        <div class="modal-head">
          <div>
            <p class="eyebrow">订单仲裁</p>
            <h3 class="section-title">提交争议说明</h3>
          </div>
          <button type="button" class="modal-close" @click="closeArbitrationDialog" title="关闭" aria-label="关闭"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
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
  </div>
</template>

<style scoped>
/* ========== 双栏布局 ========== */
.demand-detail-layout {
  grid-template-columns: 1fr 380px;
  align-items: start;
}

.demand-sidebar {
  position: sticky;
  top: 20px;
  max-height: calc(100vh - 40px);
  overflow-y: auto;
}

/* ========== 侧栏标题 ========== */
.sidebar-head {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--panel-border);
  margin-bottom: 4px;
}

.sidebar-head .eyebrow {
  margin-bottom: 4px;
}

.sidebar-head .section-title {
  margin: 0;
  font-size: 1.15em;
  font-weight: 700;
  color: var(--text-strong);
  text-align: left;
}

/* ========== 仲裁横幅 ========== */
.arbitration-banner {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-top: 12px;
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

/* ========== 需求详情突出显示 ========== */
.demand-description-highlight {
  margin-top: 12px;
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

.demand-detail-label {
  font-size: 1.15em;
  font-family: var(--heading);
  letter-spacing: 0.12em;
}

.description-content {
  font-size: 1.02em;
  line-height: 1.7;
  color: var(--text-strong);
  white-space: pre-wrap;
  word-break: break-word;
  font-weight: 700;
}

/* ========== 模态框 ========== */
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

/* ========== 需求图片 ========== */
.demand-images {
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

/* ========== 响应式：移动端切换为单栏 ========== */
@media (max-width: 960px) {
  .demand-detail-layout {
    grid-template-columns: 1fr;
  }

  .demand-sidebar {
    position: static;
    max-height: none;
    overflow-y: visible;
  }
}
</style>
