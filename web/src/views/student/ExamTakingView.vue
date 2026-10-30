<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { startExam, saveAnswer, submitExam } from '@/api'
import { useAuthStore } from '@/stores/auth'
import {
  parseLocal,
  formatRemaining,
  choicesOf,
  typeLabel,
  joinMultiAnswer,
  splitMultiAnswer
} from '@/utils/exam'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const examId = Number(route.params.examId);
const loading = ref(true);
const loadError = ref('');
const recordId = ref(null);
const paper = ref([]);
const deadlineAt = ref(null);
const remaining = ref(0);
const submitting = ref(false);
const answers = ref({});
const savingIds = ref(new Set());
const submitted = ref(false);
// 标记（自己回头想再看一眼的题）。只活在本次答题里，不提交给后端 ——
// 它是个纯粹的「阅读姿势」，不是答案的一部分，后端也没有这个字段。
const marked = ref(new Set());
// 刚跳转到的题，短暂高亮一下，否则点了题号屏幕滚动后用户找不到落点
const flashId = ref(null);

let tickTimer = null;
let ws = null;
let flashTimer = null;

const answeredCount = computed(
  () => paper.value.filter((q) => {
    const v = answers.value[q.id];
    return v !== undefined && v !== null && String(v).length > 0;
  }).length
);

const totalScore = computed(() => paper.value.reduce((s, q) => s + (q.score || 0), 0));

const remainText = computed(() => formatRemaining(remaining.value));

const urgent = computed(() => remaining.value > 0 && remaining.value <= 300);

const isAnswered = (q) => {
  const v = answers.value[q.id];
  return v !== undefined && v !== null && String(v).length > 0;
};

// 未答题号（从 1 开始，给人看的）。交卷前用它拼提示，
// 也用它算答题卡上的「还剩几题」。
const unansweredNumbers = computed(
  () => paper.value.map((q, i) => (isAnswered(q) ? 0 : i + 1)).filter(Boolean)
);

// 本地倒计时只是「显示」：每 30 秒从 deadlineAt 重算一次，而不是简单自减。
// 简单自减在页面被切到后台时会被浏览器节流，回来就慢了一大截；
// 用「截止时刻 - 当前时刻」重算，节流再厉害也不会漂。
function tick() {
  const d = parseLocal(deadlineAt.value);
  if (!d) return;
  remaining.value = Math.floor((d.getTime() - Date.now()) / 1000);
  if (remaining.value <= 0 && !submitted.value && !submitting.value) {
    ElMessage.warning('考试时间已到，正在自动交卷');
    doSubmit(true);
  }
}

// WebSocket 只用来「校准」：服务端每 5 秒推一次权威剩余秒数。
// 连不上不影响答题 —— 本地倒计时照常走，只是没有服务端校准。
// 这就是为什么它是个增强而不是依赖：断线、代理不支持 upgrade 都能正常考。
function connectWs() {
  if (!auth.token) return;
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  // 浏览器原生 WebSocket 不能带自定义请求头，token 只能走查询参数。
  const url = `${proto}//${location.host}/ws/countdown?token=${encodeURIComponent(auth.token)}&examId=${examId}`;
  try {
    ws = new WebSocket(url);
  } catch {
    return;
  }
  ws.onmessage = (ev) => {
    try {
      const msg = JSON.parse(ev.data);
      if (typeof msg.remainingSeconds === 'number') {
        remaining.value = msg.remainingSeconds;
      }
      if (msg.ended && !submitted.value && !submitting.value) {
        ElMessage.warning('考试已结束，正在自动交卷');
        doSubmit(true);
      }
    } catch {
      // 推送内容坏了就忽略，本地倒计时还在跑
    }
  };
  // 不重连：断了就用本地倒计时，重连逻辑对考试场景没有收益，反而可能重复触发
  ws.onerror = () => {};
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const result = await startExam(examId);
    recordId.value = result.recordId;
    // 按 status 分流：已交卷的直接去成绩页。
    if (result.status !== 'ongoing') {
      router.replace({ name: 'exam-result', params: { recordId: result.recordId } });
      return;
    }
    paper.value = result.paper || [];
    deadlineAt.value = result.deadlineAt;
    // 每道题先按空白初始化，再用服务端带回来的已存作答覆盖。
    // 这一行不是可有可无的：没有它，刷新页面 / 换设备重进之后，
    // 界面会把已经存进 exam_answer 的答案全部显示成没答，
    // 答题卡「已答 0/10」、交卷弹窗「还有 N 道题没有作答」也都是假的。
    const saved = new Map();
    for (const a of result.answers || []) {
      if (a && a.questionId != null && a.userAnswer != null) {
        saved.set(a.questionId, a.userAnswer);
      }
    }
    for (const q of paper.value) {
      answers.value[q.id] = saved.has(q.id) ? saved.get(q.id) : '';
    }
    tick();
    tickTimer = setInterval(tick, 30000);
    connectWs();
  } catch (e) {
    // 这里不再弹完就跳走：拿不到卷子是「这一页没加载成功」，
    // 应该停在这一页并给一个重试按钮，而不是把人送回列表页、
    // 让他自己去猜刚才发生了什么。
    loadError.value = e.message;
  } finally {
    loading.value = false;
  }
}

