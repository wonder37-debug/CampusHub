<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps<{
  images: string[]
  initialIndex?: number
}>()

const emit = defineEmits<{
  close: []
}>()

const currentIndex = ref(props.initialIndex ?? 0)
const currentImage = computed(() => props.images[currentIndex.value] ?? '')

function prev() {
  if (currentIndex.value > 0) {
    currentIndex.value--
  }
}

function next() {
  if (currentIndex.value < props.images.length - 1) {
    currentIndex.value++
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    emit('close')
  } else if (e.key === 'ArrowLeft') {
    prev()
  } else if (e.key === 'ArrowRight') {
    next()
  }
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
  document.body.style.overflow = 'hidden'
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <div class="viewer-backdrop" @click.self="emit('close')">
      <div class="viewer-header">
        <span class="viewer-counter">{{ currentIndex + 1 }} / {{ images.length }}</span>
        <button class="viewer-close" @click="emit('close')" title="关闭">×</button>
      </div>

      <button v-if="currentIndex > 0" class="viewer-nav viewer-prev" @click="prev" title="上一张">‹</button>

      <div class="viewer-image-wrap">
        <img :src="currentImage" alt="预览图片" class="viewer-image" />
      </div>

      <button v-if="currentIndex < images.length - 1" class="viewer-nav viewer-next" @click="next" title="下一张">›</button>
    </div>
  </Teleport>
</template>

<style scoped>
.viewer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.92);
  display: flex;
  align-items: center;
  justify-content: center;
}

.viewer-header {
  position: absolute;
  top: 16px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1;
}

.viewer-counter {
  color: rgba(255, 255, 255, 0.75);
  font-size: 14px;
}

.viewer-close {
  position: absolute;
  right: 20px;
  background: none;
  border: none;
  color: #fff;
  font-size: 36px;
  cursor: pointer;
  line-height: 1;
  padding: 0 8px;
  opacity: 0.8;
  transition: opacity 0.15s;
}

.viewer-close:hover {
  opacity: 1;
}

.viewer-image-wrap {
  max-width: 90vw;
  max-height: 85vh;
  display: flex;
  align-items: center;
  justify-content: center;
}

.viewer-image {
  max-width: 90vw;
  max-height: 85vh;
  object-fit: contain;
  border-radius: 8px;
  user-select: none;
}

.viewer-nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  background: rgba(255, 255, 255, 0.12);
  border: none;
  color: #fff;
  font-size: 48px;
  width: 56px;
  height: 80px;
  cursor: pointer;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.6;
  transition: opacity 0.15s, background 0.15s;
  line-height: 1;
}

.viewer-nav:hover {
  opacity: 1;
  background: rgba(255, 255, 255, 0.2);
}

.viewer-prev {
  left: 16px;
}

.viewer-next {
  right: 16px;
}
</style>
