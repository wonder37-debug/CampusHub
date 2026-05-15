<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'

const store = useCampusHubStore()
const router = useRouter()
const saving = ref(false)
const profileForm = reactive({
  nickname: store.currentUser?.nickname ?? '',
  avatarUrl: store.currentUser?.avatarUrl ?? ''
})

function onAvatarFileSelected(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  const reader = new FileReader()
  reader.onload = () => {
    profileForm.avatarUrl = String(reader.result || '')
  }
  reader.readAsDataURL(file)
}

async function save(): Promise<void> {
  saving.value = true
  try {
    await store.updateProfile(profileForm)
    router.push('/profile')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void store.fetchProfile()
})
</script>

<template>
  <div v-if="store.currentUser" class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">编辑资料</p>
          <h1 class="page-title">修改个人信息</h1>
          <p class="page-summary">更新昵称和头像链接。</p>
        </div>
      </div>

      <div class="form-grid two-column">
        <div class="field">
          <label for="nickname">昵称</label>
          <input id="nickname" v-model="profileForm.nickname" />
        </div>
        <div class="field">
          <label for="avatar">头像链接</label>
          <input id="avatar" v-model="profileForm.avatarUrl" />
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="avatar-file">本地上传头像</label>
          <input id="avatar-file" type="file" accept="image/*" @change="onAvatarFileSelected" />
          <span class="input-help">上传后会自动写入头像链接字段。</span>
        </div>
      </div>

      <div class="card-actions" style="margin-top: 16px;">
        <button type="button" class="button primary" :disabled="saving" @click="save">保存</button>
        <button type="button" class="button secondary" @click="router.push('/profile')">返回</button>
      </div>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>请先登录</strong>
  </div>
</template>
