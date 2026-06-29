<script setup lang="ts">
import { ref, computed, nextTick, onUnmounted } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: string
  size?: number
  maxSizeMB?: number
}>(), {
  size: 80,
  maxSizeMB: 5
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

/* ── 常量 ── */
const CANVAS_SIZE = 320
const OUTPUT_SIZE = 256
const PREVIEW_SIZE = 88

/* ── DOM 引用 ── */
const fileInput = ref<HTMLInputElement | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
const previewCanvasRef = ref<HTMLCanvasElement | null>(null)

/* ── 状态 ── */
const showCropModal = ref(false)
const uploading = ref(false)
const errorMsg = ref('')

/* ── 图像状态 ── */
const imageEl = ref<HTMLImageElement | null>(null)
const objectUrl = ref('')
const scale = ref(1)
const offsetX = ref(0)
const offsetY = ref(0)
const minScale = ref(0.1)
const maxScale = ref(5)

/* ── 交互状态 ── */
const isDragging = ref(false)
const dragStartX = ref(0)
const dragStartY = ref(0)
const dragStartOffsetX = ref(0)
const dragStartOffsetY = ref(0)

/* ── 触摸状态 ── */
const touchStartDistance = ref(0)
const touchStartScale = ref(1)
const touchStartOffsetX = ref(0)
const touchStartOffsetY = ref(0)
const touchStartCenterX = ref(0)
const touchStartCenterY = ref(0)

/* ── 计算属性 ── */
const displaySize = computed(() => props.size ?? 80)
const maxSizeBytes = computed(() => props.maxSizeMB * 1024 * 1024)
const hasAvatar = computed(() => !!props.modelValue.trim())
const zoomPercent = computed(() => {
  const range = maxScale.value - minScale.value
  if (range <= 0) return 50
  return Math.round(((scale.value - minScale.value) / range) * 100)
})

/* ── 文件选择 ── */
function triggerUpload() {
  if (uploading.value) return
  fileInput.value?.click()
}

function handleFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  target.value = ''

  const allowedExts = ['jpg', 'jpeg', 'png', 'webp']
  const ext = file.name.split('.').pop()?.toLowerCase()
  if (!ext || !allowedExts.includes(ext)) {
    errorMsg.value = '仅支持 jpg/png/webp 格式'
    return
  }
  if (file.size > maxSizeBytes.value) {
    errorMsg.value = `图片大小不能超过 ${props.maxSizeMB}MB`
    return
  }

  errorMsg.value = ''
  loadImageFile(file)
}

/* ── 图像加载 ── */
function loadImageFile(file: File) {
  // 清理旧的 objectUrl
  if (objectUrl.value) {
    URL.revokeObjectURL(objectUrl.value)
  }

  objectUrl.value = URL.createObjectURL(file)
  const img = new Image()
  img.onload = () => {
    imageEl.value = img
    showCropModal.value = true
    nextTick(() => {
      initCropState()
      drawCanvas()
    })
  }
  img.onerror = () => {
    errorMsg.value = '图片加载失败'
    cleanupObjectUrl()
  }
  img.src = objectUrl.value
}

function initCropState() {
  const img = imageEl.value
  if (!img) return

  // 初始缩放：让较短边填满画布
  const minDim = Math.min(img.width, img.height)
  const fitScale = CANVAS_SIZE / minDim
  scale.value = fitScale
  offsetX.value = 0
  offsetY.value = 0
  minScale.value = fitScale
  maxScale.value = fitScale * 5
}

/* ── 边界约束 ── */
function clampOffset() {
  const img = imageEl.value
  if (!img) return

  // 计算图像在画布上的显示尺寸
  const displayW = img.width * scale.value
  const displayH = img.height * scale.value

  // 圆形裁剪区域半径 = CANVAS_SIZE / 2
  // 图像中心相对于画布中心的偏移不能让裁剪圆超出图像边界
  const maxOffsetX = Math.max(0, (displayW - CANVAS_SIZE) / 2)
  const maxOffsetY = Math.max(0, (displayH - CANVAS_SIZE) / 2)

  offsetX.value = Math.max(-maxOffsetX, Math.min(maxOffsetX, offsetX.value))
  offsetY.value = Math.max(-maxOffsetY, Math.min(maxOffsetY, offsetY.value))
}

