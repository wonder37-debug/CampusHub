<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Badge } from '@/components/ui/badge'
import type { DemandRecord, RecommendationRecord } from '@/types/campushub'
import { formatCampusZone, formatDemandCategory, formatRelativeTime, statusToneClass, truncateText } from '@/utils/format'
import { getHomeDemandCard } from '@/lib/home-demand-card'

const props = defineProps<{
  demand: DemandRecord
  currentUserId: string
  recommendation?: RecommendationRecord
}>()

const imageFailed = ref(false)
const imageLoaded = ref(false)
const view = computed(() => getHomeDemandCard(props.demand, props.currentUserId))
const imageUrl = computed(() => props.demand.images?.find((image) => image.trim()) ?? '')

watch(() => props.demand.id, () => {
  imageFailed.value = false
  imageLoaded.value = false
})

function markImageLoaded(): void {
  imageLoaded.value = true
}

function markImageFailed(): void {
  imageFailed.value = true
}
</script>

<template>
  <article class="home-card">
    <RouterLink class="home-card__link" :to="`/demands/${demand.id}`" :aria-label="`查看需求：${demand.title}`">
      <div class="home-card__media" :class="{ 'home-card__media--text': !imageUrl || imageFailed }">
        <img
          v-if="imageUrl && !imageFailed"
          :src="imageUrl"
          :alt="`需求图片：${demand.title}`"
          loading="lazy"
          decoding="async"
          :class="{ 'is-loaded': imageLoaded }"
          @load="markImageLoaded"
          @error="markImageFailed"
        />
        <div v-else class="home-card__media-text">
          <span class="home-card__media-eyebrow">{{ view.focusLabel }}</span>
          <p class="home-card__media-title">{{ demand.title }}</p>
          <p class="home-card__media-desc">{{ truncateText(demand.description || '暂未补充详细说明', 60) }}</p>
          <span v-if="demand.tags?.length" class="home-card__media-tags">{{ demand.tags.slice(0, 2).join(' · ') }}</span>
        </div>
        <span class="home-card__media-category">{{ formatDemandCategory(demand.category) }}</span>
      </div>

      <div class="home-card__content">
        <div class="home-card__status-row">
          <Badge variant="secondary" class="home-card__category">{{ formatDemandCategory(demand.category) }}</Badge>
          <span class="home-card__status" :class="`home-card__status--${statusToneClass(demand.status).replace('is-', '')}`">
            {{ view.statusLabel }}
          </span>
          <span v-if="view.isPublisher" class="home-card__owner">我发布</span>
          <span v-if="recommendation" class="home-card__recommend">为你推荐</span>
        </div>

        <h2 class="home-card__title">{{ demand.title }}</h2>
        <p class="home-card__description">
          <span class="home-card__focus">{{ view.focusLabel }}</span>
          {{ truncateText(demand.description || '暂未补充详细说明', 88) }}
        </p>

        <div class="home-card__highlights">
          <span v-if="view.rewardLabel" class="home-card__reward">{{ view.rewardLabel }}</span>
          <span v-if="view.participantProgress" class="home-card__progress">{{ view.participantProgress }}</span>
          <span v-if="view.location" class="home-card__meta">{{ view.location }}</span>
          <span v-if="view.timeLabel" class="home-card__meta">{{ view.timeLabel }}</span>
        </div>

        <div v-if="demand.tags?.length" class="home-card__tags" aria-label="需求标签">
          <span v-for="tag in demand.tags.slice(0, 2)" :key="tag">{{ tag }}</span>
          <span v-if="demand.tags.length > 2" class="home-card__extra-tags">+{{ demand.tags.length - 2 }}</span>
        </div>
      </div>
    </RouterLink>

    <div class="home-card__footer">
      <RouterLink
        v-if="demand.publisherId && !demand.anonymous && !demand.anonymousCode"
        :to="`/users/${demand.publisherId}`"
        class="home-card__publisher"
        :aria-label="`查看 ${view.publisherName} 的公开主页`"
      >
        <img v-if="view.publisherAvatar" :src="view.publisherAvatar" alt="" loading="lazy" />
        <span v-else class="home-card__avatar-placeholder" aria-hidden="true">{{ view.publisherName.slice(0, 1) }}</span>
        <span class="home-card__publisher-name">{{ view.publisherName }}</span>
      </RouterLink>
      <div v-else class="home-card__publisher" :class="{ 'home-card__publisher--anonymous': demand.anonymous || demand.anonymousCode }">
        <img v-if="view.publisherAvatar" :src="view.publisherAvatar" alt="" loading="lazy" />
        <span v-else class="home-card__avatar-placeholder" aria-hidden="true">{{ view.publisherName.slice(0, 1) }}</span>
        <span class="home-card__publisher-name">{{ view.publisherName }}</span>
      </div>
      <div class="home-card__published-meta">
        <span>{{ formatCampusZone(demand.campusZone) }}</span>
        <span>{{ formatRelativeTime(demand.createdAt) }}</span>
        </div>
    </div>
  </article>
</template>

<style scoped>
.home-card {
  display: flex;
  min-width: 0;
  height: 100%;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
  transition: border-color 160ms ease, transform 160ms ease, box-shadow 160ms ease;
}

.home-card:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--primary) 32%, var(--border));
  box-shadow: 0 12px 28px rgb(24 46 38 / 8%);
}

.home-card__link {
  display: flex;
  min-height: 0;
  flex: 1;
  color: inherit;
  flex-direction: column;
  text-decoration: none;
}

