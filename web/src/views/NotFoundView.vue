<script setup>
import { useRouter } from 'vue-router'
const router = useRouter();
</script>

<template>
  <div class="notfound">
    <h1 class="code">404</h1>
    <p class="desc">这个页面不存在，可能是地址输错了。</p>
    <el-button type="primary" @click="router.replace('/')">回到首页</el-button>
  </div>
</template>

<style scoped>
.notfound {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.code {
  margin: 0;
  font-size: 64px;
  font-weight: 700;
  color: #c0c4cc;
}

.desc {
  margin: 0 0 8px;
  color: #909399;
}
</style>