// 每答一题立刻保存。为什么不做「交卷时一次性提交」：
// 断网、浏览器崩溃、误关标签页都会让整场作答消失，
// 而每题的答案本来就是一个独立的小请求，逐题保存的代价很低。
// 后端这个接口是 upsert，重复调用是覆盖，所以这里不需要防抖到「只发一次」。
async function persist(q) {
  if (!recordId.value || submitted.value) return;
  const value = answers.value[q.id] || '';
  savingIds.value.add(q.id);
  try {
    await saveAnswer(recordId.value, q.id, value);
  } catch (e) {
    ElMessage.error(`第 ${q.seq} 题保存失败：${e.message}`);
  } finally {
    savingIds.value.delete(q.id);
  }
}

function onMultiChange(q, vals) {
  // 排序后再拼：勾选顺序 D、A、C 和 A、C、D 是同一个答案集合，
  // 但会生成两个不同的字符串。排序让同一个选择永远只有一种表示。
  answers.value[q.id] = joinMultiAnswer(vals);
  persist(q);
}

function toggleMark(q) {
  if (marked.value.has(q.id)) {
    marked.value.delete(q.id);
  } else {
    marked.value.add(q.id);
  }
}

// 跳到第 idx 题（从 0 开始）。用 scrollIntoView 而不是自己算 offsetTop：
// 顶部有一个 sticky 的时间条，手算会把题头盖住一部分。
function jumpTo(idx) {
  const el = document.getElementById(`q-${idx}`);
  if (!el) return;
  el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  flashId.value = idx;
  if (flashTimer) clearTimeout(flashTimer);
  flashTimer = setTimeout(() => { flashId.value = null; }, 1200);
}

async function doSubmit(auto) {
  if (submitting.value || submitted.value) return;
  submitting.value = true;
  try {
    const result = await submitExam(recordId.value);
    submitted.value = true;
    if (result.score !== null && result.score !== undefined) {
      ElMessage.success(`交卷成功，得分 ${result.score}`);
    } else {
      // score 为 null 是「已交卷，判分中」，不是失败
      ElMessage.success('交卷成功，正在判分');
    }
    router.replace({ name: 'exam-result', params: { recordId: recordId.value } });
  } catch (e) {
    if (auto) {
      // 自动交卷失败（比如已经超时被服务端自动交掉了）：还是去成绩页，
      // 让成绩页去解释当前状态，而不是把学生留在答题界面上
      router.replace({ name: 'exam-result', params: { recordId: recordId.value } });
    } else {
      ElMessage.error(e.message);
    }
  } finally {
    submitting.value = false;
  }
}

// 交卷确认。旧版只有一句「还有 N 道题没有作答」，学生看到 N 之后
// 只能自己一题一题翻回去找 —— 而这时卷子可能有两三千像素高。
// 现在把未答题号直接列出来，并且在弹窗里点得动（点了就跳过去）。
async function onSubmitClick() {
  const missing = unansweredNumbers.value;
  const boxes = missing.map((n) => `<b class="mb-no">${n}</b>`).join('');
  const tip = missing.length
    ? `<p>还有 <b>${missing.length}</b> 道题没有作答：</p><p class="mb-list">${boxes}</p><p>交卷后不能修改。确定交卷吗？</p>`
    : '<p>全部题目都已作答。</p><p>交卷后不能修改，确定交卷吗？</p>';
  try {
    await ElMessageBox.confirm(tip, '确认交卷', {
      // 这段 HTML 里只有题目序号（数字），没有任何用户输入，所以拼接是安全的。
      dangerouslyUseHTMLString: true,
      confirmButtonText: missing.length ? '仍然交卷' : '交卷',
      cancelButtonText: '再检查一下',
      type: missing.length ? 'warning' : 'info',
      customClass: 'submit-confirm'
    });
  } catch {
    return;
  }
  doSubmit(false);
}

