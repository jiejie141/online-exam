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

// 满分是多少：从卷面各题分值累加，而不是写死 100。
// 不同考试的卷面总分可以不同（组卷时每题分值是可配的）。
const fullScore = computed(() =>
  rows.value.reduce((s, r) => s + (r.q.score || 0), 0)
);

function pad2(n) {
  return String(n).padStart(2, '0');
}

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

// 正确答案的文本。后端不会把答案随卷面发给学生，所以这里没有它 ——
// 只显示「你答了什么」和「对不对」，不显示「正确答案是什么」。
// 这是有意的：成绩单能看错在哪，但不能变成一份标准答案。
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
    <div class="page-head">
      <div>
        <p class="eyebrow">Result</p>
        <h1 class="page-title">成绩单</h1>
      </div>
      <el-button round @click="router.replace({ name: 'student-exams' })">返回考试列表</el-button>
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
    <div v-else-if="pending" class="pending-card">
      <!-- 用纯 CSS 转圈而不是 @element-plus/icons-vue 的图标组件：
           那是一个额外的依赖，只为转一个圈不值得装 -->
      <span class="pending-spinner" aria-hidden="true"></span>
      <p class="pending-title">答卷已提交，正在判分</p>
      <p class="pending-desc">判分是异步完成的，通常几秒内出结果，这个页面会自动刷新。</p>
      <el-button type="primary" round @click="refresh">手动刷新</el-button>
    </div>

    <EmptyState
      v-else-if="!data"
      text="没有拿到这份答卷"
      hint="可能是链接过期或答卷已被删除，回考试列表重新进一次。"
    />

    <template v-else>
      <!-- 大分数是这一页的主角。旁边配三个数字：答对题数、卷面总分、答对率，
           让「87 分」这个数有参照，而不是孤零零一个数字。 -->
      <section class="score-hero">
        <div class="sh-score">
          <p class="eyebrow">Final Score</p>
          <div class="sh-num display-num">{{ score }}</div>
          <div class="sh-of">满分 {{ fullScore }}</div>
        </div>

        <dl class="sh-stats">
          <div>
            <dt>答对</dt>
            <dd><strong>{{ correctCount }}</strong> / {{ rows.length }} 题</dd>
          </div>
          <div>
            <dt>得分率</dt>
            <dd>
              <strong>{{ fullScore ? Math.round((score / fullScore) * 100) : 0 }}</strong> %
            </dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd class="sh-pass" :class="score >= 60 ? 'is-pass' : 'is-fail'">
              {{ score >= 60 ? '已通过' : '未通过' }}
            </dd>
          </div>
        </dl>
      </section>

      <div class="panel">
        <p class="eyebrow">Breakdown</p>
        <h2 class="panel-title">答题详情</h2>
        <p class="panel-desc">
          按卷面顺序列出每一题的作答与得分。这里只显示你答了什么、对不对，
          不显示标准答案 —— 成绩单不是答案册。
        </p>

        <div class="detail">
          <article
            v-for="r in rows"
            :key="r.q.id"
            class="d-row"
            :class="r.correct ? 'd-ok' : 'd-bad'"
          >
            <div class="d-head">
              <span class="d-no display-num">{{ pad2(r.no) }}</span>
              <span class="d-type">{{ typeLabel(r.q.type) }}</span>
              <span class="d-flag">{{ r.correct ? '正确' : '错误' }}</span>
              <span class="d-got">
                {{ r.scoreGot == null ? 0 : r.scoreGot }} / {{ r.q.score }} 分
              </span>
            </div>
            <p class="d-stem">{{ r.q.stem }}</p>
            <div class="d-line">
              <span class="d-k">你的答案</span>
              <span class="d-v" :class="{ 'v-bad': !r.correct }">
                {{ answerText(r.q, r.userAnswer) }}
              </span>
            </div>
          </article>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* ---------------- 判分中 ---------------- */
.pending-card {
  text-align: center;
  padding: 64px 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
}

.pending-spinner {
  display: inline-block;
  width: 30px;
  height: 30px;
  border: 2px solid var(--line);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: pending-spin 0.8s linear infinite;
}

@keyframes pending-spin {
  to {
    transform: rotate(360deg);
  }
}

.pending-title {
  margin: 16px 0 6px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.pending-desc {
  margin: 0 0 20px;
  color: var(--ink-3);
  font-size: 13px;
}

/* ---------------- 分数区 ---------------- */
.score-hero {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 48px;
  align-items: center;
  padding: 36px 40px;
  margin-bottom: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
}

.sh-num {
  margin-top: 6px;
  font-size: clamp(64px, 9vw, 104px);
  color: var(--ink);
}

.sh-of {
  margin-top: 4px;
  font-size: 12px;
  letter-spacing: 0.1em;
  color: var(--ink-4);
}

.sh-stats {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
}

.sh-stats dt {
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-4);
  margin-bottom: 6px;
}

.sh-stats dd {
  margin: 0;
  font-size: 14px;
  color: var(--ink-3);
}

.sh-stats strong {
  font-size: 26px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: var(--ink);
  letter-spacing: -0.03em;
}

.sh-pass.is-pass {
  color: var(--ok);
  font-weight: 600;
}

.sh-pass.is-fail {
  color: var(--bad);
  font-weight: 600;
}

/* ---------------- 明细 ---------------- */
.detail {
  display: flex;
  flex-direction: column;
}

/* 每道题一条细线分隔，不用一张张卡片 —— 卡片一多版面就碎 */
.d-row {
  padding: 22px 0;
  border-bottom: 1px solid var(--line-soft);
}

.d-row:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.d-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.d-no {
  font-size: 22px;
  color: var(--ink-4);
}

.d-ok .d-no {
  color: var(--ok);
}

.d-bad .d-no {
  color: var(--bad);
}

.d-type {
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-4);
}

.d-flag {
  padding: 1px 9px;
  border-radius: var(--r-pill);
  font-size: 11px;
  letter-spacing: 0.06em;
  background: var(--ok-soft);
  color: var(--ok);
}

.d-bad .d-flag {
  background: var(--bad-soft);
  color: var(--bad);
}

.d-got {
  margin-left: auto;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--ink-3);
}

.d-stem {
  margin: 0 0 12px;
  font-size: 15px;
  line-height: 1.65;
  max-width: 62ch;
}

.d-line {
  display: flex;
  gap: 12px;
  font-size: 13px;
}

.d-k {
  flex: none;
  min-width: 60px;
  color: var(--ink-4);
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  padding-top: 2px;
}

.d-v {
  color: var(--ink-2);
}

.d-v.v-bad {
  color: var(--bad);
}

@media (max-width: 900px) {
  .score-hero {
    grid-template-columns: 1fr;
    gap: 24px;
    padding: 26px 22px;
  }

  .sh-stats {
    gap: 16px;
  }

  .sh-stats strong {
    font-size: 20px;
  }
}
</style>
