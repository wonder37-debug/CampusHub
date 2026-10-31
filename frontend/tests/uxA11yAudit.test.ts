import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'

import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ImageViewer from '@/components/ImageViewer.vue'
import { useConfirm } from '@/composables/useDialog'

const { storeHolder, mockFetchNotifications } = vi.hoisted(() => {
  const storeHolder: any = { value: null }
  const mockFetchNotifications = vi.fn(async () => {})
  return { storeHolder, mockFetchNotifications }
})

vi.mock('@/stores/campusHub', () => ({ useCampusHubStore: () => storeHolder.value }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn() })
}))

function notification(id: number, isRead = false) {
  return {
    id: `n-${id}`,
    type: 'ORDER_ACCEPTED',
    title: `通知 ${id}`,
    content: `内容 ${id}`,
    isRead,
    createdAt: '2026-10-31T10:00:00',
    relatedId: `o-${id}`,
    targetId: `o-${id}`,
    targetType: 'ORDER',
    actionHint: 'VIEW_ORDER',
    targetTitle: null,
    relatedName: null
  }
}

describe('全局确认框无障碍行为', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    wrapper = mount(ConfirmDialog, { attachTo: document.body })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  it('渲染 dialog 语义，打开后焦点落在「取消」而不是危险操作上', async () => {
    const pending = useConfirm('取消订单', '取消后无法恢复，确认取消该订单吗？', { danger: true })
    await nextTick()
    await nextTick()

    const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]')
    expect(dialog).not.toBeNull()
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    expect(dialog?.getAttribute('aria-labelledby')).toBe('confirm-dialog-title')
    expect(document.getElementById('confirm-dialog-title')?.textContent).toContain('取消订单')

    const cancel = dialog?.querySelector<HTMLButtonElement>('[data-autofocus]')
    expect(cancel?.textContent?.trim()).toBe('取消')
    expect(document.activeElement).toBe(cancel)

    cancel?.click()
    await expect(pending).resolves.toBe(false)
  })

  it('Esc 视为取消，不会误触发危险操作', async () => {
    const pending = useConfirm('删除记录', '确认删除？', { danger: true })
    await nextTick()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await expect(pending).resolves.toBe(false)
  })

  it('队列中的第二个确认框会自动接管焦点', async () => {
    const first = useConfirm('第一个确认', '先处理这个', { danger: true })
    const second = useConfirm('第二个确认', '再处理这个', { danger: true })
    await nextTick()
    await nextTick()

    const firstCancel = document.body.querySelector<HTMLButtonElement>('[data-autofocus]')
    firstCancel?.click()
    await expect(first).resolves.toBe(false)
    await nextTick()
    await nextTick()

    expect(document.getElementById('confirm-dialog-title')?.textContent).toContain('第二个确认')
    const secondCancel = document.body.querySelector<HTMLButtonElement>('[data-autofocus]')
    expect(document.activeElement).toBe(secondCancel)

    secondCancel?.click()
    await expect(second).resolves.toBe(false)
  })
})

describe('图片预览层无障碍行为', () => {
  let wrapper: VueWrapper | undefined

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.style.overflow = ''
  })

  it('提供 dialog 语义、可访问的按钮名与焦点入口', async () => {
    wrapper = mount(ImageViewer, { props: { images: ['/a.jpg', '/b.jpg'] } })
    await nextTick()
    await nextTick()

    const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]')
    expect(dialog).not.toBeNull()
    expect(dialog?.getAttribute('aria-modal')).toBe('true')

    const close = dialog?.querySelector<HTMLButtonElement>('[data-autofocus]')
    expect(close?.getAttribute('aria-label')).toBe('关闭预览')
    expect(document.activeElement).toBe(close)
  })

  it('方向键翻页会更新序号，Esc 关闭并通知父组件', async () => {
    wrapper = mount(ImageViewer, { props: { images: ['/a.jpg', '/b.jpg'] } })
    await nextTick()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    await nextTick()
    expect(document.body.querySelector('.viewer-counter')?.textContent?.trim()).toBe('2 / 2')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})

describe('通知列表键盘可达性', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    storeHolder.value = reactive({
      currentUser: { id: 'me', role: 'USER' },
      currentUserNotifications: Array.from({ length: 15 }, (_, i) => notification(i + 1)),
      notificationsFetchError: null,
      fetchNotifications: mockFetchNotifications,
      markNotificationRead: vi.fn(async () => {}),
      markAllNotificationsRead: vi.fn(async () => {})
    })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.clearAllMocks()
  })

  it('「加载更多」是按钮控件，可用键盘触发翻页', async () => {
    const { default: NotificationsView } = await import('@/views/NotificationsView.vue')
    wrapper = mount(NotificationsView)
    await flushPromises()

    const loadMore = wrapper.find('.load-more-panel')
    expect(loadMore.exists()).toBe(true)
    expect(loadMore.element.tagName).toBe('BUTTON')

    await loadMore.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.notification-card')).toHaveLength(15)
  })
})
