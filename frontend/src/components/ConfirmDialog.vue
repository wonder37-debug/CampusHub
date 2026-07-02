<script setup lang="ts">
import { computed } from 'vue'
import { getPendingDialogs, handleDialogResponse } from '@/composables/useDialog'

const current = computed(() => {
  const dialogs = getPendingDialogs().value
  return dialogs.length > 0 ? dialogs[0] : null
})

function respond(confirmed: boolean): void {
  if (current.value) {
    handleDialogResponse(current.value.id, confirmed)
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="current" class="modal-backdrop" @click.self="respond(false)">
      <div class="modal-card panel" style="max-width:440px;text-align:center;">
        <p class="eyebrow">{{ current.options.title }}</p>
        <p class="page-summary" style="margin-bottom:16px;">{{ current.options.message }}</p>
        <div class="card-actions" style="justify-content:center;">
          <template v-if="current.options.type === 'confirm'">
            <button type="button" class="button secondary" @click="respond(false)">
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
            <button type="button" class="button primary" @click="respond(false)">
              {{ current.options.confirmText }}
            </button>
          </template>
        </div>
      </div>
    </div>
  </Teleport>
</template>
