import { formatDateTime } from '@/utils/format'
import type { DemandRecord } from '@/types/campushub'

export interface HomeDemandCardView {
  isPublisher: boolean
  publisherName: string
  publisherAvatar: string | null
  statusLabel: string
  focusLabel: string
  rewardLabel: string | null
  location: string | null
  timeLabel: string | null
  participantProgress: string | null
  responseSummary: string | null
  personalResponseStatus: string | null
}

function isSameDemandDay(start: string | null | undefined, end: string | null | undefined): boolean {
  if (!start || !end) return false
  const startDate = new Date(start)
  const endDate = new Date(end)
  if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) return false
  return startDate.getFullYear() === endDate.getFullYear()
    && startDate.getMonth() === endDate.getMonth()
    && startDate.getDate() === endDate.getDate()
}

export function isVisibleOnDemandHome(demand: DemandRecord): boolean {
  return demand.status === 'PENDING' || demand.status === 'IN_PROGRESS' || demand.status === 'COMPLETED'
}

export function getHomeDemandCard(demand: DemandRecord, currentUserId: string): HomeDemandCardView {
  const anonymous = demand.anonymous || Boolean(demand.anonymousCode)
  const isPublisher = Boolean(currentUserId) && demand.publisherId === currentUserId
  const taskCategory = demand.category === 'EXPRESS' || demand.category === 'ERRAND'
  const rewardCategory = taskCategory || (demand.category === 'OTHER' && demand.interactionMode === 'DIRECT_ACCEPT')
  const hasValidParticipantCounts = Number.isInteger(demand.targetParticipantCount)
    && Number.isInteger(demand.selectedParticipantCount)
    && (demand.targetParticipantCount ?? 0) > 0
    && (demand.selectedParticipantCount ?? -1) >= 0
  const participantProgress = demand.interactionMode === 'SELECT_MANY' && hasValidParticipantCounts
    ? `已选 ${demand.selectedParticipantCount} / ${demand.targetParticipantCount} 人`
    : null

  let statusLabel = demand.status === 'PENDING'
    ? '开放中'
    : demand.status === 'IN_PROGRESS'
      ? '进行中'
      : '已完成'

  if (demand.status === 'PENDING' && taskCategory && demand.canAccept === true) {
    statusLabel = '可接单'
  }

  if (demand.status === 'PENDING' && demand.interactionMode === 'SELECT_MANY'
      && demand.targetParticipantCount != null && demand.selectedParticipantCount != null
      && hasValidParticipantCounts && demand.selectedParticipantCount >= demand.targetParticipantCount) {
    statusLabel = '名额已满'
  }

  const startTime = demand.startTime ? formatDateTime(demand.startTime) : ''
  const endTime = demand.endTime ? formatDateTime(demand.endTime) : ''
  // 首页卡片信息位只放得下一行时间：同日（按完整年月日判断，避免跨年同月同日误判）压成起止时刻，跨天退化为“开始时间 起”，完整时段见详情页
  const timeLabel = startTime && endTime
    ? isSameDemandDay(demand.startTime, demand.endTime)
      ? `${startTime}–${endTime.slice(6)}`
      : `${startTime} 起`
    : startTime || endTime || null

  const focusLabel = taskCategory
    ? '任务要求'
    : demand.category === 'SECOND_HAND'
      ? '物品信息'
      : demand.category === 'STUDY_TUTORING'
        ? '学习目标'
        : demand.category === 'TEAM_UP'
          ? '活动信息'
          : demand.category === 'HELP'
            ? '问题描述'
            : '需求说明'

  return {
    isPublisher,
    publisherName: anonymous ? (demand.anonymousCode || '匿名同学') : (demand.publisherName || '校园同学'),
    publisherAvatar: anonymous ? null : demand.publisherAvatar || null,
    statusLabel,
    focusLabel,
    rewardLabel: rewardCategory && demand.reward > 0 ? `${demand.reward} 校邻币` : null,
    location: demand.location?.trim() || null,
    timeLabel,
    participantProgress,
    // 列表接口没有响应数和当前用户 Response 关系，不能在首页补猜。
    responseSummary: null,
    personalResponseStatus: null
  }
}
