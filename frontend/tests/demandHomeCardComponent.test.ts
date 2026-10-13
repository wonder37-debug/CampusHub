import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import DemandHomeCard from '@/components/DemandHomeCard.vue'
import type { DemandRecord } from '@/types/campushub'

const baseDemand: DemandRecord = {
  id: 'd-7',
  title: '闲置教材转让',
  description: '九成新教材，仙林自取。',
  category: 'SECOND_HAND',
  campusZone: 'XIANLIN',
  location: '仙林校区',
  startTime: '',
  endTime: '',
  reward: 0,
  interactionMode: 'SELECT_ONE',
  targetParticipantCount: null,
  selectedParticipantCount: null,
  status: 'PENDING',
  anonymous: true,
  anonymousCode: '匿名同学 07',
  publisherId: '',
  publisherName: '匿名同学 07',
  publisherAvatar: '/must-not-show.png',
  tags: [],
  createdAt: '2026-10-12T09:00:00',
  updatedAt: '2026-10-12T09:00:00',
  distanceKm: 0,
  images: []
}

function mountCard(demand: DemandRecord) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/demands/:id', component: { template: '<div>详情</div>' } }]
  })
  return mount(DemandHomeCard, {
    props: { demand, currentUserId: 'viewer-1' },
    global: { plugins: [router] }
  })
}

describe('DemandHomeCard 视觉和隐私边界', () => {
  it('无图时显示中性占位且匿名卡片不加载发布者头像', () => {
    const wrapper = mountCard(baseDemand)

    expect(wrapper.find('.home-card__image-placeholder').exists()).toBe(true)
    expect(wrapper.text()).toContain('匿名同学 07')
    expect(wrapper.find('.home-card__publisher img').exists()).toBe(false)
    expect(wrapper.find('.home-card__footer').element.closest('a')).toBeNull()
    expect(wrapper.text()).not.toContain('校邻币')
  })

  it('需求图片加载失败时退回明确的中性失败占位', async () => {
    const wrapper = mountCard({ ...baseDemand, anonymous: false, anonymousCode: null, images: ['/demand-photo.jpg'] })
    await wrapper.get('.home-card__image img').trigger('error')

    expect(wrapper.find('.home-card__image-placeholder').exists()).toBe(true)
    expect(wrapper.find('.home-card__image img').exists()).toBe(false)
  })
})
