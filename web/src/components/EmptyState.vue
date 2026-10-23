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
    <div class="empty-text">{{ text }}</div>
    <div v-if="hint" class="empty-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.empty-state { padding: 40px 0; text-align: center; }
.empty-text { color: #606266; font-size: 14px; }
.empty-hint { margin-top: 6px; color: #a8abb2; font-size: 13px; }
</style>
