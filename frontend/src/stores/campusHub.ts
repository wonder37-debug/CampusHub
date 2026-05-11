import { defineStore } from 'pinia'

import type {
  AccountRecord,
  AuthFormInput,
  CategoryStat,
  DashboardSummary,
  DemandCategory,
  DemandFormInput,
  DemandRecord,
  DemandStatus,
  EmailVerificationRecord,
  NotificationRecord,
  OrderRecord,
  ProfilePatchInput,
  PublicUser,
  ReviewRecord
} from '@/types/campushub'

function buildAvatar(seed: string): string {
  return `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(seed)}`
}

function buildEmail(seed: string): string {
  const normalized = seed.toLowerCase().replace(/[^a-z0-9]+/g, '.').replace(/^\.+|\.+$/g, '')
  return `${normalized || 'user'}@campushub.edu.cn`
}

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

function generateVerificationCode(): string {
  return `${Math.floor(100000 + Math.random() * 900000)}`
}

function now(offsetMinutes = 0): string {
  return new Date(Date.now() + offsetMinutes * 60_000).toISOString()
}

function cloneUser(account: AccountRecord): PublicUser {
  const { password: _, ...user } = account
  return { ...user }
}

function createAccounts(): AccountRecord[] {
  return [
    {
      id: 'u-admin',
      studentId: 'admin01',
      email: buildEmail('campus admin'),
      password: 'admin123',
      nickname: '校务管理员',
      college: '信息学院',
      phone: '13800000001',
      creditScore: 99,
      role: 'admin',
      avatarUrl: buildAvatar('Campus Admin')
    },
    {
      id: 'u-chen',
      studentId: '20260001',
      email: buildEmail('chen chen'),
      password: 'campus123',
      nickname: '陈晨',
      college: '计算机学院',
      phone: '13800000002',
      creditScore: 94,
      role: 'student',
      avatarUrl: buildAvatar('陈晨')
    },
    {
      id: 'u-lin',
      studentId: '20260002',
      email: buildEmail('lin xia'),
      password: 'campus123',
      nickname: '林夏',
      college: '商学院',
      phone: '13800000003',
      creditScore: 88,
      role: 'student',
      avatarUrl: buildAvatar('林夏')
    },
    {
      id: 'u-zhou',
      studentId: '20260003',
      email: buildEmail('zhou yang'),
      password: 'campus123',
      nickname: '周扬',
      college: '外国语学院',
      phone: '13800000004',
      creditScore: 90,
      role: 'student',
      avatarUrl: buildAvatar('周扬')
    }
  ]
}

