<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatMoney, formatRelativeTime, formatScore, formatUserRole, formatUserStatus } from '@/utils/format'
import { handleError } from '@/utils/errorHandler'

const store = useCampusHubStore()
const router = useRouter()

const showChangePassword = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const changePasswordMessage = ref('')
const changePasswordError = ref('')
const changing = ref(false)

// Avatar upload
const uploadingAvatar = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function triggerAvatarUpload() {
  fileInput.value?.click()
}

async function handleAvatarChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  target.value = ''

  const allowedExts = ['jpg', 'jpeg', 'png', 'webp']
  const ext = file.name.split('.').pop()?.toLowerCase()
  if (!ext || !allowedExts.includes(ext)) {
    alert('头像仅支持 jpg/png/webp 格式')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    alert('头像大小不能超过 5MB')
    return
  }

  uploadingAvatar.value = true
  try {
    const { useCampusHubStore } = await import('@/stores/campusHub')
    const store = useCampusHubStore()
    const urls = await store.uploadImages([file])
    if (urls.length > 0) {
      await store.updateProfile({ nickname: store.currentUser?.nickname ?? '', avatarUrl: urls[0] })
    }
  } catch (e: any) {
    alert(e.message || '头像上传失败')
  } finally {
    uploadingAvatar.value = false
  }
}

const creditLevel = computed(() => {
  const score = store.currentUser?.creditScore ?? 0
  if (score >= 95) return '金牌助教'
  if (score >= 85) return '银牌助教'
  return '成长中'
})

const reviewsReceived = computed(() =>
  store.currentUserReviews.filter((r) => r.targetId === store.currentUserId)
)

const availableBalance = computed(() => {
  const balance = store.currentUser?.balance ?? 0
  const frozen = store.currentUser?.frozenBalance ?? 0
  return Math.max(0, balance - frozen)
})

const reviewsGiven = computed(() =>
  store.currentUserReviews.filter((r) => r.reviewerId === store.currentUserId)
)

function getReviewOrderTitle(orderId: string): string {
  const order = store.getOrderById(orderId)
  return order?.demandTitle ?? '未知任务'
}

function navigateToOrder(orderId: string): void {
  if (orderId) {
    router.push(`/orders/${orderId}`)
  }
}

function openEditPage(): void {
  router.push('/profile/edit')
}

function openChangePassword(): void {
  showChangePassword.value = true
  oldPassword.value = ''
  newPassword.value = ''
  changePasswordMessage.value = ''
  changePasswordError.value = ''
}

function closeChangePassword(): void {
  showChangePassword.value = false
}

async function submitChangePassword(): Promise<void> {
  changePasswordMessage.value = ''
  changePasswordError.value = ''
  if (!oldPassword.value || !newPassword.value) {
    changePasswordError.value = '请填写当前密码和新密码'
    return
  }
  if (newPassword.value.length < 6) {
    changePasswordError.value = '新密码至少需要 6 个字符'
    return
  }
  changing.value = true
  try {
    await store.changePassword(oldPassword.value, newPassword.value)
    changePasswordMessage.value = '密码已修改成功'
    setTimeout(closeChangePassword, 1200)
  } catch (e) {
    changePasswordError.value = handleError(e, '修改密码失败')
  } finally {
    changing.value = false
  }
}

function logout(): void {
  store.logout()
  router.push('/auth')
}

onMounted(() => {
  void store.fetchProfile()
  void store.fetchCurrentUserReviews()
  void store.fetchOrders()
})
</script>

