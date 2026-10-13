<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import AdminLayout from '@/layouts/AdminLayout.vue'
import StudentLayout from '@/layouts/StudentLayout.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { Toaster } from '@/components/ui/sonner'

const route = useRoute()
const activeLayout = computed(() => route.meta.adminOnly ? AdminLayout : StudentLayout)
</script>

<template>
  <component :is="activeLayout" />
  <ConfirmDialog />
  <Toaster position="top-right" />
</template>
