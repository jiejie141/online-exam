<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getResult } from '@/api'
import { parseOptions, choicesOf, typeLabel } from '@/utils/exam'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute();
const router = useRouter();

const recordId = Number(route.params.recordId);
const loading = ref(true);
// 三种界面分开：加载中 / 加载失败 / 判分中。
// 旧版把失败并进了「判分中」的 catch，一个网络错误会显示成
// 「答卷已提交，正在判分」—— 那是把错误伪装成一个正常状态。
const loadError = ref('');
const pending = ref(false);
const data = ref(null);

let pollTimer = null;

const score = computed(() => (data.value ? data.value.score : null));

// 把答卷明细按题目对起来。后端给的两份数据是分开的：
// paper 是按 seq 排的原题（不含答案），answers 是作答与判分结果。
// 用 Map 而不是双重循环，题目一多双重循环就是平方复杂度。
const rows = computed(() => {
  if (!data.value) return [];
  const byQuestion = new Map();
  for (const a of data.value.answers || []) {
    byQuestion.set(a.questionId, a);
  }
  return (data.value.paper || []).map((q, i) => {
    const a = byQuestion.get(q.id) || {};
    return {
      no: i + 1,
      q,
      userAnswer: a.userAnswer,
      correct: a.correct,
      scoreGot: a.scoreGot
    };
  });
});

const correctCount = computed(() => rows.value.filter((r) => r.correct === true).length);


function answerText(q, raw) {
  if (raw === null || raw === undefined || raw === '') return '（未作答）';
  const choices = choicesOf(q);
  const map = new Map(choices.map((c) => [c.key, c.text]));
  const s = String(raw);
  if (q.type === 'multiple') {
    return s
      .split('')
      .map((k) => map.get(k) || k)
      .join('；');
  }
  return map.get(s) || s;
}

function load() {
  loadError.value = '';
  return getResult(recordId)
    .then((d) => {
      data.value = d;
      pending.value = false;
      if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
      }
    })
    .catch((e) => {
      // 判分没完成时后端返回 400「还没判完，请稍后再看」。
      // 这是**正常的中间状态**，不是错误 —— 不能弹错误框，
      // 否则每个异步判分的学生交完卷都会先看到一个红框。
      if (e.message && e.message.indexOf('还没判完') >= 0) {
        pending.value = true;
        startPolling();
        return;
      }
      // 其它失败（网络断了、成绩单被清了、越权）留在本页显示错误与重试，
      // 而不是弹一下就让人看不见原因。
      loadError.value = e.message;
    });
}

// 轮询而不是 WebSocket：判分通常在几百毫秒内完成，
// 为一个只需要问两三次的页面维持长连接不划算。
// 3 秒一次、最多 10 次（30 秒），之后停在「判分中」并给一个手动刷新的按钮 ——
// 无限轮询会让一个卡住的判分任务变成永不停歇的请求。
function startPolling() {
  if (pollTimer) return;
  let tries = 0;
  pollTimer = setInterval(() => {
    tries += 1;
    if (tries > 10) {
      clearInterval(pollTimer);
      pollTimer = null;
      return;
    }
    load();
  }, 3000);
}

async function refresh() {
  loading.value = true;
  await load();
  loading.value = false;
}

// 「判分中」这个状态是后端判完之前唯一能给的答案，所以要让它自己继续问；
// 但错误态不轮询 —— 网络断了每秒重试一次只会刷出一串红字。

onMounted(async () => {
  await load();
  loading.value = false;
});

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer);
});
</script>