/* ── Canvas 绘制 ── */
function drawCanvas() {
  const canvas = canvasRef.value
  const img = imageEl.value
  if (!canvas || !img) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const CENTER = CANVAS_SIZE / 2

  // 清空画布
  ctx.clearRect(0, 0, CANVAS_SIZE, CANVAS_SIZE)

  // 绘制图像（带变换）
  ctx.save()
  ctx.translate(CENTER + offsetX.value, CENTER + offsetY.value)
  ctx.scale(scale.value, scale.value)
  ctx.drawImage(img, -img.width / 2, -img.height / 2)
  ctx.restore()

  // 绘制圆形遮罩（圆外区域变暗）
  ctx.save()
  ctx.fillStyle = 'rgba(0, 0, 0, 0.5)'
  ctx.beginPath()
  ctx.rect(0, 0, CANVAS_SIZE, CANVAS_SIZE)
  ctx.arc(CENTER, CENTER, CENTER, 0, Math.PI * 2)
  ctx.fill('evenodd')
  ctx.restore()

  // 绘制圆形边框
  ctx.save()
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.85)'
  ctx.lineWidth = 2
  ctx.beginPath()
  ctx.arc(CENTER, CENTER, CENTER, 0, Math.PI * 2)
  ctx.stroke()
  ctx.restore()

  // 绘制预览
  drawPreview()
}

function drawPreview() {
  const canvas = previewCanvasRef.value
  const img = imageEl.value
  if (!canvas || !img) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.clearRect(0, 0, PREVIEW_SIZE, PREVIEW_SIZE)

  // 圆形裁剪
  ctx.save()
  ctx.beginPath()
  ctx.arc(PREVIEW_SIZE / 2, PREVIEW_SIZE / 2, PREVIEW_SIZE / 2, 0, Math.PI * 2)
  ctx.clip()

  // 计算源区域
  const srcSize = CANVAS_SIZE / scale.value
  const srcCenterX = img.width / 2 - offsetX.value / scale.value
  const srcCenterY = img.height / 2 - offsetY.value / scale.value
  const srcX = srcCenterX - srcSize / 2
  const srcY = srcCenterY - srcSize / 2

  ctx.drawImage(img, srcX, srcY, srcSize, srcSize, 0, 0, PREVIEW_SIZE, PREVIEW_SIZE)
  ctx.restore()
}

/* ── 鼠标交互 ── */
function onMouseDown(e: MouseEvent) {
  isDragging.value = true
  dragStartX.value = e.clientX
  dragStartY.value = e.clientY
  dragStartOffsetX.value = offsetX.value
  dragStartOffsetY.value = offsetY.value

  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
}

function onMouseMove(e: MouseEvent) {
  if (!isDragging.value) return
  offsetX.value = dragStartOffsetX.value + (e.clientX - dragStartX.value)
  offsetY.value = dragStartOffsetY.value + (e.clientY - dragStartY.value)
  clampOffset()
  drawCanvas()
}

function onMouseUp() {
  isDragging.value = false
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
}

function onWheel(e: WheelEvent) {
  const delta = e.deltaY > 0 ? -0.05 : 0.05
  zoomBy(delta)
}

/* ── 缩放 ── */
function zoomBy(delta: number) {
  const newScale = scale.value * (1 + delta)
  scale.value = Math.max(minScale.value, Math.min(maxScale.value, newScale))
  clampOffset()
  drawCanvas()
}

function zoomIn() {
  zoomBy(0.1)
}

function zoomOut() {
  zoomBy(-0.1)
}

function onSliderInput(e: Event) {
  const target = e.target as HTMLInputElement
  scale.value = parseFloat(target.value)
  clampOffset()
  drawCanvas()
}

/* ── 触摸交互 ── */
function onTouchStart(e: TouchEvent) {
  if (e.touches.length === 1) {
    // 单指拖拽
    isDragging.value = true
    dragStartX.value = e.touches[0].clientX
    dragStartY.value = e.touches[0].clientY
    dragStartOffsetX.value = offsetX.value
    dragStartOffsetY.value = offsetY.value
  } else if (e.touches.length === 2) {
    // 双指缩放
    isDragging.value = false
    const dx = e.touches[0].clientX - e.touches[1].clientX
    const dy = e.touches[0].clientY - e.touches[1].clientY
    touchStartDistance.value = Math.hypot(dx, dy)
    touchStartScale.value = scale.value
    touchStartOffsetX.value = offsetX.value
    touchStartOffsetY.value = offsetY.value
    touchStartCenterX.value = (e.touches[0].clientX + e.touches[1].clientX) / 2
    touchStartCenterY.value = (e.touches[0].clientY + e.touches[1].clientY) / 2
  }
}

