<script setup lang="ts">
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'

withDefaults(defineProps<{
  title?: string
  description: string
  retry?: boolean
  retryLabel?: string
}>(), {
  title: '暂时无法加载',
  retry: false,
  retryLabel: '重试'
})

const emit = defineEmits<{
  retry: []
}>()
</script>

<template>
  <Alert variant="destructive" role="alert" class="error-state">
    <AlertTitle>{{ title }}</AlertTitle>
    <AlertDescription>{{ description }}</AlertDescription>
    <Button v-if="retry" variant="outline" size="sm" @click="emit('retry')">
      {{ retryLabel }}
    </Button>
  </Alert>
</template>
