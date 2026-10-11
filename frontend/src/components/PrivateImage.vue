<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useCampusHubStore } from '@/stores/campusHub'

const props = defineProps<{
  src: string
  alt?: string
  width?: string
  height?: string
  borderRadius?: string
  cursor?: string
}>()

const store = useCampusHubStore()
const blobUrl = ref<string>('')

async function loadBlob(url: string) {
  if (!url) {
    blobUrl.value = ''
    return
  }
  blobUrl.value = await store.fetchProofImageBlob(url).catch(() => url)
}

watch(() => props.src, (url) => {
  if (blobUrl.value.startsWith('blob:')) URL.revokeObjectURL(blobUrl.value)
  blobUrl.value = ''
  if (url) loadBlob(url)
}, { immediate: true })

onUnmounted(() => {
  if (blobUrl.value.startsWith('blob:')) URL.revokeObjectURL(blobUrl.value)
})
</script>

<template>
  <img
    :src="blobUrl"
    :alt="alt ?? ''"
    :style="{
      width: width ?? '80px',
      height: height ?? '80px',
      objectFit: 'cover',
      borderRadius: borderRadius ?? '8px',
      cursor: cursor ?? 'default',
      border: '1px solid rgba(0,0,0,0.08)'
    }"
  />
</template>