function onTouchMove(e: TouchEvent) {
  if (e.touches.length === 1 && isDragging.value) {
    // 单指拖拽
    offsetX.value = dragStartOffsetX.value + (e.touches[0].clientX - dragStartX.value)
    offsetY.value = dragStartOffsetY.value + (e.touches[0].clientY - dragStartY.value)
    clampOffset()
    drawCanvas()
  } else if (e.touches.length === 2) {
    // 双指缩放
    const dx = e.touches[0].clientX - e.touches[1].clientX
    const dy = e.touches[0].clientY - e.touches[1].clientY
    const distance = Math.hypot(dx, dy)
    if (touchStartDistance.value > 0) {
      const ratio = distance / touchStartDistance.value
      const newScale = touchStartScale.value * ratio
      scale.value = Math.max(minScale.value, Math.min(maxScale.value, newScale))
      clampOffset()
      drawCanvas()
    }
  }
}

function onTouchEnd(e: TouchEvent) {
  if (e.touches.length === 0) {
    isDragging.value = false
  } else if (e.touches.length === 1) {
    // 从双指变为单指，更新拖拽起点
    isDragging.value = true
    dragStartX.value = e.touches[0].clientX
    dragStartY.value = e.touches[0].clientY
    dragStartOffsetX.value = offsetX.value
    dragStartOffsetY.value = offsetY.value
  }
}

/* ── 裁剪 & 上传 ── */
async function confirmCrop() {
  const img = imageEl.value
  if (!img || uploading.value) return

  uploading.value = true
  errorMsg.value = ''

  try {
    // 1. Canvas 裁剪
    const blob = await cropToBlob()

    // 2. 包装为 File
    const fileName = `avatar_${Date.now()}.png`
    const file = new File([blob], fileName, { type: 'image/png' })

    // 3. 上传
    const { useCampusHubStore } = await import('@/stores/campusHub')
    const store = useCampusHubStore()
    const urls = await store.uploadImages([file])

    if (urls.length > 0) {
      emit('update:modelValue', urls[0])
      closeCropModal()
    } else {
      errorMsg.value = '上传返回为空，请重试'
    }
  } catch (err: any) {
    errorMsg.value = err.message || '裁剪上传失败'
  } finally {
    uploading.value = false
  }
}

function cropToBlob(): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const img = imageEl.value
    if (!img) {
      reject(new Error('图像未加载'))
      return
    }

    const canvas = document.createElement('canvas')
    canvas.width = OUTPUT_SIZE
    canvas.height = OUTPUT_SIZE
    const ctx = canvas.getContext('2d')
    if (!ctx) {
      reject(new Error('无法创建 Canvas'))
      return
    }

    // 圆形裁剪
    ctx.save()
    ctx.beginPath()
    ctx.arc(OUTPUT_SIZE / 2, OUTPUT_SIZE / 2, OUTPUT_SIZE / 2, 0, Math.PI * 2)
    ctx.clip()

    // 计算源区域（与预览相同的逻辑）
    const srcSize = CANVAS_SIZE / scale.value
    const srcCenterX = img.width / 2 - offsetX.value / scale.value
    const srcCenterY = img.height / 2 - offsetY.value / scale.value
    const srcX = srcCenterX - srcSize / 2
    const srcY = srcCenterY - srcSize / 2

    ctx.drawImage(img, srcX, srcY, srcSize, srcSize, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
    ctx.restore()

    canvas.toBlob(
      (blob) => {
        if (blob) resolve(blob)
        else reject(new Error('裁剪失败'))
      },
      'image/png',
      0.92
    )
  })
}

/* ── 模态框管理 ── */
function closeCropModal() {
  showCropModal.value = false
  cleanupObjectUrl()
  imageEl.value = null
  errorMsg.value = ''
  // 移除可能残留的事件监听
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
}

function cleanupObjectUrl() {
  if (objectUrl.value) {
    URL.revokeObjectURL(objectUrl.value)
    objectUrl.value = ''
  }
}

/* ── 生命周期清理 ── */
onUnmounted(() => {
  cleanupObjectUrl()
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
})
</script>

