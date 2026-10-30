<script setup>
import { useRouter } from 'vue-router'
const router = useRouter();
</script>

<template>
  <div class="notfound">
    <p class="eyebrow">Error 404</p>
    <h1 class="code display-num">404</h1>
    <p class="desc">这个页面不存在，可能是地址输错了。</p>
    <el-button type="primary" round @click="router.replace('/')">回到首页</el-button>
  </div>
</template>

<style scoped>
.notfound {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 24px;
}

.code {
  margin: 6px 0 4px;
  font-size: clamp(72px, 14vw, 132px);
  color: var(--ink);
}

.desc {
  margin: 0 0 16px;
  color: var(--ink-3);
  font-size: 14px;
}
</style>