function createDemands(accounts: AccountRecord[]): DemandRecord[] {
  const chen = accounts.find((account) => account.id === 'u-chen')
  const lin = accounts.find((account) => account.id === 'u-lin')
  const zhou = accounts.find((account) => account.id === 'u-zhou')

  return [
    {
      id: 'd-1001',
      title: '帮取快递并送到北区宿舍',
      description: '周五下午三点后可以取件，快递站距离宿舍步行约八分钟，希望顺路帮忙送到楼下。',
      category: '取快递',
      location: '北区快递站',
      startTime: now(-240),
      endTime: now(360),
      reward: 8,
      status: '开放中',
      approvalStatus: '已通过',
      publisherId: chen?.id ?? 'u-chen',
      publisherName: chen?.nickname ?? '陈晨',
      publisherAvatar: chen?.avatarUrl ?? buildAvatar('陈晨'),
      tags: ['近距离', '宿舍楼下'],
      createdAt: now(-300),
      distanceKm: 1.2
    },
    {
      id: 'd-1002',
      title: '高数作业思路互助',
      description: '一起梳理本周作业第 3、4 题的解题思路，接受线上语音沟通。',
      category: '学习辅导',
      location: '图书馆三楼',
      startTime: now(-180),
      endTime: now(180),
      reward: 20,
      status: '已接单',
      approvalStatus: '已通过',
      publisherId: lin?.id ?? 'u-lin',
      publisherName: lin?.nickname ?? '林夏',
      publisherAvatar: lin?.avatarUrl ?? buildAvatar('林夏'),
      tags: ['高数', '线上'],
      createdAt: now(-240),
      distanceKm: 0.8
    },
    {
      id: 'd-1003',
      title: '二手教材转让：Python 入门',
      description: '教材使用一学期，书页完整，适合大一新生入门，支持校园内面交。',
      category: '二手交易',
      location: '东区食堂门口',
      startTime: now(-120),
      endTime: now(720),
      reward: 35,
      status: '开放中',
      approvalStatus: '已通过',
      publisherId: zhou?.id ?? 'u-zhou',
      publisherName: zhou?.nickname ?? '周扬',
      publisherAvatar: zhou?.avatarUrl ?? buildAvatar('周扬'),
      tags: ['教材', '面交'],
      createdAt: now(-180),
      distanceKm: 2.4
    },
    {
      id: 'd-1004',
      title: '篮球赛临时组队，缺两名队员',
      description: '校内友谊赛周末开打，希望找两个跑动能力强、能打外线的同学。',
      category: '活动组队',
      location: '南区篮球场',
      startTime: now(60),
      endTime: now(240),
      reward: 0,
      status: '进行中',
      approvalStatus: '已通过',
      publisherId: chen?.id ?? 'u-chen',
      publisherName: chen?.nickname ?? '陈晨',
      publisherAvatar: chen?.avatarUrl ?? buildAvatar('陈晨'),
      tags: ['体育', '周末'],
      createdAt: now(-90),
      distanceKm: 1.8
    },
    {
      id: 'd-1005',
      title: '代买实验用品，需求待审核示例',
      description: '这是管理员审核队列中的示例需求，演示 P4 管理后台的审核流程。',
      category: '其他',
      location: '化学楼',
      startTime: now(120),
      endTime: now(420),
      reward: 12,
      status: '开放中',
      approvalStatus: '待审核',
      publisherId: zhou?.id ?? 'u-zhou',
      publisherName: zhou?.nickname ?? '周扬',
      publisherAvatar: zhou?.avatarUrl ?? buildAvatar('周扬'),
      tags: ['审核中', '后台'],
      createdAt: now(-60),
      distanceKm: 3.2
    }
  ]
}

function createOrders(): OrderRecord[] {
  return [
    {
      id: 'o-2001',
      demandId: 'd-1002',
      demandTitle: '高数作业思路互助',
      requesterId: 'u-lin',
      requesterName: '林夏',
      requesterAvatar: buildAvatar('林夏'),
      serviceProviderId: 'u-zhou',
      serviceProviderName: '周扬',
      serviceProviderAvatar: buildAvatar('周扬'),
      status: '进行中',
      note: '今晚八点前可以开始，想先看一下题目。',
      createdAt: now(-200),
      completedAt: '',
      timeline: [
        { at: now(-200), label: '订单创建' },
        { at: now(-150), label: '双方确认需求细节' },
        { at: now(-30), label: '进入进行中' }
      ]
    },
    {
      id: 'o-2002',
      demandId: 'd-1004',
      demandTitle: '篮球赛临时组队，缺两名队员',
      requesterId: 'u-chen',
      requesterName: '陈晨',
      requesterAvatar: buildAvatar('陈晨'),
      serviceProviderId: 'u-lin',
      serviceProviderName: '林夏',
      serviceProviderAvatar: buildAvatar('林夏'),
      status: '已完成',
      note: '同学之间协调时间，周末已完成比赛。',
      createdAt: now(-320),
      completedAt: now(-10),
      timeline: [
        { at: now(-320), label: '订单创建' },
        { at: now(-260), label: '开始执行' },
        { at: now(-10), label: '已完成' }
      ]
    }
  ]
}

function createReviews(): ReviewRecord[] {
  return [
    {
      id: 'r-3001',
      orderId: 'o-2002',
      reviewerId: 'u-chen',
      reviewerName: '陈晨',
      targetId: 'u-lin',
      targetName: '林夏',
      rating: 5,
      comment: '沟通很及时，比赛也非常顺利。',
      createdAt: now(-8)
    }
  ]
}