<template>
  <div>
    <div v-if="store.currentUser" class="page-grid">
    <section class="panel">
      <div class="avatar-row">
        <div class="avatar-upload-wrap" @click="triggerAvatarUpload" title="点击更换头像">
          <img :src="store.currentUser.avatarUrl" :alt="store.currentUser.nickname" class="avatar large avatar-clickable" />
          <div class="avatar-overlay">
            <span v-if="uploadingAvatar">上传中...</span>
            <span v-else>📷 更换头像</span>
          </div>
        </div>
        <input
          ref="fileInput"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          style="display: none"
          @change="handleAvatarChange"
        />
        <div class="profile-header">
          <div class="profile-header-top">
            <div>
              <p class="eyebrow">个人中心</p>
              <h1 class="page-title">{{ store.currentUser.nickname }}</h1>
            </div>
            <button type="button" class="button primary profile-edit-btn" @click="openEditPage">修改个人信息</button>
            <button type="button" class="button secondary profile-edit-btn" @click="openChangePassword">修改密码</button>
          </div>
          <p class="page-summary">学号：{{ store.currentUser.studentId }}</p>
          <div class="stats-row" style="gap:12px;">
            <div class="meta">当前身份：<strong>{{ formatUserRole(store.currentUser.role) }}</strong></div>
            <div class="meta">当前状态：<strong>{{ formatUserStatus(store.currentUser.status) }}</strong></div>
          </div>
        </div>
      </div>

      <div class="mini-grid">
        <div class="mini-stat">
          <span class="subtle">信用分</span>
          <strong>{{ formatScore(store.currentUser.creditScore) }}</strong>
        </div>
        <div class="mini-stat">
          <span class="subtle">可用余额</span>
          <strong>{{ formatMoney(availableBalance) }}</strong>
        </div>
        <div class="mini-stat">
          <span class="subtle">冻结金额</span>
          <strong>{{ formatMoney(store.currentUser.frozenBalance) }}</strong>
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

    <!-- 功能入口已移除，退出登录按钮已移动到页面底部 -->

    <!-- 别人对我的评价 -->
    <section class="panel">
      <p class="eyebrow">评价列表</p>
      <h2 class="section-title">别人对我的评价</h2>

      <div v-if="reviewsReceived.length" class="review-list">
        <div v-for="review in reviewsReceived" :key="review.id" class="review-item" @click="navigateToOrder(review.orderId)">
          <div class="review-item-header">
            <span class="chip is-success">{{ review.rating }} 星</span>
            <span class="review-order-title">{{ getReviewOrderTitle(review.orderId) }}</span>
            <span class="meta">{{ formatRelativeTime(review.createdAt) }}</span>
          </div>
          <div class="review-item-body">
            <span class="review-partner">{{ review.reviewerName }}</span>
            <p class="review-comment">{{ review.comment || '暂无评价内容' }}</p>
          </div>
        </div>
      </div>

      <div v-else class="empty-state">
        <strong>暂无收到的评价</strong>
      </div>
    </section>

    <!-- 我对别人的评价 -->
    <section class="panel">
      <h2 class="section-title">我对别人的评价</h2>

      <div v-if="reviewsGiven.length" class="review-list">
        <div v-for="review in reviewsGiven" :key="review.id" class="review-item" @click="navigateToOrder(review.orderId)">
          <div class="review-item-header">
            <span class="chip is-success">{{ review.rating }} 星</span>
            <span class="review-order-title">{{ getReviewOrderTitle(review.orderId) }}</span>
            <span class="meta">{{ formatRelativeTime(review.createdAt) }}</span>
          </div>
          <div class="review-item-body">
            <span class="review-partner">{{ review.targetName }}</span>
            <p class="review-comment">{{ review.comment || '暂无评价内容' }}</p>
          </div>
        </div>
      </div>

      <div v-else class="empty-state">
        <strong>暂无发出的评价</strong>
      </div>
    </section>

    <!-- 页面底部：退出登录（横向全宽样式） -->
    <section class="panel" style="margin-top: 16px;">
      <div>
        <button type="button" class="button primary" style="width:100%;" @click="logout">退出登录</button>
      </div>
    </section>
  </div>

  <div v-else class="empty-state">
    <strong>请先登录</strong>
    <p>前往认证页后再查看个人中心。</p>
    <button type="button" class="button primary" @click="router.push('/auth')">去认证页</button>
  </div>

  <!-- 修改密码弹窗 -->
  <Teleport to="body">
    <div v-if="showChangePassword" class="modal-backdrop" @click.self="closeChangePassword">
      <div class="modal-card panel">
        <div class="modal-head">
          <h3 class="section-title">修改密码</h3>
          <button type="button" class="button secondary" @click="closeChangePassword">关闭</button>
        </div>
        <div class="field">
          <label>当前密码</label>
          <input v-model="oldPassword" type="password" placeholder="请输入当前密码" />
        </div>
        <div class="field">
          <label>新密码</label>
          <input v-model="newPassword" type="password" placeholder="至少 6 个字符" />
        </div>
        <p v-if="changePasswordMessage" class="hero-badge">{{ changePasswordMessage }}</p>
        <p v-if="changePasswordError" class="hero-badge" style="background: rgba(181,71,71,0.14);color:var(--danger)">{{ changePasswordError }}</p>
        <div class="card-actions" style="justify-content:flex-end">
          <button type="button" class="button secondary" @click="closeChangePassword">取消</button>
          <button type="button" class="button primary" :disabled="changing" @click="submitChangePassword">确认修改</button>
        </div>
      </div>
    </div>
  </Teleport>
  </div>
</template>

<style scoped>
.avatar-upload-wrap {
  position: relative;
  cursor: pointer;
  border-radius: 50%;
  overflow: hidden;
}

.avatar-clickable {
  transition: filter 0.2s ease;
}

.avatar-upload-wrap:hover .avatar-clickable {
  filter: brightness(0.7);
}

.avatar-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.35);
  opacity: 0;
  transition: opacity 0.2s ease;
  border-radius: 50%;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  pointer-events: none;
}

.avatar-upload-wrap:hover .avatar-overlay {
  opacity: 1;
}
</style>