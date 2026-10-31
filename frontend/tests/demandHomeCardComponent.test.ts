import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
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

const expectedCoverUrl =
  '/api/v1/covers/SECOND_HAND/d-7.jpg?zone=XIANLIN&loc=%E4%BB%99%E6%9E%97%E6%A0%A1%E5%8C%BA'

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
  it('无上传图时使用后端生成封面，并带上校区与地点参数', () => {
    const wrapper = mountCard(baseDemand)

    // 不再退回"灰底 + 标题文字"，而是显示带文字的彩色封面
    expect(wrapper.find('.home-card__media-text').exists()).toBe(false)
    expect(wrapper.get('.home-card__media img').attributes('src')).toBe(expectedCoverUrl)
    expect(wrapper.text()).toContain('匿名同学 07')
    expect(wrapper.find('.home-card__publisher img').exists()).toBe(false)
    expect(wrapper.find('.home-card__footer').element.closest('a')).toBeNull()
    expect(wrapper.text()).not.toContain('校邻币')
  })

  it('有上传图时优先显示上传图，不被生成封面覆盖', () => {
    const wrapper = mountCard({ ...baseDemand, anonymous: false, anonymousCode: null, images: ['/demand-photo.jpg'] })

    expect(wrapper.get('.home-card__media img').attributes('src')).toBe('/demand-photo.jpg')
  })

  it('上传图失败先重试 2 次，再降级为生成封面', async () => {
    const wrapper = mountCard({ ...baseDemand, anonymous: false, anonymousCode: null, images: ['/demand-photo.jpg'] })

    await wrapper.get('.home-card__media img').trigger('error')
    expect(wrapper.get('.home-card__media img').attributes('src')).toBe('/demand-photo.jpg?retry=1')

    await wrapper.get('.home-card__media img').trigger('error')
    expect(wrapper.get('.home-card__media img').attributes('src')).toBe('/demand-photo.jpg?retry=2')

    await wrapper.get('.home-card__media img').trigger('error')
    expect(wrapper.get('.home-card__media img').attributes('src')).toBe(expectedCoverUrl)
  })

  it('封面也重试耗尽后退回文字占位', async () => {
    const wrapper = mountCard(baseDemand)

    await wrapper.get('.home-card__media img').trigger('error')
    await wrapper.get('.home-card__media img').trigger('error')
    await wrapper.get('.home-card__media img').trigger('error')

    expect(wrapper.find('.home-card__media img').exists()).toBe(false)
    expect(wrapper.find('.home-card__media-text').exists()).toBe(true)
  })

  it('同一条需求图片 URL 更换后重置失败状态，新图片重新渲染', async () => {
    const wrapper = mountCard({ ...baseDemand, anonymous: false, anonymousCode: null, images: ['/old.jpg'] })

    await wrapper.get('.home-card__media img').trigger('error')
    expect(wrapper.get('.home-card__media img').attributes('src')).toBe('/old.jpg?retry=1')

    await wrapper.setProps({
      demand: { ...baseDemand, id: 'd-7', anonymous: false, anonymousCode: null, images: ['/new.jpg'] }
    })
    await nextTick()

    expect(wrapper.get('.home-card__media img').attributes('src')).toBe('/new.jpg')
  })
})
