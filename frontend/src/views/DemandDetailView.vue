<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { Clock, MapPin, Phone } from '@lucide/vue'
import { handleError } from '@/utils/errorHandler'
import { useRoute, useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import SkeletonCard from '@/components/SkeletonCard.vue'
import ErrorState from '@/components/ErrorState.vue'
import ImageViewer from '@/components/ImageViewer.vue'
import { formatAcceptDisabledReason, formatCampusZone, formatDateTime, formatDemandCategory, formatDemandStatus, formatInteractionMode, formatOrderStatus, formatResponseStatus, formatScore, statusToneClass } from '@/utils/format'
import { useConfirm, useAlert } from '@/composables/useDialog'
import { canRespondToDemand, canSelectResponse, canViewSelectedResponse, getDemandDetailMode } from '@/utils/detailRules'

const route = useRoute()
const router = useRouter()
const store = useCampusHubStore()
const note = ref('')
const message = ref('')
const error = ref('')
const loadState = ref<'loading' | 'ready' | 'not-found' | 'forbidden' | 'error'>('loading')
const loadFailure = ref('')
const submitting = ref(false)
const loadingDemand = ref(false)
const refreshing = ref(false)
const responseContent = ref('')
const selectedResponseIds = ref<string[]>([])
const responseReviewRating = ref('5')
const responseReviewComment = ref('')
const reviewingResponseId = ref<string | null>(null)

// Image viewer
const showImageViewer = ref(false)
const viewerInitialIndex = ref(0)
const viewerImages = ref<string[]>([])
// 与 viewerImages 等长：空字符串表示加载中/可正常显示，非空表示该张凭证加载失败的原因
const viewerFailures = ref<string[]>([])
let viewerVersion = 0
let latestDemandLoad = 0
const imageLoadFailures = ref<Record<string, boolean>>({})
// 封面比例跟随首张图原比例，限制在 [4/5, 2/1]，避免极端细长图撑爆版面；加载完成前用 16/10 兜底
const coverRatio = ref('16 / 10')

function onCoverImageLoad(event: Event): void {
  const img = event.target as HTMLImageElement
  const ratio = img.naturalWidth / img.naturalHeight
  if (!Number.isFinite(ratio) || ratio <= 0) return
  coverRatio.value = String(Math.min(Math.max(ratio, 0.8), 2))
}

function resetViewer() {
  viewerVersion++
  viewerImages.value = []
  viewerFailures.value = []
}

function closeImageViewer() {
  resetViewer()
  showImageViewer.value = false
}

function openImageViewer(index: number) {
  resetViewer()
  viewerImages.value = demand.value?.images ?? []
  viewerInitialIndex.value = index
  viewerFailures.value = viewerImages.value.map(() => '')
  showImageViewer.value = true
}

onUnmounted(() => {
  resetViewer()
})

async function refreshDemand(): Promise<void> {
  loadingDemand.value = true
  refreshing.value = true
  loadState.value = 'loading'
  loadFailure.value = ''
  const demandId = String(route.params.id ?? '')
  const requestVersion = ++latestDemandLoad
  store.demandResponses = []
  store.demandResponsesDemandId = demandId
  try {
    const loadedDemand = await store.fetchDemandDetail(demandId)
    if (requestVersion !== latestDemandLoad) return
    if (!loadedDemand) {
      loadState.value = 'not-found'
      return
    }
    store.demandResponses = []
    if (store.currentUser) {
      await store.fetchOrders()
      if (requestVersion !== latestDemandLoad) return
      const hasRelatedOrder = store.orders.some((o) => o.demandId === demandId)
      if (!hasRelatedOrder) {
        await store.fetchOrderByDemandId(demandId)
        if (requestVersion !== latestDemandLoad) return
      }
    }
    const loadedResponses = await store.fetchResponses(demandId)
    if (requestVersion !== latestDemandLoad) return
    store.demandResponsesDemandId = demandId
    store.demandResponses = loadedResponses
    loadState.value = 'ready'
  } catch (requestError) {
    if (requestVersion !== latestDemandLoad) return
    loadFailure.value = handleError(requestError, '需求详情暂时无法加载')
    const status = Number((requestError as { status?: unknown })?.status)
    const code = String((requestError as { code?: unknown })?.code ?? '')
    loadState.value = status === 403 || code === 'PERMISSION_DENIED'
      ? 'forbidden'
      : status === 404 || code === 'RESOURCE_NOT_FOUND' ? 'not-found' : 'error'
  } finally {
    if (requestVersion === latestDemandLoad) {
      loadingDemand.value = false
      refreshing.value = false
    }
  }
}

watch(() => String(route.params.id ?? ''), (id, previousId) => {
  if (previousId !== undefined && id !== previousId) {
    // 切换需求时复位封面比例，避免上一条需求的首图比例残留
    coverRatio.value = '16 / 10'
    void refreshDemand()
  }
})

const demand = computed(() => store.getDemandById(String(route.params.id)))
const relatedOrder = computed(() => store.orders.find((order) => order.demandId === demand.value?.id))
const canAccept = computed(() => {
  if (!demand.value) return false
  // 仅 DIRECT_ACCEPT 走立即接单；SELECT_ONE/SELECT_MANY/HELP 走 Response 选择/采纳
  if ((demand.value.interactionMode ?? 'DIRECT_ACCEPT') !== 'DIRECT_ACCEPT') return false
  return demand.value.canAccept === true
    && demand.value.status === 'PENDING'
    && Boolean(store.currentUser)
    && store.currentUser?.role !== 'ADMIN'
    && store.currentUser?.id !== demand.value.publisherId
})

const acceptDisabledReason = computed(() => {
  if (!demand.value) return '未找到需求'
  // 优先展示订单状态相关的真实情况
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
async function acceptCurrentDemand(): Promise<void> {
  if (!demand.value || submitting.value) {
    return
  }

  error.value = ''
  message.value = ''

  if (store.currentUser?.role === 'ADMIN') {
    error.value = '管理员账号无法接单'
    return
  }

  if (!await useConfirm('确认接单', '确认接单？接单后将生成订单。')) return

  submitting.value = true
  try {
    const order = await store.acceptDemand(demand.value.id, note.value)
    message.value = `已接单，生成订单 ${order.id}`
    await refreshDemand()
  } catch (acceptError) {
    error.value = handleError(acceptError, '接单失败')
  } finally {
    submitting.value = false
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
    await refreshDemand()
  } catch (e) {
    error.value = handleError(e, '撤回失败')
  }
}

// ========== 业务模型 2.0 Response 交互 ==========
const interactionMode = computed(() => demand.value?.interactionMode ?? 'DIRECT_ACCEPT')
const detailMode = computed(() => demand.value ? getDemandDetailMode(demand.value) : 'direct-accept')
const responses = computed(() => store.demandResponsesDemandId === String(route.params.id ?? '') ? store.demandResponses : [])
const isPublisher = computed(() => !!store.currentUser && store.currentUser.id === demand.value?.publisherId)
const canRespond = computed(() => demand.value && canRespondToDemand(demand.value, store.currentUser?.id, store.currentUser?.role))
const selectedCount = computed(() => demand.value?.selectedParticipantCount ?? null)
const targetCount = computed(() => demand.value?.targetParticipantCount ?? null)
const remainingSlots = computed(() => selectedCount.value !== null && targetCount.value !== null
  ? Math.max(0, targetCount.value - selectedCount.value)
  : null)
const hasOrderAccess = computed(() => Boolean(relatedOrder.value && store.currentUser && (
  store.currentUser.role === 'ADMIN'
  || store.currentUser.id === relatedOrder.value.requesterId
  || store.currentUser.id === relatedOrder.value.serviceProviderId
)))
const isFormalSelectOne = computed(() => detailMode.value === 'select-one' && Boolean(relatedOrder.value))
const myActiveResponse = computed(() => {
  const userId = store.currentUser?.id
  if (!userId) return null
  return responses.value.find((response) => response.authorId === userId
    && (response.status === 'PENDING' || response.status === 'SELECTED')) ?? null
})
const visibleResponses = computed(() => responses.value.filter((response) => {
  if (response.id === myActiveResponse.value?.id) return false
  if (response.status !== 'SELECTED' || !isFormalSelectOne.value) return true
  return canViewSelectedResponse(
    true,
    store.currentUser?.id,
    demand.value?.publisherId ?? '',
    store.currentUser?.role,
    response.authorId
  )
}))

// T024: SELECT_MANY 剩余名额前置校验 —— 勾选时按“剩余名额 = 目标人数 - 已选人数”实时判断，超员自动回退并提示
// 与后端校验口径一致（后端限制“已选 + 本次新增 ≤ 目标”），避免前端看似可选、提交后才被拒
watch(selectedResponseIds, (next) => {
  const target = targetCount.value
  if (target === null || target <= 0) return
  const remaining = Math.max(0, target - (selectedCount.value ?? 0))
  if (next.length > remaining) {
    void useAlert(remaining === 0 ? '名额已满' : '超出剩余名额', remaining === 0
      ? `该组队需求已选满 ${target} 人，无法继续选择。`
      : `还可选择 ${remaining} 人，已超额，已自动保留前 ${remaining} 个。`)
    selectedResponseIds.value = next.slice(0, remaining)
  }
})
const responseListTitle = computed(() => {
  switch (interactionMode.value) {
    case 'HELP': return '回答'
    case 'SELECT_MANY': return '报名'
    default: return '响应'
  }
})
const demandContentLabel = computed(() => {
  if (demand.value?.category === 'SECOND_HAND') return '物品说明'
  if (demand.value?.category === 'STUDY_TUTORING') return '学习目标与辅导要求'
  if (detailMode.value === 'help') return '问题描述'
  if (detailMode.value === 'select-many') return '活动与组队目标'
  if (detailMode.value === 'direct-accept') return '任务说明'
  return '需求说明'
})
const interactionGuidance = computed(() => {
  if (demand.value?.category === 'SECOND_HAND') return '查看物品描述后提交响应。'
  if (demand.value?.category === 'STUDY_TUTORING') return '说明辅导内容与时间。'
  if (detailMode.value === 'select-many') return '发布者确认成员后结算。'
  if (detailMode.value === 'help') return '在此需求内回答与采纳。'
  if (detailMode.value === 'direct-accept') return '接单后进入订单履约。'
  return ''
})

// 当前用户在该 Demand 上是否已有 active Response（PENDING 或 SELECTED）
// SELECT_ONE/SELECT_MANY 仍保持一人一个 active Response；HELP 允许同一用户多条回答
const myRejectedResponse = computed(() => {
  const userId = store.currentUser?.id
  if (!userId) return null
  return visibleResponses.value.find((response) => response.authorId === userId && response.status === 'REJECTED') ?? null
})

// 已提交状态的友好提示文案
const myResponseStatusHint = computed(() => {
  const resp = myActiveResponse.value
  if (!resp) return ''
  switch (interactionMode.value) {
    case 'HELP':
      return resp.status === 'SELECTED'
        ? '你的回答已被采纳，如需修改请先撤回原回答。'
        : '你已经提交过回答了，如需修改请先撤回原回答。'
    case 'SELECT_MANY':
      return resp.status === 'SELECTED'
        ? '你已被选中，如需修改请先撤回当前报名。'
        : '你已经报名过该组队需求，如需修改请先撤回当前报名。'
    default:
      return resp.status === 'SELECTED'
        ? '你的留言已被选中，如需修改请先撤回原留言。'
        : '你已经留言过了，如需修改请先撤回原留言。'
  }
})

// 当前用户的 Response 是否允许撤回（仅 PENDING 状态可撤回，SELECTED 已进入履约不能撤回）
const canWithdrawMyResponse = computed(() => myActiveResponse.value?.status === 'PENDING')

// 提交过程中禁用按钮，防止连续点击产生重复请求
const responseSubmitting = ref(false)

async function createResponse(): Promise<void> {
  if (!demand.value) return
  if (responseSubmitting.value) return
  if (!responseContent.value.trim()) {
    error.value = '请填写内容'
    return
  }
  message.value = ''
  error.value = ''
  responseSubmitting.value = true
  try {
    await store.createResponse(demand.value.id, responseContent.value)
    responseContent.value = ''
    message.value = '已提交'
  } catch (e) {
    error.value = handleError(e, '提交失败')
  } finally {
    responseSubmitting.value = false
  }
}

async function selectResponse(responseId: string): Promise<void> {
  if (!demand.value) return
  if (!await useConfirm('确认选择', '确认选择该响应？将生成订单进入履约。')) return
  message.value = ''
  error.value = ''
  try {
    await store.selectResponse(demand.value.id, responseId)
    message.value = '已选择，订单已生成'
  } catch (e) {
    error.value = handleError(e, '选择失败')
  }
}

async function selectResponses(): Promise<void> {
  if (!demand.value) return
  if (selectedResponseIds.value.length === 0) {
    error.value = '请至少选择一个'
    return
  }
  // T024: 提交前再校验一次剩余名额（watch 已实时拦截，此处兜底，与后端口径一致）
  if (targetCount.value !== null && targetCount.value > 0) {
    const remaining = Math.max(0, targetCount.value - (selectedCount.value ?? 0))
    if (remaining === 0 || selectedResponseIds.value.length > remaining) {
      await useAlert(remaining === 0 ? '名额已满' : '超出剩余名额', remaining === 0
        ? `该组队需求已选满 ${targetCount.value} 人。`
        : `还可选择 ${remaining} 人，请取消多余选择后再提交。`)
      return
    }
  }
  message.value = ''
  error.value = ''
  try {
    await store.selectResponses(demand.value.id, selectedResponseIds.value)
    selectedResponseIds.value = []
    message.value = '已确认选择'
  } catch (e) {
    error.value = handleError(e, '选择失败')
  }
}

async function acceptAnswer(responseId: string): Promise<void> {
  if (!demand.value) return
  if (!await useConfirm('确认采纳', '确认采纳该回答？')) return
  message.value = ''
  error.value = ''
  try {
    await store.acceptAnswer(demand.value.id, responseId)
    message.value = '已采纳该回答'
  } catch (e) {
    error.value = handleError(e, '采纳失败')
  }
}

function startResponseReview(responseId: string) {
  reviewingResponseId.value = responseId
  responseReviewRating.value = '5'
  responseReviewComment.value = ''
}

function hasReviewedResponse(responseId: string): boolean {
  return store.reviews.some((rev) => rev.responseId === responseId && rev.reviewerId === store.currentUser?.id)
}

function cancelResponseReview() {
  reviewingResponseId.value = null
}

async function submitResponseReview(responseId: string): Promise<void> {
  if (!demand.value) return
  message.value = ''
  error.value = ''
  try {
    // store 内已将返回的 Review 写入 store.reviews，hasReviewedResponse 立即生效，无需再次请求后端
    await store.submitReviewForResponse(responseId, Number(responseReviewRating.value), responseReviewComment.value)
    reviewingResponseId.value = null
    message.value = '评价已提交'
  } catch (e) {
    error.value = handleError(e, '评价失败')
  }
}

async function withdrawResponse(responseId: string): Promise<void> {
  if (responseSubmitting.value) return
  if (!await useConfirm('撤回', '确认撤回该留言？', { danger: true })) return
  message.value = ''
  error.value = ''
  responseSubmitting.value = true
  try {
    await store.withdrawResponse(demand.value?.id ?? '', responseId)
    message.value = '已撤回'
  } catch (e) {
    error.value = handleError(e, '撤回失败')
  } finally {
    responseSubmitting.value = false
  }
}

onMounted(() => { void refreshDemand() })
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

  <ErrorState v-else-if="loadState === 'error'" title="需求详情加载失败" :description="loadFailure" :retry="true" retry-label="重试" @retry="refreshDemand" />
  <ErrorState v-else-if="loadState === 'forbidden'" title="无权查看此需求" description="该需求当前不可向你的账号展示。" />
  <div v-else-if="loadState === 'not-found'" class="empty-state">
    <strong>需求不存在</strong>
    <p>该需求可能已被删除，或链接中的编号无效。</p>
  </div>

  <div v-else-if="demand && loadState === 'ready'" class="page-grid demand-detail-layout">
    <section class="panel">
      <div class="page-head" style="margin-bottom: 12px;">
        <button type="button" class="button primary" @click="router.back()">← 返回</button>
        <button type="button" class="button primary" :disabled="refreshing" @click="refreshDemand">
          {{ refreshing ? '刷新中...' : '↻ 刷新' }}
        </button>
      </div>

      <div class="detail-title-row" style="margin-top: 4px;">
        <div>
          <div class="detail-kicker">
            <span class="chip" :class="statusToneClass(demand.status)">{{ formatDemandStatus(demand.status) }}</span>
            <span class="chip">{{ formatDemandCategory(demand.category) }}</span>
            <span class="chip">{{ formatCampusZone(demand.campusZone) }}</span>
          </div>
          <h1 class="page-title">{{ demand.title }}</h1>
        </div>
        <strong v-if="detailMode !== 'help' && demand.category !== 'SECOND_HAND' && demand.reward > 0" class="detail-reward">
          {{ demand.reward }} <span>校邻币</span>
        </strong>
      </div>

      <!-- 发布者信息 -->
      <RouterLink v-if="!demand.anonymous && demand.publisherId" :to="`/users/${demand.publisherId}`" class="avatar-row avatar-row--link">
        <img :src="demand.publisher?.avatarUrl ?? demand.publisherAvatar" :alt="demand.publisher?.nickname ?? demand.publisherName" class="avatar large" />
        <div>
          <strong>{{ demand.publisher?.nickname ?? demand.publisherName }}</strong>
          <p class="subtle">信用分：{{ demand.publisher ? formatScore(demand.publisher.creditScore) : '未知' }}</p>
          <p class="meta">发布于 {{ formatDateTime(demand.createdAt) }}</p>
        </div>
      </RouterLink>
      <div v-else class="avatar-row">
        <div class="avatar large anonymous-avatar" aria-hidden="true">匿</div>
        <div>
          <strong>{{ demand.anonymousCode ?? '匿名发布' }}</strong>
          <p class="meta">发布于 {{ formatDateTime(demand.createdAt) }}</p>
        </div>
      </div>

      <div v-if="detailMode === 'select-many' && selectedCount !== null && targetCount !== null" class="participant-progress">
        <strong>{{ selectedCount }}<span>/{{ targetCount }}</span></strong>
        <span>已选成员</span>
        <span class="meta">还差 {{ remainingSlots }} 人达到目标</span>
      </div>
      <div v-else-if="detailMode === 'select-many'" class="participant-progress is-unavailable">当前人数信息暂不可用，请刷新后再查看。</div>

      <!-- 需求详情（突出显示，置于时间信息之前） -->
      <div class="demand-mode-note">
        <span class="mode-note-mark" aria-hidden="true">{{ detailMode === 'help' ? '?' : detailMode === 'select-many' ? '＋' : detailMode === 'direct-accept' ? '↗' : '◎' }}</span>
        <p>{{ interactionGuidance }}</p>
      </div>

      <div v-if="demand.images && demand.images.length > 0" class="demand-images demand-images-featured">
        <p class="eyebrow">需求实拍 · {{ demand.images.length }} 张</p>
        <div class="image-grid">
          <button
            v-for="(imgUrl, imgIdx) in demand.images"
            :key="imgUrl"
            type="button"
            class="image-item"
            :class="{ 'image-item--cover': imgIdx === 0 }"
            :style="imgIdx === 0 ? { aspectRatio: coverRatio } : undefined"
            :aria-label="`查看需求图片 ${imgIdx + 1}`"
            @click="openImageViewer(imgIdx)"
          >
            <img :class="{ 'is-failed': imageLoadFailures[imgUrl] }" :src="imgUrl" :alt="`需求图片 ${imgIdx + 1}`" loading="lazy" class="demand-img" @error="imageLoadFailures[imgUrl] = true" @load="imgIdx === 0 && onCoverImageLoad($event)" />
            <span v-if="imageLoadFailures[imgUrl]" class="image-failure">图片暂时无法显示</span>
          </button>
        </div>
      </div>


      <div v-if="demand.description" class="demand-description-highlight">
        <p class="eyebrow demand-detail-label">{{ demandContentLabel }}</p>
        <div class="description-content">{{ demand.description }}</div>
      </div>

      <!-- 时间与地点：图标 + 标签 + 内容，同一网格基线对齐 -->
      <div class="detail-facts">
        <div class="detail-fact">
          <span class="detail-fact-icon" aria-hidden="true"><Clock :size="16" :stroke-width="1.9" /></span>
          <div>
            <p class="detail-fact-label">时间</p>
            <p class="detail-fact-value">{{ formatDateTime(demand.startTime || '') }} — {{ formatDateTime(demand.endTime || '') }}</p>
          </div>
        </div>
        <div class="detail-fact">
          <span class="detail-fact-icon" aria-hidden="true"><MapPin :size="16" :stroke-width="1.9" /></span>
          <div>
            <p class="detail-fact-label">地点</p>
            <p class="detail-fact-value">{{ demand.location || '未填写' }}</p>
          </div>
        </div>
        <div v-if="demand.contactInfo" class="detail-fact">
          <span class="detail-fact-icon" aria-hidden="true"><Phone :size="16" :stroke-width="1.9" /></span>
          <div>
            <p class="detail-fact-label">联系方式</p>
            <p class="detail-fact-value" style="white-space: pre-wrap;">{{ demand.contactInfo }}</p>
          </div>
        </div>
      </div>

      <!-- 标签 -->
      <div class="tag-row detail-block">
        <span class="badge is-neutral">{{ demand.anonymous ? '匿名发布' : '实名发布' }}</span>
        <span v-for="tag in demand.tags" :key="tag" class="badge is-neutral">{{ tag }}</span>
      </div>

      <div v-if="demand.status === 'CANCELLED' && demand.reviewReason" class="list-card detail-block">
        <strong>审核原因</strong>
        <p style="margin-top: 8px; color: var(--danger);">{{ demand.reviewReason }}</p>
      </div>

      <div v-if="demand.publisherId === store.currentUser?.id && (demand.status === 'REVIEWING' || demand.status === 'PENDING')" class="list-card detail-block">
        <strong>管理你的需求</strong>
        <p class="meta">{{ demand.status === 'REVIEWING' ? '该需求仍在审核中。' : '该需求已开放接单。' }}</p>
        <div class="card-actions">
          <button type="button" class="button danger" @click="withdrawDemand">撤回需求</button>
        </div>
      </div>

      <div v-if="detailMode === 'direct-accept'" class="demand-action-card">
        <div>
          <p class="eyebrow">互助方式</p>
          <strong>{{ demand.reward > 0 ? `完成后可获得 ${demand.reward} 校邻币` : '直接接单，按需求说明完成任务' }}</strong>
          <p v-if="relatedOrder && hasOrderAccess" class="meta">正式订单已建立，后续进度与凭证请在订单中处理。</p>
          <p v-else-if="relatedOrder" class="meta">该需求已进入正式订单流程。</p>
          <p v-else-if="!canAccept && acceptDisabledReason" class="meta">{{ acceptDisabledReason }}</p>
          <p v-else-if="acceptStatusHint" class="meta">{{ acceptStatusHint }}</p>
        </div>
        <div class="card-actions">
          <label v-if="canAccept" class="accept-note-field">
            <span>给发布者留一句话（选填）</span>
            <textarea id="accept-note" v-model="note" rows="2" placeholder="说明你将如何完成这项任务"></textarea>
          </label>
          <button v-if="canAccept" type="button" class="button primary" :disabled="submitting" @click="acceptCurrentDemand">
            {{ submitting ? '处理中…' : '立即接单' }}
          </button>
          <!-- 未登录游客：提供可点击的登录引导，避免只有静态文案无法跳转 -->
          <button
            v-else-if="!store.currentUser"
            type="button"
            class="button primary"
            @click="router.push({ name: 'Auth', query: { redirect: route.fullPath } })"
          >
            登录后接单
          </button>
          <button v-if="hasOrderAccess && relatedOrder" type="button" class="button primary" @click="router.push({ name: 'OrderDetail', params: { id: relatedOrder.id } })">
            查看正式订单
          </button>
        </div>
      </div>

      <!-- Response 交互区域（非 DIRECT_ACCEPT 模式：SELECT_ONE / SELECT_MANY / HELP） -->
      <div v-if="interactionMode !== 'DIRECT_ACCEPT'" class="list-card detail-block">
        <div class="status-row detail-block-head">
          <strong>{{ responseListTitle }}</strong>
          <span class="chip">{{ formatInteractionMode(interactionMode) }}</span>
        </div>

        <!-- 创建留言/报名/回答 -->
        <div v-if="canRespond && (interactionMode === 'HELP' || !myActiveResponse)" class="field" style="margin-top: 4px;">
          <textarea v-model="responseContent" :placeholder="`请填写${responseListTitle}内容`" rows="3"></textarea>
          <div class="card-actions">
            <button type="button" class="button primary" data-testid="submit-response" :disabled="!responseContent.trim() || responseSubmitting" @click="createResponse">
              {{ responseSubmitting ? '提交中...' : '提交' }}
            </button>
          </div>
        </div>

        <!-- 未登录游客：提供可点击的登录引导，避免只有静态文案无法跳转 -->
        <div v-else-if="!store.currentUser" class="card-actions" style="margin-top: 4px;">
          <button
            type="button"
            class="button primary"
            @click="router.push({ name: 'Auth', query: { redirect: route.fullPath } })"
          >
            登录后参与{{ responseListTitle }}
          </button>
        </div>

        <!-- 当前用户已存在 active Response：直接显示状态，避免重复提交触发后端 BUSINESS_CONFLICT -->
        <div v-if="myActiveResponse" class="list-card response-own-card">
          <strong>{{ responseListTitle === '回答' ? '已提交回答' : (responseListTitle === '报名' ? '已报名' : '已留言') }}</strong>
          <p class="meta" style="margin-top: 4px;">{{ myResponseStatusHint }}</p>
          <p class="response-own-content">{{ myActiveResponse.content }}</p>
          <div class="meta" style="margin-top: 4px;">当前状态：{{ formatResponseStatus(myActiveResponse.status) }} · {{ formatDateTime(myActiveResponse.createdAt) }}</div>
          <div class="card-actions detail-block-inner">
            <button
              v-if="canWithdrawMyResponse"
              type="button"
              class="button secondary"
              :disabled="responseSubmitting"
              @click="withdrawResponse(myActiveResponse.id)"
            >{{ responseSubmitting ? '撤回中...' : '撤回' }}</button>
            <span v-else class="chip is-warning">已进入履约，无法撤回</span>
          </div>
        </div>

        <!-- 列表 -->
        <div v-if="visibleResponses.length" class="review-list detail-block-inner">
          <div v-for="r in visibleResponses" :key="r.id" class="timeline-item">
            <div style="flex: 1;">
              <strong>{{ r.authorName }}</strong>
              <div class="meta" style="margin-top: 4px; white-space: pre-wrap;">{{ r.content }}</div>
              <div class="meta" style="margin-top: 4px;">{{ formatResponseStatus(r.status) }} · {{ formatDateTime(r.createdAt) }}</div>
            </div>
            <div class="card-actions" style="gap: 8px;">
              <button
                v-if="r.authorId === store.currentUser?.id && r.status === 'PENDING'"
                type="button"
                class="button secondary"
                :disabled="responseSubmitting"
                @click="withdrawResponse(r.id)"
              >撤回</button>
              <button
                v-if="canSelectResponse(demand, store.currentUser?.id, store.currentUser?.role, r.status) && detailMode === 'select-one'"
                type="button"
                class="button primary"
                @click="selectResponse(r.id)"
              >选择</button>
              <label
                v-if="isPublisher && store.currentUser?.role !== 'ADMIN' && demand.status === 'PENDING' && detailMode === 'select-many' && r.status === 'PENDING'"
                style="display: inline-flex; align-items: center; gap: 6px;"
              >
                <input type="checkbox" :value="r.id" v-model="selectedResponseIds" />
                <span>选择</span>
              </label>
              <button
                v-if="isPublisher && store.currentUser?.role !== 'ADMIN' && demand.status === 'PENDING' && detailMode === 'help' && r.status === 'PENDING'"
                type="button"
                class="button primary"
                @click="acceptAnswer(r.id)"
              >采纳</button>
            </div>
            <!-- SELECT_MANY 评价入口：Demand COMPLETED 后，publisher 与被选中成员可双向评价 -->
            <div
              v-if="interactionMode === 'SELECT_MANY' && demand.status === 'COMPLETED' && r.status === 'SELECTED'
                && store.currentUser && (isPublisher || r.authorId === store.currentUser.id)"
              class="detail-block-inner"
            >
              <button
                v-if="reviewingResponseId !== r.id && !hasReviewedResponse(r.id)"
                type="button"
                class="button secondary"
                @click="startResponseReview(r.id)"
              >评价</button>
              <span v-else-if="hasReviewedResponse(r.id)" class="chip is-success">已评价</span>
              <div v-else class="field">
                <label>评分</label>
                <select v-model="responseReviewRating">
                  <option value="5">5 星</option>
                  <option value="4">4 星</option>
                  <option value="3">3 星</option>
                  <option value="2">2 星</option>
                  <option value="1">1 星</option>
                </select>
                <label class="detail-block-inner">评价内容</label>
                <textarea v-model="responseReviewComment" placeholder="写下你的评价" rows="2"></textarea>
                <div class="card-actions detail-block-inner">
                  <button type="button" class="button primary" :disabled="!responseReviewComment.trim()" @click="submitResponseReview(r.id)">提交评价</button>
                  <button type="button" class="button secondary" @click="cancelResponseReview">取消</button>
                </div>
              </div>
            </div>
          </div>
        </div>
        <p v-else class="meta detail-block-inner">暂无{{ responseListTitle }}</p>

        <!-- SELECT_MANY 底部统计与确认 -->
        <div
          v-if="isPublisher && store.currentUser?.role !== 'ADMIN' && demand.status === 'PENDING' && detailMode === 'select-many'"
          class="card-actions detail-block-inner card-actions--between"
        >
          <span class="chip" :class="targetCount !== null && selectedCount !== null && selectedCount >= targetCount ? 'is-success' : 'is-warning'">
            {{ selectedCount !== null && targetCount !== null ? (selectedCount >= targetCount ? `已满 ${targetCount} 人` : `已选择 ${selectedCount} / ${targetCount} 人`) : '人数信息暂不可用' }}
          </span>
          <span v-if="remainingSlots !== null && remainingSlots > 0" class="meta">还可选择 {{ remainingSlots }} 人</span>
          <button
            type="button"
            class="button primary"
            :disabled="selectedResponseIds.length === 0 || (remainingSlots !== null && selectedResponseIds.length > remainingSlots)"
            @click="selectResponses"
          >确认选择</button>
        </div>
      </div>

      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
    </section>

    <section class="panel demand-sidebar">
      <div class="sidebar-head">
        <p class="eyebrow">下一步</p>
        <h2 class="section-title">需求处理</h2>
      </div>

      <div v-if="hasOrderAccess && relatedOrder" class="order-entry-card">
        <span class="chip" :class="statusToneClass(relatedOrder.status)">{{ formatOrderStatus(relatedOrder.status) }}</span>
        <h3>正式订单已建立</h3>
        <p class="meta">订单进度、完成凭证、双方确认、仲裁和评价都在订单详情中处理。</p>
        <button type="button" class="button primary" @click="router.push({ name: 'OrderDetail', params: { id: relatedOrder.id } })">前往订单详情</button>
      </div>
      <div v-else-if="detailMode === 'select-one' && myActiveResponse?.status === 'SELECTED'" class="order-entry-card">
        <h3>你已被选中</h3>
        <p class="meta">正式订单已建立。订单入口仅在服务端确认你的参与权限后显示。</p>
      </div>
      <div v-else-if="detailMode === 'select-one' && myRejectedResponse" class="order-entry-card">
        <h3>本次未被选中</h3>
        <p class="meta">你只能查看自己的处理结果，其他参与者的订单内容不会向你展示。</p>
      </div>
      <div v-else-if="detailMode === 'select-many'" class="order-entry-card">
        <strong>{{ selectedCount ?? '—' }} / {{ targetCount ?? '—' }} 人已选</strong>
        <p class="meta">达到目标人数后自动完成并结算。</p>
      </div>
      <div v-else-if="detailMode === 'help'" class="order-entry-card">
        <strong>{{ demand.status === 'COMPLETED' ? '问题已解决' : '在需求中交流' }}</strong>
        <p class="meta">采纳回答后自动结算。</p>
      </div>
      <div v-else class="order-entry-card">
        <strong>{{ demand.status === 'PENDING' ? '需求开放中' : formatDemandStatus(demand.status) }}</strong>
        <p class="meta">接单后进入正式订单履约。</p>
      </div>
    </section>
  </div>

  <!-- 全屏图片预览 -->
  <ImageViewer
    v-if="showImageViewer && viewerImages.length"
    :images="viewerImages"
    :initial-index="viewerInitialIndex"
    :failure-messages="viewerFailures"
    @close="closeImageViewer"
  />

  <!-- 仲裁对话框 -->
  </div>
</template>

<style scoped>
/* ========== 双栏布局 ========== */
.demand-detail-layout {
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 22px;
  align-items: start;
}

.detail-title-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.detail-title-row .page-title {
  max-width: 820px;
  margin: 12px 0 0;
  font-size: clamp(2rem, 3vw, 2.65rem);
  line-height: 1.16;
  letter-spacing: -0.045em;
}

.detail-kicker {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.detail-reward {
  flex: 0 0 auto;
  color: var(--accent-strong);
  font-size: 1.45rem;
  letter-spacing: -0.04em;
  white-space: nowrap;
}

.detail-reward span {
  color: var(--muted);
  font-size: 0.58em;
  letter-spacing: 0;
}

.demand-mode-note,
.order-entry-card {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
  padding: 14px 16px;
  border: 1px solid var(--panel-border);
  border-radius: 14px;
  background: #f8faf8;
}

/* 详情页纵向堆叠块统一 12px 节奏，替代散落的内联 margin，保证各区块间距一致 */
.detail-block {
  margin-top: 12px;
}

.detail-block-head {
  margin-bottom: 8px;
}

.detail-block-inner {
  margin-top: 8px;
}

.demand-action-card {
  margin-top: 12px;
}

.response-own-card {
  margin-top: 4px;
  background: var(--accent-soft);
  border-color: rgba(31, 95, 83, 0.2);
}

.card-actions--between {
  justify-content: space-between;
}

.demand-mode-note p,
.order-entry-card p {
  margin: 0;
  line-height: 1.55;
}

.mode-note-mark {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 10px;
  background: var(--accent-soft);
  color: var(--accent-strong);
  font-size: 1.1rem;
  font-weight: 700;
}

.participant-progress {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: baseline;
  column-gap: 9px;
  margin-top: 16px;
  padding: 18px 20px;
  border-radius: 15px;
  background: #eef4ef;
}

.participant-progress strong {
  color: var(--accent-strong);
  font-size: 1.9rem;
  font-variant-numeric: tabular-nums;
}

.participant-progress strong span {
  color: var(--muted);
  font-size: 0.65em;
}

.participant-progress > span:last-child {
  grid-column: 2;
}

.participant-progress.is-unavailable {
  display: block;
  color: var(--muted);
  font-size: 0.9rem;
}

.anonymous-avatar {
  display: grid;
  place-items: center;
  color: var(--muted);
  background: #eef1ee;
  font-weight: 700;
}

.accept-note-field {
  display: grid;
  min-width: min(360px, 100%);
  gap: 6px;
  color: var(--muted);
  font-size: 0.82rem;
}

.accept-note-field textarea {
  min-height: 66px;
  resize: vertical;
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

.demand-image-placeholder {
  display: grid;
  min-height: 160px;
  margin-top: 16px;
  place-items: center;
  border: 1px dashed rgba(43, 72, 62, 0.22);
  border-radius: 14px;
  background: #f8faf8;
  color: var(--muted);
  font-size: 0.9rem;
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

.demand-images-featured .image-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

/* 第一张作为封面：跨两列、更矮的比例，让宽图与竖图都保持原比例完整显示 */
.demand-images-featured .image-item--cover {
  grid-column: span 2;
  aspect-ratio: 16 / 10;
}

.demand-images-featured .image-item {
  aspect-ratio: 4 / 3;
  border: 0;
  border-radius: 12px;
  background: #e9ede9;
}

.demand-images-featured .image-item:focus-visible {
  outline: 3px solid var(--accent);
  outline-offset: 3px;
}

/* ========== 时间 / 地点 / 联系方式 ========== */
.detail-facts {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.detail-fact {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  gap: 12px;
  padding: 13px 16px;
  border: 1px solid var(--panel-border);
  border-radius: 14px;
  background: #f8faf8;
}

.detail-fact-icon {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 10px;
  background: var(--accent-soft);
  color: var(--accent);
}

.detail-fact-label {
  margin: 0;
  color: var(--muted);
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.detail-fact-value {
  margin: 3px 0 0;
  color: var(--text-strong);
  font-size: 0.92rem;
  line-height: 1.55;
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
  margin-top: 8px;
}

.image-item {
  position: relative;
  display: block;
  width: 100%;
  padding: 0;
  background: #eef1ee;
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
  object-fit: contain;
}

.demand-img.is-failed {
  display: none;
}

.response-own-content {
  margin: 9px 0 0;
  color: var(--text-strong);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.image-failure {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--muted);
  font-size: 0.85rem;
}

.order-entry-card {
  align-items: flex-start;
  flex-direction: column;
}

.order-entry-card h3 {
  margin: 4px 0 0;
  color: var(--text-strong);
}

.order-entry-card .button {
  margin-top: 5px;
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