onMounted(load);

onBeforeUnmount(() => {
  if (tickTimer) clearInterval(tickTimer);
  if (flashTimer) clearTimeout(flashTimer);
  if (ws) {
    ws.onclose = null;
    ws.close();
  }
});
</script>

<template>
  <div v-loading="loading" class="taking">
    <!-- 顶部固定条：剩余时间 + 进度 + 交卷。考试页最需要随时可见的就是这三样 -->
    <div v-if="!loadError" class="bar">
      <div class="bar-left">
        <span class="bar-label">剩余时间</span>
        <span class="clock" :class="{ urgent }">{{ remainText }}</span>
      </div>
      <div class="bar-mid">
        <span class="bar-label">已答</span>
        <strong>{{ answeredCount }}</strong>
        <span class="muted">/ {{ paper.length }}</span>
      </div>
      <el-button type="primary" :loading="submitting" @click="onSubmitClick">交卷</el-button>
    </div>

    <SkeletonBlock v-if="loading" :rows="8" />

    <ErrorState
      v-else-if="loadError"
      title="这张卷子没能打开"
      :message="loadError"
      @retry="load"
    />

    <template v-else>
      <div class="taking-body">
        <div class="paper">
          <div
            v-for="(q, idx) in paper"
            :id="`q-${idx}`"
            :key="q.id"
            class="q-card"
            :class="{ 'q-card-flash': flashId === idx, 'q-card-marked': marked.has(q.id) }"
          >
            <div class="q-head">
              <span class="q-no">{{ idx + 1 }}</span>
              <span class="q-type">{{ typeLabel(q.type) }}</span>
              <span class="q-score">{{ q.score }} 分</span>
              <span v-if="savingIds.has(q.id)" class="q-saving">保存中…</span>
              <button
                type="button"
                class="q-mark"
                :class="{ on: marked.has(q.id) }"
                :aria-pressed="marked.has(q.id)"
                @click="toggleMark(q)"
              >
                {{ marked.has(q.id) ? '已标记' : '标记' }}
              </button>
            </div>

            <div class="q-stem">{{ q.stem }}</div>

            <!-- 单选 / 判断：一个选项即提交 -->
            <el-radio-group
              v-if="q.type === 'single' || q.type === 'judge'"
              v-model="answers[q.id]"
              class="q-options"
              @change="persist(q)"
            >
              <el-radio v-for="opt in choicesOf(q)" :key="opt.key" :value="opt.key" class="q-option">
                <span class="opt-key">{{ opt.key }}</span>
                <span>{{ opt.text }}</span>
              </el-radio>
            </el-radio-group>

            <!-- 多选：勾完一次提交。el-checkbox-group 的 v-model 是数组，
                 提交前要转成 'ABC' 这种字符串，后端 GradingRule 按字符集合比较 -->
            <el-checkbox-group
              v-else-if="q.type === 'multiple'"
              :model-value="splitMultiAnswer(answers[q.id])"
              class="q-options"
              @change="(vals) => onMultiChange(q, vals)"
            >
              <el-checkbox v-for="opt in choicesOf(q)" :key="opt.key" :value="opt.key" class="q-option">
                <span class="opt-key">{{ opt.key }}</span>
                <span>{{ opt.text }}</span>
              </el-checkbox>
            </el-checkbox-group>
          </div>

          <EmptyState
            v-if="!paper.length"
            text="这张卷子还没有题目，请联系老师。"
          />

          <div v-if="paper.length" class="submit-row">
            <span class="muted">本卷共 {{ paper.length }} 题，总分 {{ totalScore }} 分</span>
            <el-button type="primary" size="large" :loading="submitting" @click="onSubmitClick">
              交卷
            </el-button>
          </div>
        </div>

        <!-- 答题卡：题号宫格 + 已答/未答/标记三色 + 点击跳转。
             旧版一张卷子要滚近 3000 像素，顶部只有一个「已答 3/10」，
             想回第 2 题改一个答案只能靠手滚。 -->
        <aside v-if="paper.length" class="answer-card" aria-label="答题卡">
          <div class="ac-title">答题卡</div>
          <div class="ac-progress">
            <span class="ac-done">{{ answeredCount }}</span>
            <span class="ac-total">/ {{ paper.length }}</span>
          </div>
          <div class="ac-grid">
            <button
              v-for="(q, idx) in paper"
              :key="q.id"
              type="button"
              class="ac-cell"
              :class="{
                'is-done': isAnswered(q),
                'is-marked': marked.has(q.id)
              }"
              :title="`第 ${idx + 1} 题${isAnswered(q) ? '（已答）' : '（未答）'}${marked.has(q.id) ? ' · 已标记' : ''}`"
              @click="jumpTo(idx)"
            >
              {{ idx + 1 }}
            </button>
          </div>
          <div class="ac-legend">
            <span><i class="dot dot-done" />已答</span>
            <span><i class="dot dot-todo" />未答</span>
            <span><i class="dot dot-mark" />标记</span>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.taking {
  padding-bottom: 40px;
}