.home-card__link:focus-visible {
  outline: 3px solid var(--ring);
  outline-offset: -3px;
  border-radius: inherit;
}

.home-card__media {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  /* 图片按原比例 contain 居中，四周留白用中性浅灰承载，避免拉伸或裁切 */
  background: #e9ede9;
}

.home-card__media > img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  opacity: 0;
  transition: transform 220ms ease, opacity 220ms ease;
}

.home-card__media > img.is-loaded {
  opacity: 1;
}

.home-card:hover .home-card__media > img {
  transform: scale(1.025);
}

.home-card__media--text {
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(120% 100% at 12% 0%, rgb(255 255 255 / 85%) 0%, transparent 58%),
    linear-gradient(140deg, #eef3ee 0%, #e2ebe4 100%);
}

.home-card__media-text {
  display: flex;
  width: 100%;
  height: 100%;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 7px;
  padding: 20px 17px;
}

/* 无图占位即封面：分类小标 + 标题艺术字 + 摘要，替代灰块 */
.home-card__media-eyebrow {
  color: var(--accent);
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.home-card__media-title {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--accent-strong);
  font-family: var(--heading);
  font-size: 1.18rem;
  font-weight: 730;
  letter-spacing: -0.02em;
  line-height: 1.32;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.home-card__media-desc {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: #40514a;
  font-size: 0.78rem;
  font-weight: 500;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.home-card__media-tags {
  color: var(--muted-foreground);
  font-size: 0.7rem;
  font-weight: 500;
}

.home-card__media-category {
  position: absolute;
  right: 12px;
  bottom: 12px;
  padding: 5px 9px;
  border: 1px solid rgb(255 255 255 / 70%);
  border-radius: 999px;
  background: rgb(255 255 255 / 92%);
  color: var(--foreground);
  font-size: 0.72rem;
  font-weight: 650;
}

.home-card__media--text .home-card__media-category {
  border-color: rgba(43, 107, 93, 0.18);
  background: rgba(255, 255, 255, 0.88);
}

.home-card__content {
  display: flex;
  /* 固定内容区高度：所有卡片等高，不随描述行数/标签有无波动 */
  height: 246px;
  flex: none;
  flex-direction: column;
  overflow: hidden;
  padding: 15px 17px 14px;
}

.home-card__status-row {
  display: flex;
  min-height: 22px;
  align-items: center;
  gap: 7px;
}

.home-card__category {
  display: none;
}

.home-card__status,
.home-card__owner,
.home-card__recommend {
  color: var(--muted-foreground);
  font-size: 0.73rem;
  font-weight: 600;
}

.home-card__status--warning { color: #8b5d14; }
.home-card__status--success { color: #246c50; }
.home-card__status--danger { color: #9e4d42; }
.home-card__owner { margin-left: auto; color: var(--primary); }
.home-card__recommend { margin-left: auto; color: #88631f; }

.home-card__title {
  display: -webkit-box;
  min-height: 2.9rem;
  margin: 9px 0 6px;
  overflow: hidden;
  color: var(--foreground);
  font-size: 1.02rem;
  font-weight: 680;
  line-height: 1.42;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.home-card__description {
  display: -webkit-box;
  min-height: 3.7rem;
  margin: 0;
  overflow: hidden;
  color: var(--muted-foreground);
  font-size: 0.83rem;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.home-card__focus {
  margin-right: 5px;
  color: var(--foreground);
  font-weight: 620;
}

.home-card__highlights {
  display: flex;
  min-height: 29px;
  flex-wrap: nowrap;
  align-items: center;
  gap: 6px 13px;
  margin-top: 10px;
  overflow: hidden;
  white-space: nowrap;
}

.home-card__reward,
.home-card__progress {
  color: #8a621e;
  font-size: 0.8rem;
  font-weight: 680;
}

.home-card__meta {
  min-width: 0;
  overflow: hidden;
  color: var(--muted-foreground);
  font-size: 0.75rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-card__tags {
  display: flex;
  min-height: 25px;
  align-items: center;
  gap: 6px;
  overflow: hidden;
}

.home-card__tags > span {
  max-width: 112px;
  overflow: hidden;
  padding: 3px 7px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent-strong);
  font-size: 0.68rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-card__tags > .home-card__extra-tags {
  flex: none;
  background: transparent;
}

.home-card__footer {
  display: flex;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding: 11px 17px 14px;
  border-top: 1px solid var(--border);
}

.home-card__publisher {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.home-card__publisher img,
.home-card__avatar-placeholder {
  display: grid;
  width: 29px;
  height: 29px;
  flex: none;
  place-items: center;
  overflow: hidden;
  border-radius: 50%;
  object-fit: cover;
}

.home-card__avatar-placeholder {
  background: #e9eee9;
  color: #637267;
  font-size: 0.75rem;
}

.home-card__publisher-name {
  overflow: hidden;
  color: var(--foreground);
  font-size: 0.78rem;
  font-weight: 610;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-card__published-meta {
  display: grid;
  justify-items: end;
  gap: 3px;
  color: var(--muted-foreground);
  font-size: 0.68rem;
  white-space: nowrap;
}

@media (prefers-reduced-motion: reduce) {
  .home-card,
  .home-card__media > img {
    transition: none;
  }
}

/* 图片淡入就绪前保持占位底色，避免加载瞬间的白闪 */
.home-card__media > img:not(.is-loaded) {
  background: #e9ede9;
}
</style>
