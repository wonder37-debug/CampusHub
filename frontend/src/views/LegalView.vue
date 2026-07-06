<script setup lang="ts">
import { RouterLink } from 'vue-router'

const agreementSections = [
  {
    title: '一、服务说明',
    body: 'CampusHub（以下简称“本平台”）是由南京大学软件工程课程大作业项目开发的校园互助平台，提供需求发布、接单协作、订单管理、评价反馈、站内通知等功能。本平台仅面向校园场景内的互助需求，使用者须为具备完全民事行为能力的在校学生或教职工。'
  },
  {
    title: '二、账号与注册',
    body: '注册需使用校园邮箱（域名须在白名单内，默认支持 nju.edu.cn、smail.nju.edu.cn、edu.cn 等），并通过邮箱验证码完成验证。用户应保证注册信息真实有效，妥善保管账号与密码；因账号保管不善导致的一切后果由用户自行承担。密码采用 BCrypt 加密存储，认证 Token 有效期为 1 小时。'
  },
  {
    title: '三、使用规范',
    items: [
      '不得发布代课、代考等违反学术诚信或法律法规的违规内容，平台通过敏感词过滤进行屏蔽。',
      '不得发布虚假、欺诈、侵权或损害他人合法权益的需求。',
      '不得恶意接单、囤单、恶意评价或扰乱平台正常秩序。',
      '不得利用本平台从事任何商业经营性活动或洗钱、刷单等违规资金操作。',
      '不得对平台接口进行逆向、爬取、压力测试或其他破坏性访问。'
    ]
  },
  {
    title: '四、订单与交易',
    body: '悬赏金在发布需求时冻结，订单完成后转移给接单方，订单取消时解冻退回发布方。订单状态流转为：已接单 → 进行中 → 已完成 / 仲裁中 / 已取消。接单方提交完成凭证后，发布者应在 48 小时内确认，超时系统将自动完成。出现争议时任意方可发起仲裁，由管理员裁决。'
  },
  {
    title: '五、信用与评价',
    body: '订单完成后双方可互相评价（1-5 星 + 文字评论）。信用分按历史权重 0.9 + 新评价权重 0.1 动态计算，信用等级分为：金牌助教（95+）、银牌助教（85+）、成长中。平台有权对刷分、恶意评价等异常行为进行处置。'
  },
  {
    title: '六、知识产权',
    body: '本平台的页面设计、代码、文档等知识产权归项目团队及南京大学所有，基于 MIT 协议开源。用户在平台发布的内容（需求描述、凭证图片、评价等）著作权归用户所有，但用户在发布即视为授予平台为提供服务而存储、展示、处理的非排他性许可。'
  },
  {
    title: '七、免责声明',
    body: '本平台为课程项目，仅供学习与校园互助使用，不提供任何明示或默示的担保。因使用本平台产生的间接损失、第三方纠纷或交易风险，平台在法律允许的范围内不承担责任。平台保留在法律允许范围内对违规账号进行封禁、限制功能的权利。'
  }
]

