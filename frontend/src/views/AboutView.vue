<script setup lang="ts">
const docs = [
  '发布需求、接单、评价和通知已经可以正常使用。',
  '平台支持账号切换，方便体验不同身份下的操作路径。',
  '页面已按真实使用场景整理，便于直接浏览和体验。'
]
</script>

<template>
  <div class="page-grid two-column">
    <article class="panel">
      <p class="eyebrow">关于项目</p>
      <h1 class="page-title">CampusHub 校园互助平台</h1>
      <p class="page-summary">
        这是一个服务校园日常互助的平台，你可以在这里发布需求、接单协作、查看通知和管理账号。
      </p>

      <div class="section-grid">
        <div v-for="item in docs" :key="item" class="list-card">
          <p>{{ item }}</p>
        </div>
      </div>
    </article>

    <article class="panel helper-card">
      <p class="eyebrow">平台特色</p>
      <h2 class="section-title">页面说明</h2>
      <div class="timeline">
        <div class="timeline-item">
          <span>Vue 3 + TypeScript + Vite</span>
          <strong>流畅单页体验</strong>
        </div>
        <div class="timeline-item">
          <span>数据同步</span>
          <strong>统一管理页面状态</strong>
        </div>
        <div class="timeline-item">
          <span>Vue Router</span>
          <strong>清晰页面导航</strong>
        </div>
        <div class="timeline-item">
          <span>功能覆盖</span>
          <strong>认证 / 需求 / 订单 / 通知</strong>
        </div>
      </div>
    </article>
  </div>
</template>
