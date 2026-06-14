<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, watch } from 'vue'
import { getPendingDialogs, handleDialogResponse } from '@/composables/useDialog'

const pendingDialogs = getPendingDialogs()

// 处理键盘事件（Esc 键关闭）
function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    const topDialog = pendingDialogs.value[pendingDialogs.value.length - 1]
    if (topDialog && topDialog.options.type === 'confirm') {
      handleDialogResponse(topDialog.id, false)
    } else if (topDialog && topDialog.options.type === 'alert') {
      handleDialogResponse(topDialog.id, true)
    }
  }
}

// 禁止/恢复背景滚动
function toggleBodyScroll(disable: boolean): void {
  document.body.style.overflow = disable ? 'hidden' : ''
}

// 对话框打开后自动聚焦取消按钮（防止误操作）
async function autoFocusCancel(): Promise<void> {
  await nextTick()
  const btn = document.querySelector<HTMLButtonElement>('.dialog-btn-cancel')
  btn?.focus()
}

// 监听对话框变化
watch(
  () => pendingDialogs.value.length,
  (newLength, oldLength) => {
    if (newLength > 0 && oldLength === 0) {
      toggleBodyScroll(true)
      document.addEventListener('keydown', handleKeydown)
      autoFocusCancel()
    } else if (newLength > oldLength) {
      autoFocusCancel()
    } else if (newLength === 0) {
      toggleBodyScroll(false)
      document.removeEventListener('keydown', handleKeydown)
    }
  }
)

onMounted(() => {
  if (pendingDialogs.value.length > 0) {
    toggleBodyScroll(true)
    document.addEventListener('keydown', handleKeydown)
    autoFocusCancel()
  }
})

onUnmounted(() => {
  toggleBodyScroll(false)
  document.removeEventListener('keydown', handleKeydown)
})

function handleConfirm(id: number): void {
  handleDialogResponse(id, true)
}

function handleCancel(id: number): void {
  handleDialogResponse(id, false)
}

function handleBackdropClick(id: number, event: MouseEvent): void {
  if (event.target === event.currentTarget) {
    const dialog = pendingDialogs.value.find((d) => d.id === id)
    if (dialog?.options.type === 'confirm') {
      handleDialogResponse(id, false)
    } else if (dialog?.options.type === 'alert') {
      handleDialogResponse(id, true)
    }
  }
}
</script>

<template>
  <teleport to="body">
    <TransitionGroup name="dialog">
      <div
        v-for="dialog in pendingDialogs"
        :key="dialog.id"
        class="dialog-backdrop"
        @click="(e) => handleBackdropClick(dialog.id, e)"
      >
        <div class="dialog-card panel">
          <div class="dialog-header">
            <h3 class="dialog-title">{{ dialog.options.title }}</h3>
          </div>
          
          <p class="dialog-message">{{ dialog.options.message }}</p>
          
          <div class="dialog-actions">
            <button
              v-if="dialog.options.type === 'confirm'"
              type="button"
              class="button secondary dialog-btn dialog-btn-cancel"
              @click="handleCancel(dialog.id)"
            >
              {{ dialog.options.cancelText }}
            </button>
            <button
              type="button"
              :class="[
                'button',
                dialog.options.danger ? 'danger' : 'primary',
                'dialog-btn'
              ]"
              @click="handleConfirm(dialog.id)"
            >
              {{ dialog.options.confirmText }}
            </button>
          </div>
        </div>
      </div>
    </TransitionGroup>
  </teleport>
</template>

<style scoped>
.dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(31, 26, 23, 0.5);
  backdrop-filter: blur(8px);
}

.dialog-card {
  width: min(90vw, 420px);
  max-width: 100%;
  padding: 24px;
  border-radius: var(--radius-lg);
  box-shadow: 0 24px 60px rgba(58, 39, 21, 0.2);
  animation: dialogSlideIn 0.25s ease-out;
}

@keyframes dialogSlideIn {
  from {
    opacity: 0;
    transform: translateY(-20px) scale(0.95);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.dialog-header {
  margin-bottom: 12px;
}

.dialog-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--text-strong);
  font-family: var(--heading);
}

.dialog-message {
  margin: 0 0 20px;
  font-size: 0.95rem;
  line-height: 1.6;
  color: var(--text);
}

.dialog-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.dialog-btn {
  min-width: 80px;
  padding: 10px 20px;
  font-weight: 600;
}

/* Vue Transition */
.dialog-enter-active,
.dialog-leave-active {
  transition: all 0.25s ease;
}

.dialog-enter-from .dialog-card,
.dialog-leave-to .dialog-card {
  opacity: 0;
  transform: translateY(-20px) scale(0.95);
}

.dialog-enter-from,
.dialog-leave-to {
  background: rgba(31, 26, 23, 0);
  backdrop-filter: blur(0);
}

/* Danger button style */
.button.danger {
  color: #fff;
  background: linear-gradient(135deg, #b54747, #a03d3d);
}

.button.danger:hover {
  background: linear-gradient(135deg, #a03d3d, #8b3434);
  box-shadow: 0 8px 20px rgba(181, 71, 71, 0.3);
}

/* 移动端适配 */
@media (max-width: 780px) {
  .dialog-card {
    padding: 20px;
    width: min(95vw, 360px);
  }
  
  .dialog-title {
    font-size: 1.1rem;
  }
  
  .dialog-message {
    font-size: 0.9rem;
  }
  
  .dialog-actions {
    flex-direction: column-reverse;
  }
  
  .dialog-btn {
    width: 100%;
  }
}
</style>
