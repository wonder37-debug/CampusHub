<script setup lang="ts">
import { RouterLink } from 'vue-router'

const quickStart = [
  {
    icon: '📝',
    title: '注册与登录',
    desc: '使用校园邮箱（nju.edu.cn、smail.nju.edu.cn 等）注册，填写邮箱验证码完成验证；也可使用页面顶部的身份切换按钮快速体验不同角色。'
  },
  {
    icon: '🔍',
    title: '浏览与筛选需求',
    desc: '在首页查看需求列表，可按关键词、分类、校区、地点和时间范围筛选，并按时间、距离、报酬或推荐排序。'
  },
  {
    icon: '📢',
    title: '发布需求',
    desc: '点击右下角发布按钮，填写标题、描述、分类、校区、报酬等信息，支持匿名发布、图片上传，草稿会自动保存到本地。'
  },
  {
    icon: '🤝',
    title: '接单与执行',
    desc: '在需求详情页点击接单，订单进入「已接单」状态；开始执行后提交凭证图片，由发布者确认完成。'
  },
  {
    icon: '⭐',
    title: '评价与信用',
    desc: '订单完成后双方互相评价（1-5 星 + 评论），系统按历史权重动态计算信用分，达到 95+ 为金牌助教。'
  },
  {
    icon: '🔔',
    title: '消息通知',
    desc: '接单、状态变更、收到评价、审核结果等会以站内通知推送，可标记已读或全部已读，点击通知直达关联页面。'
  }
]

const faqs = [
  {
    q: '为什么我注册时收不到验证码？',
    a: '平台未配置 SMTP 时，验证码会自动输出到后端控制台日志，可在启动后端时查看；配置 SMTP 后会通过邮件发送。请确认邮箱域名在白名单内（nju.edu.cn、smail.nju.edu.cn、edu.cn）。'
  },
  {
    q: '悬赏金是如何扣付的？',
    a: '发布需求时悬赏金会被冻结，订单完成后转移给接单方余额，订单取消时解冻退回发布方。新用户初始余额为 100.00。'
  },
  {
    q: '订单出现争议怎么办？',
    a: '任意一方可在订单页发起仲裁，订单进入「仲裁中」状态后由管理员裁决（判定完成或取消），裁决结果会通知双方。'
  },
  {
    q: '订单多久会自动完成？',
    a: '接单方提交完成凭证后，若发布者 48 小时内未确认，订单将自动完成，避免长时间挂起。'
  },
  {
    q: '如何发布匿名需求？',
    a: '在发布需求时勾选「匿名发布」，系统会生成匿名识别码展示给其他用户，保护发布者身份。'
  },
  {
    q: '推荐列表是怎么排序的？',
    a: '新用户采用冷启动算法（40% 报酬 + 30% 紧急度 + 30% 新鲜度）；有接单历史后切换为热启动算法（50% 分类偏好 + 20% 报酬 + 15% 紧急度 + 15% 新鲜度）。'
  }
]

const contacts = [
  { label: '邮箱', value: 'campus-hub@nju.edu.cn' },
  { label: '地址', value: '南京大学信息化建设管理中心' },
  { label: '反馈渠道', value: '通过页面底部邮箱反馈问题或建议' }
]
</script>

