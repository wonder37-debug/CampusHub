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
const objectUrl = ref('')
const errorMessage = ref('')
// 每次 src 变化自增，用于丢弃过期异步请求的结果，避免加载顺序变化导致串图或 Blob 泄漏
let version = 0

function releaseObjectUrl() {
  if (objectUrl.value.startsWith('blob:')) {
    URL.revokeObjectURL(objectUrl.value)
  }
  objectUrl.value = ''
}

function showFailure(message: string) {
  releaseObjectUrl()
  errorMessage.value = message
}

async function load(src: string, expectedVersion: number) {
  if (!src) {
    if (expectedVersion === version) showFailure('凭证地址为空，无法加载')
    return
  }
  const result = await store.fetchProofImageBlob(src)
  // 过期结果：成功时也要释放刚拿到的 Blob URL，否则这次请求就泄漏了
  if (expectedVersion !== version) {
    if (result.ok) URL.revokeObjectURL(result.objectUrl)
    return
  }
  if (result.ok) {
    errorMessage.value = ''
    releaseObjectUrl()
    objectUrl.value = result.objectUrl
  } else {
    // 明确失败态：绝不用原始 src 走普通 <img>，那会绕过服务端鉴权
    showFailure(result.message)
  }
}

watch(() => props.src, (src) => {
  version++
  releaseObjectUrl()
  errorMessage.value = ''
  if (src) load(src, version)
}, { immediate: true })

onUnmounted(() => {
  version++
  releaseObjectUrl()
  errorMessage.value = ''
})
</script>

<template>
  <!-- 单一根节点：保证父组件传入的 @click 等 fallthrough 属性能落到真实 DOM 上 -->
  <div
    class="private-image"
    :style="{
      width: width ?? '80px',
      height: height ?? '80px',
      borderRadius: borderRadius ?? '8px',
      cursor: cursor ?? 'default'
    }"
  >
    <img
      v-if="objectUrl"
      :src="objectUrl"
      :alt="alt ?? ''"
      class="private-image-img"
    />
    <div
      v-else
      class="private-image-error"
      role="img"
      :aria-label="errorMessage || '凭证加载失败'"
      data-testid="private-image-error"
      :title="errorMessage"
    >
      <span class="private-image-error-icon" aria-hidden="true">⚠️</span>
      <span class="private-image-error-text">{{ errorMessage || '凭证加载失败' }}</span>
    </div>
  </div>
</template>

<style scoped>
.private-image {
  display: inline-flex;
  box-sizing: border-box;
  border: 1px solid rgba(0, 0, 0, 0.08);
  overflow: hidden;
  flex: 0 0 auto;
}

.private-image-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.private-image-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  height: 100%;
  padding: 4px;
  box-sizing: border-box;
  border: 1px dashed rgba(0, 0, 0, 0.18);
  background: rgba(0, 0, 0, 0.04);
  color: var(--text-weak, #888);
  text-align: center;
}

.private-image-error-icon {
  font-size: 16px;
  line-height: 1;
}

.private-image-error-text {
  font-size: 10px;
  line-height: 1.2;
  word-break: break-all;
}
</style>
