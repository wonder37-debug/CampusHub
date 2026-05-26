<template>
  <div class="list-card skeleton">
    <div class="s-head">
      <div class="s-line s-line-short"></div>
      <div class="s-line s-line-mini"></div>
    </div>
    <div class="s-title s-line"></div>
    <div class="s-sub s-line"></div>
    <div class="s-avatar-row">
      <div class="s-avatar"></div>
      <div class="s-lines">
        <div class="s-line"></div>
        <div class="s-line s-line-short"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// purely presentational
</script>

<style scoped>
.skeleton {
  position: relative;
  overflow: hidden;
}
.skeleton::after {
  content: '';
  position: absolute;
  top: 0;
  left: -150%;
  height: 100%;
  width: 150%;
  background: linear-gradient(90deg, rgba(255,255,255,0), rgba(255,255,255,0.6), rgba(255,255,255,0));
  animation: shimmer 1.2s infinite linear;
}
.s-line { height: 12px; background: rgba(0,0,0,0.06); border-radius: 8px; margin: 8px 0; }
.s-line-short { width: 40%; }
.s-line-mini { width: 24px; height: 8px; }
.s-title { height: 18px; width: 70%; }
.s-sub { height: 12px; width: 90%; }
.s-head { display:flex; justify-content:space-between; align-items:center; gap:8px }
.s-avatar-row { display:flex; gap:12px; align-items:center; margin-top:8px }
.s-avatar { width:42px; height:42px; border-radius:10px; background: rgba(0,0,0,0.06); }
.s-lines { flex:1 }

@keyframes shimmer { from { transform: translateX(0) } to { transform: translateX(100%) } }
</style>
