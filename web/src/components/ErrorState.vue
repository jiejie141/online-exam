<script setup>
// 加载失败时的整块错误态。
//
// 为什么值得单独做一个组件：旧版所有页面都是 catch 里 ElMessage.error 弹一下就完事，
// 弹窗 3 秒后消失，页面上剩下空白 —— 用户既不知道发生了什么，也不知道能不能重试。
// 错误提示的正确位置是它发生的地方，不是屏幕角落。
defineProps({
  title: { type: String, default: '加载失败' },
  message: { type: String, default: '' },
  // 401 这类「重试也没用」的错误不显示重试按钮
  retryable: { type: Boolean, default: true }
});

defineEmits(['retry']);
</script>

<template>
  <div class="err-state" role="alert">
    <div class="err-mark">!</div>
    <div class="err-body">
      <div class="err-title">{{ title }}</div>
      <div v-if="message" class="err-msg">{{ message }}</div>
    </div>
    <el-button v-if="retryable" size="small" @click="$emit('retry')">重试</el-button>
  </div>
</template>

<style scoped>
.err-state {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  background: #fef0f0;
  border: 1px solid #fbc4c4;
  border-radius: 8px;
}

.err-mark {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #f56c6c;
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  text-align: center;
  line-height: 22px;
}

.err-body { flex: 1; min-width: 0; }
.err-title { color: #c45656; font-size: 14px; font-weight: 600; }
.err-msg { margin-top: 2px; color: #909399; font-size: 13px; word-break: break-word; }
</style>