const privacySections = [
  {
    title: '一、我们收集的信息',
    items: [
      '账号信息：邮箱、学号、昵称、头像等注册与个人资料信息。',
      '交易信息：需求、订单、评价、凭证图片及余额流水记录。',
      '行为信息：浏览、接单、评价等行为日志，用于个性化推荐。',
      '设备信息：为保障安全，可能记录访问时间与请求来源等基本日志。'
    ]
  },
  {
    title: '二、信息使用方式',
    items: [
      '提供需求发布、接单、订单管理、评价与通知等核心服务。',
      '计算信用分与生成个性化推荐，提升匹配效率。',
      '进行安全风控、违规识别与平台运营数据分析。',
      '在取得用户同意或法律法规要求时，用于其他必要用途。'
    ]
  },
  {
    title: '三、信息存储与保护',
    body: '数据存储于平台后端数据库（生产环境 MySQL，字符集 utf8mb4），密码使用 BCrypt 加密，认证采用 Token 机制。我们采取合理的技术与管理措施保护信息安全，但无法保证绝对安全。文件上传仅支持 jpg、jpeg、png、webp，单文件上限 5MB，按年月目录存储并提供 24 小时浏览器缓存。'
  },
  {
    title: '四、信息共享与披露',
    items: [
      '本平台不会向第三方出售用户个人信息。',
      '仅在用户同意、完成交易所需或法律法规要求时，向必要对象共享信息。',
      '在涉及仲裁、违规处置等场景下，平台可向相关方披露必要信息。',
      '为保护平台、其他用户及公共利益，在合法合规前提下可依法披露。'
    ]
  },
  {
    title: '五、用户权利',
    items: [
      '有权访问、更正自己的个人信息（可在“我的 → 编辑资料”中操作）。',
      '有权请求删除部分非必要信息或注销账号（可通过底部邮箱联系我们）。',
      '有权知悉信息的使用范围与目的，本政策将明确告知。',
      '对信息处理有疑问时，可通过页面底部联系方式反馈。'
    ]
  },
  {
    title: '六、Cookie 与本地存储',
    body: '为保持登录状态、保存草稿（前端 localStorage）与提升体验，本平台会在浏览器本地存储必要信息。用户可通过浏览器设置清除本地数据，但可能影响部分功能。'
  },
  {
    title: '七、政策更新',
    body: '本平台可能根据业务发展或法律法规变化更新本协议与隐私政策。更新后在平台公布即生效，建议用户定期查阅。继续使用即视为接受更新后的内容。'
  }
]
</script>

<template>
  <div class="page-grid">
    <nav class="doc-back">
      <RouterLink to="/" class="back-link">← 返回首页</RouterLink>
    </nav>

    <article class="panel doc-hero">
      <p class="eyebrow">法律文件</p>
      <h1 class="page-title">🛡 用户协议 &amp; 隐私政策</h1>
      <p class="page-summary">
        请在使用 CampusHub 前仔细阅读以下条款。本文件说明了你与平台之间的权利义务，以及我们如何收集、使用和保护你的个人信息。注册或继续使用即视为你已阅读并同意本协议与隐私政策。
      </p>
      <p class="doc-meta">最近更新：2026 年 7 月 · 适用范围：CampusHub 全站</p>
    </article>

    <article class="panel">
      <p class="eyebrow">用户协议</p>
      <h2 class="section-title">使用条款</h2>
      <div class="doc-article">
        <section v-for="s in agreementSections" :key="s.title" class="doc-section">
          <h3>{{ s.title }}</h3>
          <p v-if="s.body">{{ s.body }}</p>
          <ul v-if="s.items">
            <li v-for="i in s.items" :key="i">{{ i }}</li>
          </ul>
        </section>
      </div>
    </article>

    <article class="panel">
      <p class="eyebrow">隐私政策</p>
      <h2 class="section-title">我们如何处理你的信息</h2>
      <div class="doc-article">
        <section v-for="s in privacySections" :key="s.title" class="doc-section">
          <h3>{{ s.title }}</h3>
          <p v-if="s.body">{{ s.body }}</p>
          <ul v-if="s.items">
            <li v-for="i in s.items" :key="i">{{ i }}</li>
          </ul>
        </section>
      </div>
    </article>

    <article class="panel doc-accept">
      <p class="page-summary" style="margin: 0;">
        如对本协议或隐私政策有任何疑问，可通过页面底部的联系方式与我们沟通。本平台为南京大学软件工程课程大作业项目，基于 MIT 协议开源。
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

.doc-meta {
  margin-top: 10px;
  font-size: 12px;
  color: var(--muted);
}

.doc-article {
  display: grid;
  gap: 20px;
}

.doc-section {
  border-left: 3px solid var(--panel-border);
  padding-left: 16px;
  transition: border-color 0.15s ease;
}

.doc-section:hover {
  border-left-color: var(--text-strong);
}

.doc-section h3 {
  margin: 0 0 8px;
  font-size: 16px;
  color: var(--text-strong);
}

.doc-section p {
  margin: 0 0 8px;
  font-size: 14px;
  line-height: 1.75;
  color: var(--text);
}

.doc-section ul {
  margin: 0;
  padding-left: 22px;
  display: grid;
  gap: 6px;
}

.doc-section li {
  font-size: 14px;
  line-height: 1.7;
  color: var(--text);
}

.doc-accept {
  background: var(--bg-soft);
}
</style>