<template>
  <div>
    <div class="toolbar">
      <h2 class="page-title" style="margin: 0">成绩单</h2>
      <div class="toolbar-spacer" />
      <el-button @click="router.replace({ name: 'student-exams' })">返回考试列表</el-button>
    </div>

    <!-- 四种界面各走各的：加载中 / 加载失败 / 判分中 / 有结果。
         旧版把「加载中」交给 v-loading 遮罩、把失败塞进判分中的分支，
         于是网络错误和学生看到的是同一块「正在判分」的卡片。 -->
    <SkeletonBlock v-if="loading" :rows="7" />

    <ErrorState
      v-else-if="loadError"
      title="成绩单加载失败"
      :message="loadError"
      @retry="refresh"
    />

    <!-- 判分中：明确告诉学生「已收到，正在算」，而不是显示一个 0 分 -->
    <el-card v-else-if="pending" shadow="never" class="pending-card">
      <!-- 用纯 CSS 转圈而不是 @element-plus/icons-vue 的图标组件： -->
      <!-- 那是一个额外的依赖，只为转一个圈不值得装 -->
      <span class="pending-spinner" aria-hidden="true"></span>
      <p class="pending-title">答卷已提交，正在判分</p>
      <p class="pending-desc">判分是异步完成的，通常几秒内出结果，这个页面会自动刷新。</p>
      <el-button type="primary" plain @click="refresh">手动刷新</el-button>
    </el-card>

    <EmptyState
      v-else-if="!data"
      text="没有拿到这份答卷"
      hint="可能是链接过期或答卷已被删除，回考试列表重新进一次。"
    />

    <template v-else>
      <el-card shadow="never" class="score-card">
        <div class="score-main">
          <div class="score-num">{{ score }}</div>
          <div class="score-meta">
            <div class="score-label">总分</div>
            <div class="score-sub">
              答对 {{ correctCount }} / {{ rows.length }} 题
            </div>
          </div>
        </div>
      </el-card>

      <div class="detail-title">答题详情</div>
      <div class="detail">
        <div v-for="r in rows" :key="r.q.id" class="d-row" :class="{ 'd-ok': r.correct, 'd-bad': !r.correct }">
          <div class="d-head">
            <span class="d-no">{{ r.no }}</span>
            <span class="d-type">{{ typeLabel(r.q.type) }}</span>
            <span class="d-got">{{ r.scoreGot == null ? 0 : r.scoreGot }} / {{ r.q.score }} 分</span>
            <span class="d-flag">{{ r.correct ? '正确' : '错误' }}</span>
          </div>
          <div class="d-stem">{{ r.q.stem }}</div>
          <div class="d-answers">
            <div class="d-line">
              <span class="d-k">你的答案</span>
              <span class="d-v" :class="{ 'v-bad': !r.correct }">{{ answerText(r.q, r.userAnswer) }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pending-card {
  text-align: center;
  padding: 24px 0;
}

.pending-spinner {
  display: inline-block;
  width: 28px;
  height: 28px;
  border: 3px solid #d9ecff;
  border-top-color: #409eff;
  border-radius: 50%;
  animation: pending-spin 0.8s linear infinite;
}

@keyframes pending-spin {
  to {
    transform: rotate(360deg);
  }
}

.pending-title {
  margin: 12px 0 4px;
  font-size: 16px;
  font-weight: 600;
}

.pending-desc {
  margin: 0 0 16px;
  color: #909399;
  font-size: 13px;
}

.score-card {
  margin-bottom: 20px;
}

.score-main {
  display: flex;
  align-items: center;
  gap: 20px;
}

.score-num {
  font-size: 56px;
  font-weight: 700;
  line-height: 1;
  color: #409eff;
  font-variant-numeric: tabular-nums;
}

.score-label {
  font-size: 13px;
  color: #909399;
}

.score-sub {
  margin-top: 4px;
  font-size: 14px;
  color: #606266;
}

.detail-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.d-row {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-left: 3px solid #c0c4cc;
  border-radius: 6px;
  padding: 14px 18px;
}

.d-row.d-ok {
  border-left-color: #67c23a;
}

.d-row.d-bad {
  border-left-color: #f56c6c;
}

.d-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 13px;
}

.d-no {
  font-weight: 600;
  color: #606266;
}

.d-type {
  color: #909399;
}

.d-got {
  margin-left: auto;
  color: #606266;
}

.d-flag {
  padding: 1px 8px;
  border-radius: 3px;
  font-size: 12px;
  background: #f0f9eb;
  color: #67c23a;
}

.d-bad .d-flag {
  background: #fef0f0;
  color: #f56c6c;
}

.d-stem {
  margin-bottom: 10px;
  font-size: 14px;
  line-height: 1.6;
}

.d-line {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.d-k {
  min-width: 64px;
  color: #909399;
}

.d-v {
  color: #303133;
}

.d-v.v-bad {
  color: #f56c6c;
}
</style>