<template>
  <div class="avatar-cropper">
    <!-- 头像显示区域 -->
    <div
      class="avatar-display"
      :style="{ width: displaySize + 'px', height: displaySize + 'px' }"
      @click="triggerUpload"
      :title="hasAvatar ? '点击更换头像' : '点击上传头像'"
    >
      <img v-if="hasAvatar" :src="modelValue" alt="头像" class="avatar-img" />
      <div v-else class="avatar-placeholder">
        <span class="placeholder-icon">📷</span>
      </div>
      <!-- 悬停遮罩 -->
      <div class="avatar-hover-overlay" :class="{ 'is-uploading': uploading }">
        <span v-if="uploading" class="uploading-text">上传中…</span>
        <template v-else>
          <span class="overlay-icon">📷</span>
          <span class="overlay-text">{{ hasAvatar ? '更换' : '上传' }}</span>
        </template>
      </div>
    </div>

    <!-- 错误提示（模态框外） -->
    <p v-if="errorMsg && !showCropModal" class="cropper-error">{{ errorMsg }}</p>

    <!-- 隐藏的文件输入 -->
    <input
      ref="fileInput"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      style="display: none"
      @change="handleFileChange"
    />

    <!-- 裁剪模态框 -->
    <Teleport to="body">
      <div v-if="showCropModal" class="crop-modal-backdrop" @click.self="closeCropModal">
        <div class="crop-modal-card">
          <!-- 头部 -->
          <div class="crop-modal-head">
            <h3>裁剪头像</h3>
            <button type="button" class="crop-close-btn" @click="closeCropModal" title="关闭">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>

          <!-- 主体 -->
          <div class="crop-modal-body">
            <!-- 裁剪画布 -->
            <div class="crop-canvas-wrap">
              <canvas
                ref="canvasRef"
                :width="CANVAS_SIZE"
                :height="CANVAS_SIZE"
                class="crop-canvas"
                @mousedown="onMouseDown"
                @wheel.prevent="onWheel"
                @touchstart.prevent="onTouchStart"
                @touchmove.prevent="onTouchMove"
                @touchend="onTouchEnd"
              ></canvas>
              <p class="crop-hint">拖拽移动 · 滚轮 / 双指缩放</p>
            </div>

            <!-- 控制区 -->
            <div class="crop-sidebar">
              <!-- 预览 -->
              <div class="crop-preview-section">
                <span class="preview-label">预览效果</span>
                <div class="preview-circle">
                  <canvas ref="previewCanvasRef" :width="PREVIEW_SIZE" :height="PREVIEW_SIZE" class="preview-canvas"></canvas>
                </div>
                <span class="preview-size-hint">{{ OUTPUT_SIZE }}×{{ OUTPUT_SIZE }} px</span>
              </div>

              <!-- 缩放控制 -->
              <div class="zoom-section">
                <span class="zoom-label">缩放</span>
                <div class="zoom-controls">
                  <button type="button" class="zoom-btn" @click="zoomOut" :disabled="scale <= minScale">−</button>
                  <input
                    type="range"
                    class="zoom-slider"
                    :min="minScale"
                    :max="maxScale"
                    :step="0.01"
                    :value="scale"
                    @input="onSliderInput"
                  />
                  <button type="button" class="zoom-btn" @click="zoomIn" :disabled="scale >= maxScale">+</button>
                </div>
                <span class="zoom-percent">{{ zoomPercent }}%</span>
              </div>
            </div>
          </div>

          <!-- 底部操作 -->
          <div class="crop-modal-footer">
            <p v-if="errorMsg" class="crop-error">{{ errorMsg }}</p>
            <div class="crop-actions">
              <button type="button" class="button secondary" @click="closeCropModal" :disabled="uploading">取消</button>
              <button type="button" class="button primary crop-confirm-btn" :disabled="uploading" @click="confirmCrop">
                <span v-if="uploading" class="loading-spinner"></span>
                {{ uploading ? '上传中…' : '确认裁剪' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.avatar-cropper {
  display: inline-flex;
  flex-direction: column;
  gap: 6px;
}

/* ── 头像显示区 ── */
.avatar-display {
  position: relative;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
  border: 3px solid rgba(255, 255, 255, 0.9);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  transition: box-shadow 0.2s ease;
}

.avatar-display:hover {
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(31, 95, 83, 0.16), rgba(227, 143, 61, 0.16));
}

.placeholder-icon {
  font-size: 28px;
}

.avatar-hover-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  opacity: 0;
  transition: opacity 0.2s ease;
  border-radius: 50%;
  pointer-events: none;
}

