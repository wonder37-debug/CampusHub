<script setup lang="ts">
const docs = [
  'P3 已给出 RESTful API 规范、请求参数与错误码。',
  'P4 明确了前端页面任务：认证、需求、订单、评价、通知与后台。',
  '当前展示层使用演示态数据仓库，便于在后端未完成时仍可演示流程。'
]
</script>

<template>
  <div class="page-grid two-column">
    <article class="panel">
      <p class="eyebrow">关于项目</p>
      <h1 class="page-title">CampusHub 校园互助平台</h1>
      <p class="page-summary">
        这是一个围绕“发布需求-接单-完成-评价-通知-后台审核”闭环设计的课程项目，目前前端展示层已经按文档结构重构。
      </p>

      <div class="section-grid">
        <div v-for="item in docs" :key="item" class="list-card">
          <p>{{ item }}</p>
        </div>
      </div>
    </article>

    <article class="panel helper-card">
      <p class="eyebrow">技术栈</p>
      <h2 class="section-title">前端结构</h2>
      <div class="timeline">
        <div class="timeline-item">
          <span>Vue 3 + TypeScript + Vite</span>
          <strong>SPA</strong>
        </div>
        <div class="timeline-item">
          <span>Pinia</span>
          <strong>演示态仓库</strong>
        </div>
        <div class="timeline-item">
          <span>Vue Router</span>
          <strong>多页面入口</strong>
        </div>
        <div class="timeline-item">
          <span>P3 接口映射</span>
          <strong>/api/v1</strong>
        </div>
      </div>
    </article>
  </div>
</template>
