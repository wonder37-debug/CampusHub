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
let version = 0

function revokeCurrent() {
  if (blobUrl.value.startsWith('blob:')) {
    URL.revokeObjectURL(blobUrl.value)
  }
  blobUrl.value = ''
}

async function loadBlob(url: string, expectedVersion: number) {
  if (!url) {
    if (expectedVersion === version) revokeCurrent()
    return
  }
  const fetched = await store.fetchProofImageBlob(url).catch(() => url)
  if (expectedVersion !== version) {
    if (fetched.startsWith('blob:')) URL.revokeObjectURL(fetched)
    return
  }
  revokeCurrent()
  blobUrl.value = fetched
}

watch(() => props.src, (url) => {
  version++
  revokeCurrent()
  if (url) loadBlob(url, version)
}, { immediate: true })

onUnmounted(() => {
  version++
  revokeCurrent()
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
