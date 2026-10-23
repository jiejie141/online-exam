<script setup>
// 骨架屏：请求还没回来时占住版面，让页面在 0.2 秒内看起来就「已经在了」。
//
// 为什么自己写而不用 el-skeleton：这个块只有一种形状（几条灰条），
// 自己写十行 CSS 就够，省得为它再往 vendor 里塞一个组件。
//
// 注意 aria-busy + aria-live：读屏软件靠这两个属性知道「这里正在加载」，
// 否则它会读出一片空白然后什么都不说。
defineProps({
  rows: { type: Number, default: 4 },
  // 卡片式（列表/表格）还是行式（详情），只影响第一条的宽度
  dense: { type: Boolean, default: false }
});
</script>

<template>
  <div class="skeleton" aria-busy="true" aria-live="polite" aria-label="正在加载">
    <div
      v-for="i in rows"
      :key="i"
      class="skeleton-row"
      :class="{ 'skeleton-row-dense': dense }"
      :style="{ width: i === rows ? '45%' : i === 1 ? '70%' : '100%' }"
    />
  </div>
</template>

<style scoped>
.skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 0;
}

.skeleton-row {
  height: 16px;
  border-radius: 4px;
  /* 用背景位移做扫光，比 opacity 闪烁不刺眼；动画停掉也仍然是一条灰条 */
  background: linear-gradient(90deg, #f0f2f5 25%, #e6e9ee 37%, #f0f2f5 63%);
  background-size: 400% 100%;
  animation: skeleton-sweep 1.4s ease infinite;
}

.skeleton-row-dense {
  height: 12px;
}

@keyframes skeleton-sweep {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* 尊重系统设置：关掉动效的人不该被一块一直扫光的东西晃着 */
@media (prefers-reduced-motion: reduce) {
  .skeleton-row { animation: none; }
}
</style>