<template>
  <div class="page-grid">
    <nav class="doc-back">
      <RouterLink to="/" class="back-link">← 返回首页</RouterLink>
    </nav>

    <article class="panel doc-hero">
      <p class="eyebrow">支持</p>
      <h1 class="page-title">🔗 帮助中心</h1>
      <p class="page-summary">
        在这里你可以快速了解 CampusHub 的使用方式，从注册登录到发布需求、接单协作、评价与仲裁，覆盖平台核心流程。如果本文未解决你的问题，可通过页面底部的联系方式与我们取得联系。
      </p>
    </article>

    <article class="panel">
      <p class="eyebrow">快速上手</p>
      <h2 class="section-title">核心流程一览</h2>
      <div class="section-grid three-column">
        <div v-for="item in quickStart" :key="item.title" class="list-card">
          <div class="doc-icon">{{ item.icon }}</div>
          <strong>{{ item.title }}</strong>
          <p>{{ item.desc }}</p>
        </div>
      </div>
    </article>

    <article class="panel">
      <p class="eyebrow">业务流程</p>
      <h2 class="section-title">从发布到完成的完整链路</h2>
      <div class="timeline">
        <div class="timeline-item">
          <span>第 1 步</span>
          <strong>发布需求 → 管理员审核</strong>
        </div>
        <div class="timeline-item">
          <span>第 2 步</span>
          <strong>公开浏览 → 接单</strong>
        </div>
        <div class="timeline-item">
          <span>第 3 步</span>
          <strong>开始执行 → 提交凭证</strong>
        </div>
        <div class="timeline-item">
          <span>第 4 步</span>
          <strong>双方确认完成 → 互相评价</strong>
        </div>
        <div class="timeline-item">
          <span>如有争议</span>
          <strong>发起仲裁 → 管理员裁决</strong>
        </div>
      </div>
    </article>

    <article class="panel">
      <p class="eyebrow">常见问题</p>
      <h2 class="section-title">FAQ</h2>
      <div class="faq-list">
        <details v-for="item in faqs" :key="item.q" class="faq-item">
          <summary>
            <span class="faq-q">{{ item.q }}</span>
            <span class="faq-toggle" aria-hidden="true">＋</span>
          </summary>
          <p class="faq-a">{{ item.a }}</p>
        </details>
      </div>
    </article>

    <article class="panel">
      <p class="eyebrow">联系我们</p>
      <h2 class="section-title">需要进一步帮助？</h2>
      <div class="contact-grid">
        <div v-for="c in contacts" :key="c.label" class="contact-card">
          <span class="contact-label">{{ c.label }}</span>
          <strong>{{ c.value }}</strong>
        </div>
      </div>
      <p class="page-summary" style="margin-top: 12px;">
        本平台为南京大学软件工程课程大作业项目，相关问题会在工作日尽快回复。
      </p>
    </article>
  </div>
</template>

<style scoped>
.doc-back {
  margin-bottom: 4px;
}

.back-link {
  font-size: 14px;
  color: var(--muted);
  text-decoration: none;
  transition: color 0.15s ease;
}

.back-link:hover {
  color: var(--text-strong);
}

.doc-hero {
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.95), rgba(247, 236, 221, 0.92));
}

.doc-icon {
  font-size: 28px;
  line-height: 1;
  margin-bottom: 8px;
}

.list-card strong {
  display: block;
  margin-bottom: 6px;
  color: var(--text-strong);
}

.list-card p {
  margin: 0;
  font-size: 14px;
  color: var(--muted);
  line-height: 1.6;
}

.faq-list {
  display: grid;
  gap: 10px;
}

.faq-item {
  border: 1px solid var(--panel-border);
  border-radius: var(--radius-md, 12px);
  background: var(--bg-soft);
  padding: 0;
  overflow: hidden;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.faq-item[open] {
  border-color: rgba(77, 59, 36, 0.24);
  background: #fff;
}

.faq-item summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  cursor: pointer;
  list-style: none;
  font-size: 15px;
  color: var(--text-strong);
  font-weight: 600;
}

.faq-item summary::-webkit-details-marker {
  display: none;
}

.faq-q {
  flex: 1;
}

.faq-toggle {
  flex: none;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--panel-border);
  color: var(--text-strong);
  font-size: 14px;
  font-weight: 700;
  transition: transform 0.2s ease, background 0.15s ease;
}

.faq-item[open] .faq-toggle {
  transform: rotate(45deg);
  background: var(--text-strong);
  color: #fff;
}

.faq-a {
  margin: 0;
  padding: 0 16px 16px;
  font-size: 14px;
  color: var(--muted);
  line-height: 1.7;
}

.contact-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.contact-card {
  display: grid;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--panel-border);
  border-radius: 12px;
  background: var(--bg-soft);
}

.contact-label {
  font-size: 12px;
  color: var(--muted);
}

.contact-card strong {
  color: var(--text-strong);
  font-size: 15px;
}

@media (max-width: 780px) {
  .three-column {
    grid-template-columns: 1fr !important;
  }
}
</style>
