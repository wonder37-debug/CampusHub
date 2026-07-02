<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="empty-hero">
    <div class="empty-icon">🔍</div>
    <p class="eyebrow">404</p>
    <h1 class="page-title">页面不存在</h1>
    <p class="page-summary">这个路由不在当前页面范围内，请返回首页继续浏览平台功能。</p>
    <RouterLink class="button primary" to="/">返回首页</RouterLink>
  </div>
</template>

<style scoped>
.empty-hero {
  max-width: 560px;
  margin: 40px auto;
  padding: 36px 28px;
  text-align: center;
  border-radius: var(--radius-lg);
  background: var(--panel);
  border: 1px solid var(--panel-border);
  box-shadow: var(--shadow-soft);
}

.empty-icon {
  font-size: 56px;
  margin-bottom: 12px;
  line-height: 1;
}
</style>