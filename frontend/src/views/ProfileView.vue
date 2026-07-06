<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useCampusHubStore } from '@/stores/campusHub'
import { formatMoney, formatRelativeTime, formatScore, formatUserRole, formatUserStatus } from '@/utils/format'
import { handleError } from '@/utils/errorHandler'
import AvatarCropper from '@/components/AvatarCropper.vue'

const store = useCampusHubStore()
const router = useRouter()

const showChangePassword = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const changePasswordMessage = ref('')
const changePasswordError = ref('')
const changing = ref(false)

// 头像更新（AvatarCropper 裁剪上传后回调）
async function handleAvatarUpdate(url: string): Promise<void> {
  try {
    await store.updateProfile({
      nickname: store.currentUser?.nickname ?? '',
      avatarUrl: url
    })
  } catch (e) {
    alert(handleError(e, '头像更新失败'))
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
        <AvatarCropper
          :model-value="store.currentUser.avatarUrl"
          :size="72"
          @update:model-value="handleAvatarUpdate"
        />
        <div class="profile-header">
          <div class="profile-header-top">
            <div>
              <p class="eyebrow">个人中心</p>
              <h1 class="page-title">{{ store.currentUser.nickname }}</h1>
            </div>
            <div class="profile-actions">
              <button type="button" class="button profile-edit-btn" @click="openEditPage">修改个人信息</button>
              <button type="button" class="button profile-change-pwd-btn" @click="openChangePassword">修改密码</button>
            </div>
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
          <button type="button" class="modal-close" @click="closeChangePassword" title="关闭" aria-label="关闭"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
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
.profile-header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex: 1;
}

.profile-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.profile-actions .button {
  height: 38px;
  min-width: 120px;
  padding: 0 18px;
  justify-content: center;
  align-items: center;
  font-family: var(--sans);
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  border: none;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  transition: background 0.2s ease, box-shadow 0.2s ease;
}

.profile-edit-btn {
  background: linear-gradient(135deg, #64b5f6, #1e88e5);
}

.profile-edit-btn:hover {
  background: linear-gradient(135deg, #42a5f5, #1565c0);
  box-shadow: 0 4px 12px rgba(30, 136, 229, 0.35);
}

.profile-change-pwd-btn {
  background: linear-gradient(135deg, #ef9a9a, #e53935);
}

.profile-change-pwd-btn:hover {
  background: linear-gradient(135deg, #e57373, #c62828);
  box-shadow: 0 4px 12px rgba(229, 57, 53, 0.35);
}

/* AvatarCropper 组件自带样式，此处无需额外头像上传样式 */
</style>