function createNotifications(): NotificationRecord[] {
  return [
    {
      id: 'n-4001',
      receiverId: 'u-lin',
      type: '订单',
      content: '你的需求“高数作业思路互助”已被周扬接单。',
      isRead: false,
      createdAt: now(-120),
      relatedId: 'o-2001'
    },
    {
      id: 'n-4002',
      receiverId: 'u-zhou',
      type: '评价',
      content: '陈晨刚刚给你提交了一条 5 星评价。',
      isRead: true,
      createdAt: now(-6),
      relatedId: 'r-3001'
    },
    {
      id: 'n-4003',
      receiverId: 'u-admin',
      type: '需求',
      content: '有一条需求正在等待管理员审核。',
      isRead: false,
      createdAt: now(-55),
      relatedId: 'd-1005'
    },
    {
      id: 'n-4004',
      receiverId: 'u-chen',
      type: '系统',
      content: 'CampusHub 演示数据已准备完成，可以切换不同身份查看流程。',
      isRead: true,
      createdAt: now(-500),
      relatedId: 'system'
    }
  ]
}

function nextId(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 9)}`
}

function recalculateCredit(accounts: AccountRecord[], targetId: string, reviews: ReviewRecord[]): void {
  const target = accounts.find((account) => account.id === targetId)
  if (!target) {
    return
  }

  const targetReviews = reviews.filter((review) => review.targetId === targetId)
  if (!targetReviews.length) {
    return
  }

  const average = targetReviews.reduce((sum, review) => sum + review.rating, 0) / targetReviews.length
  target.creditScore = Math.round(average * 20)
}

export const useCampusHubStore = defineStore('campusHub', {
  state: () => ({
    currentUserId: 'u-chen',
    token: 'demo-token',
    accounts: createAccounts(),
    demands: createDemands(createAccounts()),
    orders: createOrders(),
    reviews: createReviews(),
    notifications: createNotifications(),
    verificationCodes: {} as Record<string, EmailVerificationRecord>,
    appMessage: '校园互助平台展示层已启用演示数据仓库。'
  }),

  getters: {
    currentUser(state): PublicUser | null {
      const account = state.accounts.find((item) => item.id === state.currentUserId)
      return account ? cloneUser(account) : null
    },

    accountOptions(state): PublicUser[] {
      return state.accounts.map((account) => cloneUser(account))
    },

    unreadNotificationCount(state): number {
      return state.notifications.filter((notification) => notification.receiverId === state.currentUserId && !notification.isRead).length
    },

    currentUserNotifications(state): NotificationRecord[] {
      return state.notifications
        .filter((notification) => notification.receiverId === state.currentUserId)
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    currentUserDemands(state): DemandRecord[] {
      return state.demands
        .filter((demand) => demand.publisherId === state.currentUserId)
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    currentUserOrders(state): OrderRecord[] {
      return state.orders
        .filter((order) => order.requesterId === state.currentUserId || order.serviceProviderId === state.currentUserId)
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    currentUserReviews(state): ReviewRecord[] {
      return state.reviews
        .filter((review) => review.targetId === state.currentUserId || review.reviewerId === state.currentUserId)
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    pendingApprovals(state): DemandRecord[] {
      return state.demands
        .filter((demand) => demand.approvalStatus === '待审核')
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    },

    categoryStats(state): CategoryStat[] {
      return ['取快递', '学习辅导', '二手交易', '活动组队', '其他'].map((category) => ({
        category: category as DemandCategory,
        total: state.demands.filter((demand) => demand.category === category).length
      }))
    },

    dashboardSummary(state): DashboardSummary {
      const averageCredit = Math.round(state.accounts.reduce((sum, account) => sum + account.creditScore, 0) / state.accounts.length)

      return {
        openDemands: state.demands.filter((demand) => demand.status === '开放中').length,
        activeOrders: state.orders.filter((order) => order.status === '进行中' || order.status === '已接单').length,
        unreadNotifications: state.notifications.filter((notification) => notification.receiverId === state.currentUserId && !notification.isRead).length,
        pendingApprovals: state.demands.filter((demand) => demand.approvalStatus === '待审核').length,
        averageCredit
      }
    },

    popularCategories(state): DemandCategory[] {
      const ordered = [...state.demands].sort((left, right) => {
        const leftScore = left.approvalStatus === '已通过' ? 1 : 0
        const rightScore = right.approvalStatus === '已通过' ? 1 : 0
        return rightScore - leftScore || right.createdAt.localeCompare(left.createdAt)
      })

      return ordered.slice(0, 4).map((demand) => demand.category)
    }
  },

  actions: {
    getDemandById(demandId: string): DemandRecord | undefined {
      return this.demands.find((demand) => demand.id === demandId)
    },

    getOrderById(orderId: string): OrderRecord | undefined {
      return this.orders.find((order) => order.id === orderId)
    },

    getUserById(userId: string): PublicUser | undefined {
      const account = this.accounts.find((item) => item.id === userId)
      return account ? cloneUser(account) : undefined
    },

    switchAccount(userId: string): void {
      const account = this.accounts.find((item) => item.id === userId)
      if (!account) {
        throw new Error('未找到可切换的演示账号')
      }

      this.currentUserId = account.id
      this.token = `demo-${account.studentId}`
    },

    logout(): void {
      this.currentUserId = ''
      this.token = ''
    },

    login(form: AuthFormInput): PublicUser {
      const account = this.accounts.find((item) => item.studentId === form.studentId.trim() && item.password === form.password)

      if (!account) {
        throw new Error('学号或密码不正确')
      }

      this.currentUserId = account.id
      this.token = `demo-${account.studentId}`
      return cloneUser(account)
    },

    sendRegistrationCode(email: string): string {
      const normalizedEmail = normalizeEmail(email)

      if (!normalizedEmail || !normalizedEmail.includes('@')) {
        throw new Error('请输入有效的邮箱地址')
      }

      const code = generateVerificationCode()
      this.verificationCodes[normalizedEmail] = {
        code,
        email: normalizedEmail,
        expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
        sender: 'noreply@campushub.edu.cn'
      }

      return code
    },

    register(form: AuthFormInput): PublicUser {
      const studentId = form.studentId.trim()
      const email = normalizeEmail(form.email ?? '')
      const verificationCode = form.verificationCode?.trim() ?? ''

      if (!email) {
        throw new Error('请输入邮箱地址')
      }

      if (!verificationCode) {
        throw new Error('请输入邮箱验证码')
      }

      if (this.accounts.some((account) => account.studentId === studentId)) {
        throw new Error('该学号已经被注册')
      }

      if (this.accounts.some((account) => account.email === email)) {
        throw new Error('该邮箱已经被注册')
      }

      const verification = this.verificationCodes[email]
      if (!verification) {
        throw new Error('请先发送邮箱验证码')
      }

      if (verification.code !== verificationCode) {
        throw new Error('验证码不正确')
      }

      if (new Date(verification.expiresAt).getTime() < Date.now()) {
        throw new Error('验证码已过期，请重新发送')
      }

      const nickname = form.nickname?.trim() ?? ''
      const college = form.college?.trim() ?? ''
      const phone = form.phone?.trim() ?? ''
      const avatarUrl = form.avatarUrl?.trim() ?? ''

      const account: AccountRecord = {
        id: nextId('u'),
        studentId,
        email,
        password: form.password,
        nickname: nickname || studentId,
        college: college || '未填写学院',
        phone: phone || '未填写',
        creditScore: 90,
        role: 'student',
        avatarUrl: avatarUrl || buildAvatar(nickname || studentId)
      }

      this.accounts.unshift(account)
      this.currentUserId = account.id
      this.token = `demo-${account.studentId}`
      delete this.verificationCodes[email]
      return cloneUser(account)
    },

    updateProfile(form: ProfilePatchInput): PublicUser {
      const account = this.accounts.find((item) => item.id === this.currentUserId)
      if (!account) {
        throw new Error('请先登录后再编辑资料')
      }

      account.nickname = form.nickname.trim() || account.nickname
      account.college = form.college.trim() || account.college
      account.phone = form.phone.trim() || account.phone
      account.avatarUrl = form.avatarUrl.trim() || account.avatarUrl
      return cloneUser(account)
    },

    createDemand(form: DemandFormInput): DemandRecord {
      const currentUser = this.accounts.find((account) => account.id === this.currentUserId)
      if (!currentUser) {
        throw new Error('请先登录后再发布需求')
      }

      const reward = Number(form.reward || 0)
      const demand: DemandRecord = {
        id: nextId('d'),
        title: form.title.trim(),
        description: form.description.trim(),
        category: (form.category || '其他') as DemandCategory,
        location: form.location.trim() || '校园内',
        startTime: form.startTime || now(),
        endTime: form.endTime || now(180),
        reward: Number.isNaN(reward) ? 0 : reward,
        status: '开放中' satisfies DemandStatus,
        approvalStatus: currentUser.role === 'admin' ? '已通过' : '待审核',
        publisherId: currentUser.id,
        publisherName: currentUser.nickname,
        publisherAvatar: currentUser.avatarUrl,
        tags: form.tags
          .split(/[，,]/)
          .map((tag) => tag.trim())
          .filter(Boolean),
        createdAt: now(),
        distanceKm: Number((Math.random() * 3 + 0.4).toFixed(1))
      }

      this.demands.unshift(demand)
      this.notifications.unshift({
        id: nextId('n'),
        receiverId: currentUser.id,
        type: '需求',
        content: `你刚刚发布了需求“${demand.title}”。`,
        isRead: false,
        createdAt: now(),
        relatedId: demand.id
      })

      return demand
    },

    approveDemand(demandId: string, approved: boolean): DemandRecord {
      const demand = this.demands.find((item) => item.id === demandId)
      if (!demand) {
        throw new Error('未找到待审核需求')
      }

      demand.approvalStatus = approved ? '已通过' : '已拒绝'
      if (!approved) {
        demand.status = '已关闭'
      }

      this.notifications.unshift({
        id: nextId('n'),
        receiverId: demand.publisherId,
        type: '系统',
        content: approved ? `你的需求“${demand.title}”已通过审核。` : `你的需求“${demand.title}”未通过审核。`,
        isRead: false,
        createdAt: now(),
        relatedId: demand.id
      })

      return demand
    },

    acceptDemand(demandId: string, note = ''): OrderRecord {
      const currentUser = this.accounts.find((account) => account.id === this.currentUserId)
      const demand = this.demands.find((item) => item.id === demandId)

      if (!currentUser) {
        throw new Error('请先登录后再接单')
      }

      if (!demand) {
        throw new Error('未找到需求')
      }

      if (demand.publisherId === currentUser.id) {
        throw new Error('不能接自己的需求')
      }

      if (demand.approvalStatus !== '已通过') {
        throw new Error('需求尚未通过审核，暂时不能接单')
      }

      if (demand.status !== '开放中') {
        throw new Error('该需求当前不可接单')
      }

      demand.status = '已接单'

      const order: OrderRecord = {
        id: nextId('o'),
        demandId: demand.id,
        demandTitle: demand.title,
        requesterId: demand.publisherId,
        requesterName: demand.publisherName,
        requesterAvatar: demand.publisherAvatar,
        serviceProviderId: currentUser.id,
        serviceProviderName: currentUser.nickname,
        serviceProviderAvatar: currentUser.avatarUrl,
        status: '已接单',
        note: note.trim(),
        createdAt: now(),
        completedAt: '',
        timeline: [
          { at: now(), label: '接单成功' },
          { at: now(5), label: '等待执行' }
        ]
      }

      this.orders.unshift(order)
      this.notifications.unshift(
        {
          id: nextId('n'),
          receiverId: demand.publisherId,
          type: '订单',
          content: `你的需求“${demand.title}”已被 ${currentUser.nickname} 接单。`,
          isRead: false,
          createdAt: now(),
          relatedId: order.id
        },
        {
          id: nextId('n'),
          receiverId: currentUser.id,
          type: '订单',
          content: `你已成功接下需求“${demand.title}”。`,
          isRead: false,
          createdAt: now(),
          relatedId: order.id
        }
      )

      return order
    },

    startOrder(orderId: string): OrderRecord {
      const order = this.orders.find((item) => item.id === orderId)
      if (!order) {
        throw new Error('未找到订单')
      }

      if (order.status !== '已接单') {
        throw new Error('只有已接单的订单可以开始执行')
      }

      order.status = '进行中'
      order.timeline.unshift({ at: now(), label: '开始执行' })
      this.notifications.unshift({
        id: nextId('n'),
        receiverId: order.requesterId,
        type: '订单',
        content: `你的订单“${order.demandTitle}”已进入进行中状态。`,
        isRead: false,
        createdAt: now(),
        relatedId: order.id
      })
      return order
    },

    completeOrder(orderId: string): OrderRecord {
      const order = this.orders.find((item) => item.id === orderId)
      if (!order) {
        throw new Error('未找到订单')
      }

      if (order.status !== '进行中' && order.status !== '已接单') {
        throw new Error('当前状态不能直接完成订单')
      }

      order.status = '已完成'
      order.completedAt = now()
      order.timeline.unshift({ at: now(), label: '确认完成' })
      this.notifications.unshift({
        id: nextId('n'),
        receiverId: order.requesterId,
        type: '订单',
        content: `你的订单“${order.demandTitle}”已经完成，可以进行评价。`,
        isRead: false,
        createdAt: now(),
        relatedId: order.id
      })
      return order
    },

    submitReview(orderId: string, rating: number, comment: string): ReviewRecord {
      const order = this.orders.find((item) => item.id === orderId)
      const reviewer = this.accounts.find((account) => account.id === this.currentUserId)

      if (!order) {
        throw new Error('未找到订单')
      }

      if (!reviewer) {
        throw new Error('请先登录后再提交评价')
      }

      if (rating < 1 || rating > 5) {
        throw new Error('评分必须在 1 到 5 之间')
      }

      const targetId = reviewer.id === order.requesterId ? order.serviceProviderId : order.requesterId
      const target = this.accounts.find((account) => account.id === targetId)
      if (!target) {
        throw new Error('未找到被评价用户')
      }

      const review: ReviewRecord = {
        id: nextId('r'),
        orderId,
        reviewerId: reviewer.id,
        reviewerName: reviewer.nickname,
        targetId,
        targetName: target.nickname,
        rating,
        comment: comment.trim(),
        createdAt: now()
      }

      this.reviews.unshift(review)
      recalculateCredit(this.accounts, targetId, this.reviews)
      this.notifications.unshift({
        id: nextId('n'),
        receiverId: targetId,
        type: '评价',
        content: `${reviewer.nickname} 给你提交了 ${rating} 星评价。`,
        isRead: false,
        createdAt: now(),
        relatedId: review.id
      })

      return review
    },

    markNotificationRead(notificationId: string): void {
      const notification = this.notifications.find((item) => item.id === notificationId)
      if (notification) {
        notification.isRead = true
      }
    },

    markAllNotificationsRead(): void {
      this.notifications
        .filter((notification) => notification.receiverId === this.currentUserId)
        .forEach((notification) => {
          notification.isRead = true
        })
    },

    resetDemoState(): void {
      this.accounts = createAccounts()
      this.demands = createDemands(this.accounts)
      this.orders = createOrders()
      this.reviews = createReviews()
      this.notifications = createNotifications()
      this.currentUserId = 'u-chen'
      this.token = 'demo-token'
    }
  }
})