.avatar-display:hover .avatar-hover-overlay {
  opacity: 1;
}

.avatar-hover-overlay.is-uploading {
  opacity: 1;
}

.overlay-icon {
  font-size: 18px;
}

.overlay-text,
.uploading-text {
  font-size: 12px;
  font-weight: 600;
}

.cropper-error {
  color: var(--danger, #b54747);
  font-size: 12px;
  margin: 0;
}

/* ── 裁剪模态框 ── */
.crop-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(31, 26, 23, 0.55);
  backdrop-filter: blur(6px);
}

.crop-modal-card {
  width: min(560px, 100%);
  background: #fff;
  border-radius: 20px;
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.25);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.crop-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.crop-modal-head h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #1f1a17;
}

.crop-close-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.05);
  color: #666;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.crop-close-btn:hover {
  background: rgba(181, 71, 71, 0.12);
  color: #b54747;
}

/* ── 模态框主体 ── */
.crop-modal-body {
  display: flex;
  gap: 20px;
  padding: 20px;
}

.crop-canvas-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.crop-canvas {
  width: 320px;
  height: 320px;
  max-width: 100%;
  border-radius: 12px;
  background: #1a1a1a;
  touch-action: none;
  cursor: grab;
}

.crop-canvas:active {
  cursor: grabbing;
}

.crop-hint {
  margin: 0;
  font-size: 12px;
  color: #999;
  text-align: center;
}

/* ── 侧栏 ── */
.crop-sidebar {
  flex-shrink: 0;
  width: 140px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding-top: 8px;
}

.crop-preview-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.preview-label,
.zoom-label {
  font-size: 12px;
  font-weight: 600;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.preview-circle {
  width: 88px;
  height: 88px;
  border-radius: 50%;
  overflow: hidden;
  border: 2px solid rgba(0, 0, 0, 0.1);
  background: #f5f5f5;
}

.preview-canvas {
  width: 100%;
  height: 100%;
  display: block;
}

.preview-size-hint {
  font-size: 11px;
  color: #bbb;
}

.zoom-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.zoom-controls {
  display: flex;
  align-items: center;
  gap: 6px;
}

.zoom-btn {
  width: 30px;
  height: 30px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 8px;
  background: #fff;
  color: #333;
  font-size: 18px;
  font-weight: 700;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 0.15s, border-color 0.15s;
  flex-shrink: 0;
}

.zoom-btn:hover:not(:disabled) {
  background: rgba(31, 95, 83, 0.08);
  border-color: rgba(31, 95, 83, 0.3);
}

.zoom-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.zoom-slider {
  flex: 1;
  height: 4px;
  -webkit-appearance: none;
  appearance: none;
  background: rgba(0, 0, 0, 0.1);
  border-radius: 2px;
  outline: none;
  cursor: pointer;
}

.zoom-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--accent, #1f5f53);
  cursor: pointer;
  border: 2px solid #fff;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.2);
}

.zoom-slider::-moz-range-thumb {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--accent, #1f5f53);
  cursor: pointer;
  border: 2px solid #fff;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.2);
}

.zoom-percent {
  font-size: 12px;
  color: #888;
  text-align: center;
}

/* ── 底部 ── */
.crop-modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.crop-error {
  color: var(--danger, #b54747);
  font-size: 13px;
  margin: 0;
  flex: 1;
}

.crop-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
  margin-left: auto;
}

.crop-confirm-btn {
  min-width: 120px;
  justify-content: center;
}

/* ── 加载动画 ── */
.loading-spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
  margin-right: 6px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 响应式 ── */
@media (max-width: 520px) {
  .crop-modal-body {
    flex-direction: column;
    align-items: center;
  }

  .crop-sidebar {
    width: 100%;
    flex-direction: row;
    justify-content: center;
    align-items: center;
    gap: 24px;
  }

  .crop-canvas {
    width: 280px;
    height: 280px;
  }

  .crop-modal-footer {
    flex-direction: column;
    align-items: stretch;
  }

  .crop-actions {
    margin-left: 0;
    justify-content: flex-end;
  }
}
</style>