.bar {
  position: sticky;
  top: 60px;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 12px 20px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}

.bar-left {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.bar-mid {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 14px;
}

.bar-label {
  color: #909399;
  font-size: 13px;
}

.clock {
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #303133;
}

/* 最后 5 分钟变红，是唯一一个「不说也想让人看到」的状态 */
.clock.urgent {
  color: #f56c6c;
}

.muted {
  color: #909399;
  font-size: 13px;
}

/* 左边卷子、右边答题卡。答题卡是 sticky 的：滚到第 8 题时它还在视野里 */
.taking-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 210px;
  gap: 20px;
  align-items: start;
}

.paper {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.q-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px 24px;
  /* 跳转高亮用 outline 而不是 border：border 会改变盒子尺寸，让页面抖一下 */
  scroll-margin-top: 80px;
  transition: outline-color 0.2s;
  outline: 2px solid transparent;
}

.q-card-flash {
  outline-color: #409eff;
}

.q-card-marked {
  border-color: #e6a23c;
}

.q-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.q-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #ecf5ff;
  color: #409eff;
  font-size: 13px;
  font-weight: 600;
}

.q-type {
  font-size: 13px;
  color: #606266;
}

.q-score {
  font-size: 13px;
  color: #909399;
}

.q-saving {
  margin-left: auto;
  font-size: 12px;
  color: #e6a23c;
}

.q-mark {
  margin-left: auto;
  padding: 2px 8px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: #909399;
  font-size: 12px;
  cursor: pointer;
}

.q-mark:hover {
  color: #e6a23c;
  border-color: #f3d19e;
}

.q-mark.on {
  color: #b88230;
  border-color: #e6a23c;
  background: #fdf6ec;
}

.q-stem {
  margin-bottom: 16px;
  font-size: 15px;
  line-height: 1.7;
}

.q-options {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
}

/* 让整行可点，而不是只有左边的小圆点 —— 点一行比点一个 14px 的圆容易得多 */
.q-option {
  display: flex;
  align-items: flex-start;
  height: auto;
  padding: 8px 10px;
  margin: 0;
  border-radius: 6px;
}

.q-option:hover {
  background: #f5f7fa;
}

.opt-key {
  display: inline-block;
  min-width: 18px;
  font-weight: 600;
  color: #606266;
}

.submit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}

/* ---------------- 答题卡 ---------------- */
.answer-card {
  position: sticky;
  top: 140px;
  padding: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}

.ac-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.ac-progress {
  margin: 6px 0 12px;
  font-size: 13px;
  color: #909399;
}

.ac-done {
  color: #409eff;
  font-size: 18px;
  font-weight: 600;
}

.ac-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 6px;
}

.ac-cell {
  aspect-ratio: 1;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: #606266;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}

.ac-cell:hover {
  border-color: #409eff;
  color: #409eff;
}

/* 已答：填充；未答：空白轮廓。两者靠「有没有底」区分，
   不靠颜色深浅 —— 色弱的人也能分出来。 */
.ac-cell.is-done {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}

/* 标记：底下加一条橙色横线，和已答的填充不冲突（已答+标记能同时成立） */
.ac-cell.is-marked {
  box-shadow: inset 0 -3px 0 #e6a23c;
}

.ac-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}

.ac-legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  border: 1px solid #dcdfe6;
}

.dot-done { background: #409eff; border-color: #409eff; }
.dot-todo { background: #fff; }
.dot-mark { background: #fff; border-color: #e6a23c; box-shadow: inset 0 -3px 0 #e6a23c; }

/* 窄屏：答题卡从右栏变成卷子上方的一条，宫格自动铺满整行。
   不隐藏它 —— 手机用户比桌面用户更需要「跳到第几题」。 */
@media (max-width: 900px) {
  .taking-body {
    grid-template-columns: 1fr;
  }

  .answer-card {
    position: static;
    order: -1;
  }

  .ac-grid {
    grid-template-columns: repeat(auto-fill, minmax(36px, 1fr));
  }

  .bar {
    gap: 12px;
    flex-wrap: wrap;
  }
}
</style>
