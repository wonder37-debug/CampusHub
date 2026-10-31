<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { handleError } from '@/utils/errorHandler'
import { useRoute, useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import SkeletonCard from '@/components/SkeletonCard.vue'
import ImageViewer from '@/components/ImageViewer.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import PrivateImage from '@/components/PrivateImage.vue'
import ErrorState from '@/components/ErrorState.vue'
import { formatOrderStatus, formatRelativeTime, formatScore, formatCampusZone, statusToneClass } from '@/utils/format'
import { useConfirm, useAlert } from '@/composables/useDialog'
import { useModalA11y } from '@/composables/useModalA11y'
import { canCancelOrder, getOrderFlowKind } from '@/utils/detailRules'

const route = useRoute()
const router = useRouter()
const store = useCampusHubStore()

const order = computed(() => store.getOrderById(String(route.params.id)))
const isRequester = computed(() => order.value && store.currentUser?.id === order.value.requesterId)
const isProvider = computed(() => order.value && store.currentUser?.id === order.value.serviceProviderId)
const orderFlowKind = computed(() => getOrderFlowKind(order.value?.demandInteractionMode))
const message = ref('')
const error = ref('')
const reviewRating = ref('5')
const reviewComment = ref('')
const loadingOrder = ref(false)
const refreshing = ref(false)
const loadState = ref<'loading' | 'ready' | 'not-found' | 'forbidden' | 'error'>('loading')
const loadFailure = ref('')
let latestOrderLoad = 0
const arbitrationDialogOpen = ref(false)
const arbitrationReason = ref('')
const arbitrationImages = ref<string[]>([])
const arbitrationResponseDialogOpen = ref(false)
const arbitrationResponseReason = ref('')
const arbitrationResponseImages = ref<string[]>([])
const proofImages = ref<string[]>([])
const arbitrationDialogRef = ref<HTMLElement | null>(null)
const arbitrationResponseDialogRef = ref<HTMLElement | null>(null)

// 仲裁弹窗：Esc 关闭、焦点锁在弹窗内、关闭后归还原位
useModalA11y(arbitrationDialogOpen, arbitrationDialogRef, { onClose: closeArbitrationDialog })
useModalA11y(arbitrationResponseDialogOpen, arbitrationResponseDialogRef, { onClose: closeArbitrationResponseDialog })
// 关键操作（开始执行/提交完成/取消/评价/仲裁）防重复点击 loading
const submitting = ref(false)

onUnmounted(() => {
  // 递增版本号让在途的 openPrivateImageViewer 结果作废，避免卸载后新 Blob URL 泄漏
  resetViewer()
})

// Image viewer
const showImageViewer = ref(false)
const viewerInitialIndex = ref(0)
const viewerImages = ref<string[]>([])
// 与 viewerImages 等长：空字符串表示加载中/可正常显示，非空表示该张凭证加载失败的原因
const viewerFailures = ref<string[]>([])
let viewerVersion = 0

function revokeViewerBlobs() {
  viewerImages.value.forEach(u => {
    if (u.startsWith('blob:')) URL.revokeObjectURL(u)
  })
}

/** 关闭/切换预览前的统一清理：自增版本号让在途请求作废，并释放已创建的 Blob URL */
function resetViewer() {
  viewerVersion++
  revokeViewerBlobs()
  viewerImages.value = []
  viewerFailures.value = []
}

function closeImageViewer() {
  resetViewer()
  showImageViewer.value = false
}

function openImageViewer(imagesOrIndex: string[] | number, idx?: number) {
  resetViewer()
  if (Array.isArray(imagesOrIndex)) {
    const index = idx ?? 0
    viewerImages.value = imagesOrIndex
    viewerInitialIndex.value = index
  } else {
    viewerImages.value = order.value?.demandImages ?? []
    viewerInitialIndex.value = imagesOrIndex
  }
  viewerFailures.value = viewerImages.value.map(() => '')
  showImageViewer.value = true
}

async function openPrivateImageViewer(urls: string[], idx: number) {
  resetViewer()
  viewerInitialIndex.value = idx
  // 先用空占位打开预览：私密凭证必须等带鉴权的请求成功后才能显示，
  // 不能先把原始 URL 交给 <img>，那会绕过服务端鉴权直接 401
  viewerImages.value = urls.map(() => '')
  viewerFailures.value = urls.map(() => '凭证加载中…')
  showImageViewer.value = true
  const expectedVersion = viewerVersion
  const results = await Promise.all(urls.map(url => store.fetchProofImageBlob(url)))
  if (expectedVersion !== viewerVersion) {
    // 预览已被切换/关闭：丢弃结果，同时释放本次新创建的 Blob URL，避免泄漏
    results.forEach(r => {
      if (r.ok) URL.revokeObjectURL(r.objectUrl)
    })
    return
  }
  revokeViewerBlobs()
  viewerImages.value = results.map(r => (r.ok ? r.objectUrl : ''))
  viewerFailures.value = results.map(r => (r.ok ? '' : r.message))
}

async function refreshOrder(): Promise<void> {
  refreshing.value = true
  loadingOrder.value = true
  loadState.value = 'loading'
  loadFailure.value = ''
  const orderId = String(route.params.id ?? '')
  const requestVersion = ++latestOrderLoad
  try {
    const result = await store.fetchOrderDetail(orderId)
    if (requestVersion !== latestOrderLoad) return
    loadState.value = result ? 'ready' : 'not-found'
  } catch (requestError) {
    if (requestVersion !== latestOrderLoad) return
    loadFailure.value = handleError(requestError, '订单详情暂时无法加载')
    const status = Number((requestError as { status?: unknown })?.status)
    const code = String((requestError as { code?: unknown })?.code ?? '')
    loadState.value = status === 403 || code === 'PERMISSION_DENIED'
      ? 'forbidden'
      : status === 404 || code === 'RESOURCE_NOT_FOUND' ? 'not-found' : 'error'
  } finally {
    if (requestVersion === latestOrderLoad) {
      loadingOrder.value = false
      refreshing.value = false
    }
  }
}

watch(() => String(route.params.id ?? ''), () => { void refreshOrder() }, { immediate: true })

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
const orderProgressCopy = computed(() => {
  const currentOrder = order.value
  if (!currentOrder) return ''
  if (store.currentUser?.role === 'ADMIN') return '当前账号查看订单记录；订单双方履约操作不在此账号开放。'
  if (currentOrder.status === 'ACCEPTED') {
    return isProvider.value ? '订单已建立，可以开始执行。' : '等待接单方开始执行；后续进度在此订单中更新。'
  }
  if (currentOrder.status === 'IN_PROGRESS') {
    if (completionHint.value) return ''
    if (isProvider.value && currentUserConfirmedCompletion.value) {
      return '你已提交完成确认，正在等待需求方查看凭证并确认。'
    }
    if (providerConfirmed.value && isRequester.value) {
      return '接单方已提交完成凭证，请查看凭证并确认。'
    }
    return isProvider.value
      ? '完成任务后提交 1–3 张凭证，需求方查看凭证并确认。'
      : '接单方正在执行；收到完成确认后，请查看凭证并确认。'
  }
  if (currentOrder.status === 'COMPLETED') {
    const automaticallyCompleted = currentOrder.timeline.some((entry) => entry.note === 'SYSTEM_AUTO_COMPLETED')
    return automaticallyCompleted
      ? '接单方提交完成凭证后 48 小时内未收到需求方确认，订单已按规则自动完成。'
      : '双方已确认完成，订单奖励已按规则结算。'
  }
  if (currentOrder.status === 'CANCELLED') return '订单已取消，原需求保持关闭，历史记录仍可查看。'
  return ''
})
// 后端在匿名需求下对接单方隐藏发布者真实 ID，因此不按 ID 匹配，改按视角相对展示待评价对象
const pendingReviewTargetLabel = computed(() => {
  if (!order.value || order.value.status !== 'COMPLETED' || hasSubmittedReview.value) return ''
  if (isProvider.value) return order.value.requesterName || '需求方'
  if (isRequester.value) return order.value.serviceProviderName || '接单方'
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
  if (order.value.status === 'ACCEPTED' && isProvider.value) return true
  if (order.value.status === 'IN_PROGRESS' && (isProvider.value || isRequester.value)) return true
  return false
})

const canCancelCurrentOrder = computed(() => order.value && canCancelOrder(
  order.value,
  store.currentUser?.id,
  store.currentUser?.role
))

const canRequestArbitration = computed(() => {
  if (!order.value) return false
  if (!isProvider.value && !isRequester.value) return false
  return order.value.status === 'ACCEPTED' || order.value.status === 'IN_PROGRESS'
})

const currentUserConfirmedCompletion = computed(() => {
  if (!order.value || !store.currentUser) return false
  return order.value.timeline.some(
    (t) => t.operatorId === store.currentUser!.id
      && (t.note === 'PROVIDER_CONFIRMED_COMPLETION' || t.note === 'REQUESTER_CONFIRMED_COMPLETION')
  )
})

const providerConfirmed = computed(() => {
  if (!order.value) return false
  return order.value.timeline.some(
    (t) => t.operatorId === order.value!.serviceProviderId
      && t.note === 'PROVIDER_CONFIRMED_COMPLETION'
  )
})

const isDemandAnonymous = computed(() => order.value?.anonymous === true)

/** 从时间线中查找仲裁发起人 ID */
const arbitrationInitiatorId = computed(() => {
  if (!order.value) return undefined
  const entry = order.value.timeline.find((t) => t.note?.startsWith('ARBITRATION_REQUESTED'))
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
  if (!order.value || submitting.value) return
  submitting.value = true
  message.value = ''
  error.value = ''
  try {
    await store.startOrder(order.value.id)
    message.value = '订单已进入进行中。'
  } catch (e) {
    error.value = handleError(e, '操作失败')
  } finally {
    submitting.value = false
  }
}

async function completeOrder(): Promise<void> {
  if (!order.value || submitting.value) return

  submitting.value = true
  try {
    // 接单方提交完成必须上传 1-3 张凭证
    if (isProvider.value && (proofImages.value.length < 1 || proofImages.value.length > 3)) {
      await useAlert('凭证不足', '请上传 1-3 张完成凭证图片后再提交完成。')
      return
    }

    if (!await useConfirm('确认完成', '确认完成此订单？此操作不可撤销。', { danger: true })) return

    message.value = ''
    error.value = ''

    const proofImageUrls = isProvider.value ? proofImages.value : null
    const updatedOrder = await store.completeOrder(order.value.id, proofImageUrls)
    message.value = updatedOrder.status === 'COMPLETED'
      ? '双方都已确认完成，订单已完成。'
      : '已提交完成确认，等待对方确认。'
    if (isProvider.value) proofImages.value = []
  } catch (completeError) {
    error.value = handleError(completeError, '操作失败')
  } finally {
    submitting.value = false
  }
}

async function cancelOrder(): Promise<void> {
  if (!order.value || submitting.value) return

  submitting.value = true
  try {
    if (!await useConfirm('确认取消', '确认取消此订单？此操作不可撤销。', { danger: true })) return

    message.value = ''
    error.value = ''
    await store.cancelOrder(order.value.id)
    message.value = '订单已取消。'
  } catch (cancelError) {
    error.value = handleError(cancelError, '取消失败')
  } finally {
    submitting.value = false
  }
}

function openArbitrationDialog(): void {
  arbitrationReason.value = ''
  arbitrationImages.value = []
  error.value = ''
  arbitrationDialogOpen.value = true
}

function closeArbitrationDialog(): void {
  arbitrationDialogOpen.value = false
  arbitrationReason.value = ''
  arbitrationImages.value = []
}

function openArbitrationResponseDialog(): void {
  arbitrationResponseReason.value = ''
  arbitrationResponseImages.value = []
  error.value = ''
  arbitrationResponseDialogOpen.value = true
}

function closeArbitrationResponseDialog(): void {
  arbitrationResponseDialogOpen.value = false
  arbitrationResponseReason.value = ''
  arbitrationResponseImages.value = []
}

async function submitArbitration(): Promise<void> {
  if (!order.value || submitting.value) return
  if (!arbitrationReason.value.trim()) {
    error.value = '请填写仲裁原因'
    return
  }

  message.value = ''
  error.value = ''
  submitting.value = true
  try {
    await store.requestOrderArbitration(order.value.id, arbitrationReason.value, arbitrationImages.value)
    message.value = '已提交仲裁申请，等待管理员处理。'
    closeArbitrationDialog()
  } catch (arbitrationError) {
    error.value = handleError(arbitrationError, '发起仲裁失败')
  } finally {
    submitting.value = false
  }
}

async function submitArbitrationResponse(): Promise<void> {
  if (!order.value || submitting.value) return
  if (!arbitrationResponseReason.value.trim()) {
    error.value = '请填写补充说明'
    return
  }

  message.value = ''
  error.value = ''
  submitting.value = true
  try {
    await store.submitArbitrationResponse(order.value.id, arbitrationResponseReason.value, arbitrationResponseImages.value)
    message.value = '已提交补充说明，等待管理员处理。'
    closeArbitrationResponseDialog()
  } catch (arbitrationError) {
    error.value = handleError(arbitrationError, '提交补充说明失败')
  } finally {
    submitting.value = false
  }
}

async function submitReview(): Promise<void> {
  if (!order.value || submitting.value) return
  message.value = ''
  error.value = ''
  submitting.value = true
  try {
    await store.submitReview(order.value.id, Number(reviewRating.value), reviewComment.value)
    message.value = '评价已提交。'
    reviewComment.value = ''
  } catch (e) {
    error.value = handleError(e, '评价失败')
  } finally {
    submitting.value = false
  }
}

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

  <ErrorState v-else-if="loadState === 'error'" title="订单详情加载失败" :description="loadFailure" :retry="true" retry-label="重试" @retry="refreshOrder" />
  <ErrorState v-else-if="loadState === 'forbidden'" title="无权查看此订单" description="订单履约信息仅向订单双方和有权管理员开放。" />
  <div v-else-if="loadState === 'not-found'" class="empty-state"><strong>订单不存在</strong><p>请检查订单链接，或从原始需求重新进入。</p></div>

  <div v-else-if="order && loadState === 'ready'" class="page-grid order-detail-layout">
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
        <span v-if="order.status === 'IN_PROGRESS' && completionHint" class="chip is-warning">{{ completionHint }}</span>
        <span class="chip">{{ orderFlowKind === 'selected-response' ? '响应选定后建立' : '直接接单' }}</span>
      </div>
      <p v-if="orderProgressCopy" class="order-progress-copy">{{ orderProgressCopy }}</p>

      <div class="order-source-note">
        <div>
          <strong>{{ orderFlowKind === 'selected-response' ? '由需求响应进入履约' : '直接接单委托' }}</strong>
          <p>{{ orderFlowKind === 'selected-response' ? '双方已在需求中完成选择，当前页面只处理正式订单履约。' : '接单方承接了发布者的直接委托。' }}</p>
        </div>
        <button type="button" class="button secondary" @click="router.push({ name: 'DemandDetail', params: { id: order.demandId } })">查看原始需求</button>
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

      <!-- 仲裁双方举证材料 -->
      <div v-if="order.arbitrationSubmissions && order.arbitrationSubmissions.length > 0" class="list-card" style="margin-top: 12px;">
        <div class="meta"><strong>⚖️ 双方举证材料</strong></div>
        <div v-for="sub in order.arbitrationSubmissions" :key="String(sub.id ?? sub.submitterId + sub.submittedAt)" style="margin-top: 12px; padding-bottom: 12px; border-bottom: 1px solid rgba(0,0,0,0.08);">
          <div class="meta">
            <span class="chip" :class="sub.role === 'INITIATOR' ? 'is-warning' : 'is-info'">
              {{ sub.role === 'INITIATOR' ? '发起方' : '另一方' }}
            </span>
            <span style="margin-left: 8px;">{{ sub.submitterName }}</span>
            <span style="margin-left: 8px;" class="meta">{{ formatRelativeTime(sub.submittedAt) }}</span>
          </div>
          <p style="margin-top: 6px; white-space: pre-wrap;">{{ sub.reason }}</p>
          <div v-if="sub.imageUrls && sub.imageUrls.length > 0" style="display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px;">
            <PrivateImage
              v-for="(url, idx) in sub.imageUrls"
              :key="url + idx"
              :src="url"
              :alt="'证据图片' + (idx + 1)"
              cursor="pointer"
              @click="openPrivateImageViewer(sub.imageUrls, idx)"
            />
          </div>
        </div>
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
        <div class="mini-stat"><span class="subtle">奖励</span><strong>{{ order.demandReward ? order.demandReward + ' 校邻币' : '无奖励' }}</strong></div>
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
        <div class="meta"><strong>{{ orderFlowKind === 'selected-response' ? '已选响应' : '接单备注' }}</strong></div>
        <p style="margin-top: 4px; white-space: pre-wrap;">{{ order.note || '暂无留言' }}</p>
      </div>

      <!-- 完成凭证上传（接单方提交完成前） -->
      <div v-if="order.status === 'IN_PROGRESS' && isProvider && !currentUserConfirmedCompletion" class="list-card" style="margin-top: 12px;">
        <p class="eyebrow">完成凭证</p>
        <p class="meta">请上传 1-3 张完成凭证图片，发布者确认完成时可查看。</p>
        <ImageUploader v-model="proofImages" :max-count="3" purpose="proof" />
      </div>

      <!-- 完成凭证展示（已提交凭证后供发布者/管理员查看，通过认证 Blob 加载） -->
      <div v-if="order.proofImageUrls && order.proofImageUrls.length > 0" class="order-images">
        <p class="eyebrow">完成凭证 ({{ order.proofImageUrls.length }})</p>
        <div class="image-grid">
          <PrivateImage
            v-for="(url, pIdx) in order.proofImageUrls"
            :key="url"
            :src="url"
            :alt="'凭证图片 ' + (pIdx + 1)"
            width="140px"
            height="140px"
            cursor="pointer"
            @click="openPrivateImageViewer(order.proofImageUrls, pIdx)"
          />
        </div>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

      <!-- 订单操作 -->
      <div v-if="hasAvailableActions || canRequestArbitration || order.canSubmitArbitrationResponse" class="order-actions-section">
        <p class="eyebrow">订单操作</p>
        <div class="card-actions">
          <button v-if="order.status === 'ACCEPTED' && isProvider" type="button" class="button primary" :disabled="submitting" @click="startOrder">{{ submitting ? '处理中...' : '开始执行' }}</button>
          <button
            v-if="order.status === 'IN_PROGRESS' && isProvider && !currentUserConfirmedCompletion"
            type="button"
            class="button primary"
            :disabled="submitting"
            @click="completeOrder"
          >
            {{ submitting ? '处理中...' : '提交完成确认' }}
          </button>
          <button
            v-if="order.status === 'IN_PROGRESS' && isRequester && providerConfirmed && !currentUserConfirmedCompletion"
            type="button"
            class="button primary"
            :disabled="submitting"
            @click="completeOrder"
          >
            {{ submitting ? '处理中...' : '确认完成' }}
          </button>
          <span
            v-if="order.status === 'IN_PROGRESS' && completionHint && (isProvider || isRequester)"
            class="chip is-warning"
          >{{ completionHint }}</span>
          <button v-if="canCancelCurrentOrder" type="button" class="button danger" :disabled="submitting" @click="cancelOrder">{{ submitting ? '处理中...' : '取消订单' }}</button>
          <button v-if="canRequestArbitration" type="button" class="button secondary" :disabled="submitting" @click="openArbitrationDialog">发起仲裁</button>
          <button v-if="order.canSubmitArbitrationResponse" type="button" class="button secondary" :disabled="submitting" @click="openArbitrationResponseDialog">补充说明</button>
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
            <select id="review-rating" class="input" v-model="reviewRating">
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
          <button type="button" class="button primary" :disabled="submitting" @click="submitReview">{{ submitting ? '处理中...' : '提交评价' }}</button>
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
      <div
        ref="arbitrationDialogRef"
        class="modal-card panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="arbitration-dialog-title"
      >
        <div class="modal-head">
          <div>
            <p class="eyebrow">订单仲裁</p>
            <h3 id="arbitration-dialog-title" class="section-title">提交争议说明</h3>
          </div>
          <button type="button" class="modal-close" @click="closeArbitrationDialog" title="关闭" aria-label="关闭"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
        </div>

        <p class="page-summary" style="margin-top: 0;">提交后订单会进入仲裁中状态，双方暂时无法继续推进订单，管理员会根据说明做出处理。</p>
        <div class="field">
          <label for="arbitration-reason">仲裁原因</label>
          <textarea id="arbitration-reason" v-model="arbitrationReason" rows="5" data-autofocus placeholder="请简要说明争议点、当前情况和希望管理员关注的信息"></textarea>
        </div>

        <div class="field" role="group" aria-label="证据图片（可选，最多5张）">
          <label>证据图片（可选，最多5张）</label>
          <ImageUploader v-model="arbitrationImages" :max-count="5" purpose="proof" />
        </div>

        <p v-if="error" class="hero-badge" role="alert" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

        <div class="card-actions" style="justify-content: flex-end;">
          <button type="button" class="button secondary" @click="closeArbitrationDialog">取消</button>
          <button type="button" class="button primary" :disabled="!arbitrationReason.trim() || submitting" @click="submitArbitration">{{ submitting ? '处理中...' : '提交仲裁' }}</button>
        </div>
      </div>
    </div>
  </teleport>

  <!-- 补充说明对话框 -->
  <teleport to="body">
    <div v-if="arbitrationResponseDialogOpen" class="modal-backdrop" @click.self="closeArbitrationResponseDialog">
      <div
        ref="arbitrationResponseDialogRef"
        class="modal-card panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="arbitration-response-dialog-title"
      >
        <div class="modal-head">
          <div>
            <p class="eyebrow">仲裁补充说明</p>
            <h3 id="arbitration-response-dialog-title" class="section-title">提交补充说明</h3>
          </div>
          <button type="button" class="modal-close" @click="closeArbitrationResponseDialog" title="关闭" aria-label="关闭"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
        </div>

        <p class="page-summary" style="margin-top: 0;">作为另一方，您可以提交补充说明和证据图片，管理员将根据双方材料做出裁决。</p>
        <div class="field">
          <label for="arbitration-response-reason">补充说明</label>
          <textarea id="arbitration-response-reason" v-model="arbitrationResponseReason" rows="5" data-autofocus placeholder="请补充您的说明和理由"></textarea>
        </div>

        <div class="field" role="group" aria-label="证据图片（可选，最多5张）">
          <label>证据图片（可选，最多5张）</label>
          <ImageUploader v-model="arbitrationResponseImages" :max-count="5" purpose="proof" />
        </div>

        <p v-if="error" class="hero-badge" role="alert" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>

        <div class="card-actions" style="justify-content: flex-end;">
          <button type="button" class="button secondary" @click="closeArbitrationResponseDialog">取消</button>
          <button type="button" class="button primary" :disabled="!arbitrationResponseReason.trim() || submitting" @click="submitArbitrationResponse">{{ submitting ? '处理中...' : '提交说明' }}</button>
        </div>
      </div>
    </div>
  </teleport>

  <!-- 全屏图片预览 -->
  <ImageViewer
    v-if="showImageViewer && viewerImages.length"
    :images="viewerImages"
    :initial-index="viewerInitialIndex"
    :failure-messages="viewerFailures"
    @close="closeImageViewer"
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

.order-source-note {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-top: 14px;
  padding: 16px 18px;
  border: 1px solid var(--panel-border);
  border-radius: 14px;
  background: #f8faf8;
}

.order-source-note strong {
  color: var(--text-strong);
}

.order-source-note p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 0.9rem;
  line-height: 1.5;
}

.order-progress-copy {
  margin: 12px 0 0;
  padding: 12px 14px;
  border-left: 3px solid var(--accent);
  background: var(--accent-soft);
  color: var(--text-strong);
  line-height: 1.55;
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
