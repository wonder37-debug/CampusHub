<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="panel" style="max-width: 720px; margin: 0 auto; text-align: center;">
    <p class="eyebrow">404</p>
    <h1 class="page-title">页面不存在</h1>
    <p class="page-summary">这个路由不在当前演示层范围内，请返回首页继续浏览平台功能。</p>
    <RouterLink class="button primary" to="/">返回首页</RouterLink>
  </div>
</template>