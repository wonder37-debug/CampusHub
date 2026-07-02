<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  modelValue: string[]
  maxCount?: number
  maxSizeMB?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

const maxCount = computed(() => props.maxCount ?? 6)
const maxSizeBytes = computed(() => (props.maxSizeMB ?? 5) * 1024 * 1024)

const uploading = ref(false)
const uploadProgress = ref(0)
const dragOver = ref(false)
const errorMsg = ref('')

const fileInput = ref<HTMLInputElement | null>(null)

function triggerFileInput() {
  fileInput.value?.click()
}

function handleFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const files = target.files
  if (!files || files.length === 0) return
  processFiles(Array.from(files))
  target.value = ''
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
  dragOver.value = true
}

function onDragLeave() {
  dragOver.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  dragOver.value = false
  const files = e.dataTransfer?.files
  if (!files || files.length === 0) return
  processFiles(Array.from(files))
}

function validateFile(file: File): string | null {
  const allowedExts = ['jpg', 'jpeg', 'png', 'webp']
  const ext = file.name.split('.').pop()?.toLowerCase()
  if (!ext || !allowedExts.includes(ext)) {
    return `"${file.name}" 格式不支持，仅支持 jpg/png/webp`
  }
  if (file.size > maxSizeBytes.value) {
    return `"${file.name}" 超过 ${props.maxSizeMB ?? 5}MB 限制`
  }
  return null
}

async function processFiles(files: File[]) {
  errorMsg.value = ''

  // Validate
  const remaining = maxCount.value - props.modelValue.length
  if (remaining <= 0) {
    errorMsg.value = `最多上传 ${maxCount.value} 张图片`
    return
  }

  const toUpload = files.slice(0, remaining)
  for (const f of toUpload) {
    const err = validateFile(f)
    if (err) {
      errorMsg.value = err
      return
    }
  }

  // Upload via store
  uploading.value = true
  uploadProgress.value = 0
  try {
    const { useCampusHubStore } = await import('@/stores/campusHub')
    const store = useCampusHubStore()

    // Simulate progress (real progress from fetch would need XMLHttpRequest)
    const progressInterval = setInterval(() => {
      if (uploadProgress.value < 90) {
        uploadProgress.value += 10
      }
    }, 150)

    const urls = await store.uploadImages(toUpload)
    clearInterval(progressInterval)
    uploadProgress.value = 100

    emit('update:modelValue', [...props.modelValue, ...urls])
  } catch (e: any) {
    errorMsg.value = e.message || '上传失败'
  } finally {
    uploading.value = false
    setTimeout(() => { uploadProgress.value = 0 }, 500)
  }
}

function removeImage(index: number) {
  const newVal = [...props.modelValue]
  newVal.splice(index, 1)
  emit('update:modelValue', newVal)
}
</script>

<template>
  <div class="image-uploader">
    <div
      class="upload-zone"
      :class="{ 'drag-over': dragOver }"
      @click="triggerFileInput"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
      <input
        ref="fileInput"
        type="file"
        multiple
        accept="image/jpeg,image/png,image/webp"
        style="display: none"
        @change="handleFileChange"
      />
      <div v-if="uploading" class="upload-progress">
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: uploadProgress + '%' }"></div>
        </div>
        <span class="progress-text">上传中 {{ uploadProgress }}%</span>
      </div>
      <div v-else class="upload-hint">
        <span class="upload-icon">📷</span>
        <span>点击上传或拖拽图片到此处</span>
        <span class="upload-limit">支持 jpg/png/webp，单张 ≤{{ maxSizeMB ?? 5 }}MB，最多 {{ maxCount }} 张</span>
      </div>
    </div>

    <p v-if="errorMsg" class="upload-error">{{ errorMsg }}</p>

    <!-- Preview grid -->
    <div v-if="modelValue.length > 0" class="preview-grid">
      <div v-for="(url, index) in modelValue" :key="url" class="preview-item">
        <img :src="url" alt="预览图片" class="preview-img" />
        <button type="button" class="remove-btn" @click.stop="removeImage(index)" title="移除图片">×</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.image-uploader {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.upload-zone {
  border: 2px dashed rgba(0, 0, 0, 0.16);
  border-radius: 16px;
  padding: 32px 16px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
  background: rgba(255, 255, 255, 0.5);
}

.upload-zone:hover,
.upload-zone.drag-over {
  border-color: var(--accent, #1f5f53);
  background: rgba(31, 95, 83, 0.04);
}

.upload-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--text-weak, #888);
  font-size: 14px;
}

.upload-icon {
  font-size: 32px;
}

.upload-limit {
  font-size: 12px;
  color: var(--text-weak, #aaa);
}

.upload-progress {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.progress-bar {
  width: 80%;
  height: 8px;
  background: rgba(0, 0, 0, 0.08);
  border-radius: 4px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #2d8a4e, #1f5f53);
  border-radius: 4px;
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 13px;
  color: var(--accent, #1f5f53);
  font-weight: 600;
}

.upload-error {
  color: var(--danger, #b54747);
  font-size: 13px;
  margin: 0;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
  gap: 8px;
}

.preview-item {
  position: relative;
  border-radius: 10px;
  overflow: hidden;
  aspect-ratio: 1;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.preview-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.remove-btn {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: none;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}

.remove-btn:hover {
  background: var(--danger, #b54747);
}
</style>
