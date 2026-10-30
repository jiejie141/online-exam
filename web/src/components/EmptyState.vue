<script setup>
// 空状态。和「加载中」是两件事：一个是还没有结果，一个是结果就是没有。
// 混在一起写就会出现「一直在转圈」或者「还没查完就说没有」。
defineProps({
  text: { type: String, required: true },
  hint: { type: String, default: '' }
});
</script>

<template>
  <div class="empty-state">
    <div class="empty-mark" aria-hidden="true"></div>
    <div class="empty-text">{{ text }}</div>
    <div v-if="hint" class="empty-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.empty-state {
  padding: 56px 0;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

/* 一个空心的方块轮廓，比一个大图标安静 */
.empty-mark {
  width: 34px;
  height: 34px;
  margin-bottom: 10px;
  border: 1px dashed var(--ink-4);
  border-radius: var(--r-sm);
  transform: rotate(45deg);
}

.empty-text { color: var(--ink-2); font-size: 14px; }
.empty-hint { color: var(--ink-4); font-size: 13px; max-width: 40ch; line-height: 1.6; }
</style>
