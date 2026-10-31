<script setup lang="ts">
import { computed, ref } from 'vue'
import { getPendingDialogs, handleDialogResponse } from '@/composables/useDialog'
import { useModalA11y } from '@/composables/useModalA11y'

const current = computed(() => {
  const dialogs = getPendingDialogs().value
  return dialogs.length > 0 ? dialogs[0] : null
})

const dialogRef = ref<HTMLElement | null>(null)
const isOpen = computed(() => current.value !== null)

function respond(confirmed: boolean): void {
  if (current.value) {
    handleDialogResponse(current.value.id, confirmed)
  }
}

// 全局确认框：Esc 等于取消、打开即聚焦（危险操作用例聚焦「取消」更安全）、关闭后焦点归还原位
useModalA11y(isOpen, dialogRef, { onClose: () => respond(false) })
</script>

<template>
  <Teleport to="body">
    <div v-if="current" class="modal-backdrop" @click.self="respond(false)">
      <div
        ref="dialogRef"
        class="modal-card panel"
        style="max-width:440px;text-align:center;"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-message"
      >
        <p id="confirm-dialog-title" class="eyebrow">{{ current.options.title }}</p>
        <p id="confirm-dialog-message" class="page-summary" style="margin-bottom:16px;">{{ current.options.message }}</p>
        <div class="card-actions" style="justify-content:center;">
          <template v-if="current.options.type === 'confirm'">
            <button type="button" class="button secondary" data-autofocus @click="respond(false)">
              {{ current.options.cancelText }}
            </button>
            <button
              type="button"
              :class="['button', current.options.danger ? 'danger' : 'primary']"
              @click="respond(true)"
            >
              {{ current.options.confirmText }}
            </button>
          </template>
          <template v-else>
            <button type="button" class="button primary" data-autofocus @click="respond(false)">
              {{ current.options.confirmText }}
            </button>
          </template>
        </div>
      </div>
    </div>
  </Teleport>
</template>
