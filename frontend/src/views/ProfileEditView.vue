<script setup lang="ts">
import { onMounted, reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { validateNickname, validateAvatarUrl } from '@/utils/validators'
import { handleError } from '@/utils/errorHandler'
import { useConfirm } from '@/composables/useDialog'
import AvatarCropper from '@/components/AvatarCropper.vue'

const store = useCampusHubStore()
const router = useRouter()
const saving = ref(false)
const message = ref('')
const error = ref('')
const fieldErrors = reactive({ nickname: '', avatarUrl: '' })

const isValid = computed(() => !fieldErrors.nickname && !fieldErrors.avatarUrl && profileForm.nickname.trim().length >= 2)
const profileForm = reactive({
  nickname: store.currentUser?.nickname ?? '',
  avatarUrl: store.currentUser?.avatarUrl ?? ''
})

async function save(): Promise<void> {
  // run local validations
  fieldErrors.nickname = validateNickname(profileForm.nickname)
  fieldErrors.avatarUrl = validateAvatarUrl(profileForm.avatarUrl)

  if (!isValid.value) {
    error.value = '请修正表单错误后再保存'
    return
  }

  if (!await useConfirm('确认保存', '确认保存个人信息？')) return

  saving.value = true
  message.value = ''
  error.value = ''
  try {
    await store.updateProfile(profileForm)
    message.value = '资料已更新'
    // 0.8s 后返回个人页
    setTimeout(() => {
      router.push('/profile')
    }, 800)
  } catch (saveError) {
    error.value = handleError(saveError, '保存失败，请检查输入后重试')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void store.fetchProfile()
})
</script>

<template>
  <div>
    <div v-if="store.currentUser" class="page-grid">
    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">编辑资料</p>
          <h1 class="page-title">修改个人信息</h1>
          <p class="page-summary">更新昵称和头像。</p>
        </div>
      </div>

      <div class="form-grid two-column">
        <div class="field" style="grid-column: 1 / -1;">
          <label for="nickname">昵称</label>
          <input id="nickname" v-model="profileForm.nickname" @input="fieldErrors.nickname = ''" />
          <p v-if="fieldErrors.nickname" class="input-help" style="color: var(--danger)">{{ fieldErrors.nickname }}</p>
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label>头像</label>
          <AvatarCropper v-model="profileForm.avatarUrl" :size="80" />
        </div>
      </div>

      <div class="card-actions" style="margin-top: 16px;">
        <button type="button" class="button primary" :disabled="saving || !isValid" @click="save">保存</button>
        <button type="button" class="button secondary" @click="router.push('/profile')">返回</button>
      </div>
      <p v-if="message" class="hero-badge">{{ message }}</p>
      <p v-if="error" class="hero-badge" style="background: rgba(181, 71, 71, 0.14); color: var(--danger)">{{ error }}</p>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>请先登录</strong>
  </div>
  </div>
</template>
