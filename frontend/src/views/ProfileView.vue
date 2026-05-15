<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatScore, formatUserRole, formatUserStatus } from '@/utils/format'

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

const creditLevel = computed(() => {
  const score = store.currentUser?.creditScore ?? 0
  if (score >= 95) return '金牌助教'
  if (score >= 85) return '银牌助教'
  return '成长中'
})

const maskedPhone = computed(() => {
  const phone = store.currentUser?.phone ?? store.currentUser?.studentId ?? ''
  return phone ? `${String(phone).slice(0, 3)}****${String(phone).slice(-4)}` : '138****0000'
})

async function saveProfile(): Promise<void> {
  saving.value = true
  try {
    await store.updateProfile(profileForm)
  } finally {
    saving.value = false
  }
}

function openEditPage(): void {
  router.push('/profile/edit')
}

function logout(): void {
  store.logout()
  router.push('/auth')
}

onMounted(() => {
  void store.fetchProfile()
})
</script>

<template>
  <div v-if="store.currentUser" class="page-grid">
    <section class="panel">
      <div class="avatar-row">
        <img :src="store.currentUser.avatarUrl" :alt="store.currentUser.nickname" class="avatar large" />
        <div>
          <p class="eyebrow">个人中心</p>
          <h1 class="page-title">{{ store.currentUser.nickname }}</h1>
          <p class="page-summary">{{ store.currentUser.studentId }} · {{ maskedPhone }}</p>
          <div class="stats-row">
            <span class="chip is-neutral">{{ formatUserRole(store.currentUser.role) }}</span>
            <span class="chip is-neutral">{{ formatUserStatus(store.currentUser.status) }}</span>
          </div>
          <button type="button" class="button secondary" @click="openEditPage">编辑资料</button>
        </div>
      </div>

      <div class="mini-grid">
        <div class="mini-stat">
          <span class="subtle">信用分</span>
          <strong>{{ formatScore(store.currentUser.creditScore) }}</strong>
        </div>
        <div class="mini-stat">
          <span class="subtle">信用等级</span>
          <strong>{{ creditLevel }}</strong>
        </div>
        <div class="mini-stat">
          <span class="subtle">已完成订单</span>
          <strong>{{ store.currentUserOrders.filter((order) => order.status === 'COMPLETED').length }}</strong>
        </div>
      </div>
    </section>

    <section class="panel">
      <p class="eyebrow">功能入口</p>
      <div class="section-grid">
        <button type="button" class="button secondary" @click="router.push('/orders')">我的订单</button>
        <button type="button" class="button secondary" @click="router.push('/notifications')">消息通知</button>
        <button v-if="store.currentUser.role === 'ADMIN'" type="button" class="button secondary" @click="router.push('/admin')">管理后台</button>
        <button type="button" class="button primary" @click="logout">退出登录</button>
      </div>

      <div class="hero-badge" style="margin-top: 16px;">v1.0.0</div>
    </section>

    <section class="panel">
      <div class="page-head">
        <div>
          <p class="eyebrow">资料编辑</p>
          <h2 class="section-title">修改头像与昵称</h2>
        </div>
      </div>

      <div class="form-grid two-column">
        <div class="field">
          <label for="profile-nickname">昵称</label>
          <input id="profile-nickname" v-model="profileForm.nickname" />
        </div>
        <div class="field">
          <label for="profile-avatar">头像链接</label>
          <input id="profile-avatar" v-model="profileForm.avatarUrl" />
        </div>
        <div class="field" style="grid-column: 1 / -1;">
          <label for="profile-avatar-file">本地上传头像</label>
          <input
            id="profile-avatar-file"
            ref="avatarFileInput"
            type="file"
            accept="image/*"
            @change="onAvatarFileSelected"
          />
          <span class="input-help">选择本地图片后会自动转成预览地址，保存后即作为头像使用。</span>
        </div>
      </div>

      <div class="card-actions">
        <button type="button" class="button primary" :disabled="saving" @click="saveProfile">保存资料</button>
      </div>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>请先登录</strong>
    <p>前往认证页后再查看个人中心。</p>
    <button type="button" class="button primary" @click="router.push('/auth')">去认证页</button>
  </div>
</template>