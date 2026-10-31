import { nextTick, onBeforeUnmount, watch, type Ref } from 'vue'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

export interface ModalA11yOptions {
  /** Esc 是否关闭弹窗，默认 true */
  escape?: boolean
  /** Esc 触发时的关闭回调 */
  onClose?: () => void
}

/**
 * 弹窗无障碍基座：Esc 关闭、Tab 焦点陷阱、打开时移入焦点、关闭后归还焦点、锁定背景滚动。
 *
 * <p>用法：弹窗根节点挂 ref，传入 `isOpen`（打开时 DOM 才存在）。
 * 可在弹窗内用 `data-autofocus` 指定初始焦点元素（例如危险确认框的「取消」按钮）。
 */
export function useModalA11y(
  isOpen: Ref<boolean>,
  container: Ref<HTMLElement | null>,
  options: ModalA11yOptions = {}
): void {
  let restoreTarget: HTMLElement | null = null
  let previousOverflow = ''
  let active = false

  function focusables(): HTMLElement[] {
    const root = container.value
    if (!root) return []
    return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape' && options.escape !== false) {
      event.preventDefault()
      options.onClose?.()
      return
    }
    if (event.key !== 'Tab') return

    const items = focusables()
    if (!items.length) {
      event.preventDefault()
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    const current = document.activeElement as HTMLElement | null
    const outside = !container.value?.contains(current)

    if (event.shiftKey) {
      if (outside || current === first) {
        event.preventDefault()
        last.focus()
      }
    } else if (outside || current === last) {
      event.preventDefault()
      first.focus()
    }
  }

  async function activate(): Promise<void> {
    restoreTarget = document.activeElement as HTMLElement | null
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    // 捕获阶段监听，弹窗内部元素不会吞掉 Esc/Tab
    document.addEventListener('keydown', onKeydown, true)
    active = true

    await nextTick()
    const root = container.value
    if (!root) return
    if (!root.hasAttribute('tabindex')) {
      root.setAttribute('tabindex', '-1')
    }
    const preferred = root.querySelector<HTMLElement>('[data-autofocus]')
    const target = preferred ?? focusables()[0] ?? root
    target.focus()
  }

  function deactivate(): void {
    if (!active) return
    active = false
    document.removeEventListener('keydown', onKeydown, true)
    document.body.style.overflow = previousOverflow
    if (restoreTarget && document.contains(restoreTarget)) {
      restoreTarget.focus()
    }
    restoreTarget = null
  }

  watch(
    isOpen,
    (open) => {
      if (open) {
        void activate()
      } else {
        deactivate()
      }
    },
    { immediate: true, flush: 'post' }
  )

  onBeforeUnmount(() => {
    deactivate()
    if (isOpen.value && previousOverflow !== undefined) {
      document.body.style.overflow = previousOverflow
    }
  })
}
