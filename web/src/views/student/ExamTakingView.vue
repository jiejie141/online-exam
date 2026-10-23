<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { startExam, saveAnswer, submitExam } from '@/api'
import { useAuthStore } from '@/stores/auth'

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const examId = Number(route.params.examId);
const loading = ref(true);
const recordId = ref(null);
const paper = ref([]);
const deadlineAt = ref(null);
const remaining = ref(0);
const submitting = ref(false);
const answers = ref({});
const savingIds = ref(new Set());
const submitted = ref(false);

let tickTimer = null;
let ws = null;

// 把后端的 options（JSON 数组字符串）解析成数组。
// 解析失败不能抛异常 —— 一道题的数据坏了不该让整张卷子白屏。
function parseOptions(raw) {
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

function typeLabel(t) {
  return { single: '单选题', multiple: '多选题', judge: '判断题' }[t] || t;
}

// 判断题在数据库里 options 是 NULL，答案用 T/F。
// 这里把它补成两个选项，让界面上和单选长得一样。
function choicesOf(q) {
  if (q.type === 'judge') {
    return [{ key: 'T', text: '正确' }, { key: 'F', text: '错误' }];
  }
  return parseOptions(q.options);
}

const answeredCount = computed(
  () => paper.value.filter((q) => {
    const v = answers.value[q.id];
    return v !== undefined && v !== null && String(v).length > 0;
  }).length
);

const totalScore = computed(() => paper.value.reduce((s, q) => s + (q.score || 0), 0));

// 时间显示。剩余秒数可能因为网络延迟略负，统一钳到 0。
const remainText = computed(() => {
  const s = Math.max(0, remaining.value);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return h > 0 ? `${pad(h)}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`;
});

const urgent = computed(() => remaining.value > 0 && remaining.value <= 300);

function parseLocal(s) {
  // 后端给的是不带时区的本地时间字符串（2026-10-23T09:00:00）。
  // 直接 new Date(s) 在部分浏览器上会被当成 UTC 解析，导致差 8 小时。
  // 显式拆字段构造，避免这个经典坑。
  if (!s) return null;
  const m = String(s).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  if (!m) return new Date(s);
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
}

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
    for (const q of paper.value) {
      answers.value[q.id] = '';
    }
    tick();
    tickTimer = setInterval(tick, 30000);
    connectWs();
  } catch (e) {
    ElMessage.error(e.message);
    router.replace({ name: 'student-exams' });
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

async function onSubmitClick() {
  const unanswered = paper.value.length - answeredCount.value;
  const tip = unanswered > 0
    ? `还有 ${unanswered} 道题没有作答，交卷后不能修改。确定交卷吗？`
    : '交卷后不能修改，确定交卷吗？';
  try {
    await ElMessageBox.confirm(tip, '确认交卷', {
      confirmButtonText: '交卷',
      cancelButtonText: '再检查一下',
      type: unanswered > 0 ? 'warning' : 'info'
    });
  } catch {
    return;
  }
  doSubmit(false);
}

onMounted(load);

onBeforeUnmount(() => {
  if (tickTimer) clearInterval(tickTimer);
  if (ws) {
    ws.onclose = null;
    ws.close();
  }
});
</script>

<template>
  <div v-loading="loading" class="taking">
    <!-- 顶部固定条：剩余时间 + 进度 + 交卷。考试页最需要随时可见的就是这三样 -->
    <div class="bar">
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

    <div class="paper">
      <div v-for="(q, idx) in paper" :key="q.id" class="q-card">
        <div class="q-head">
          <span class="q-no">{{ idx + 1 }}</span>
          <span class="q-type">{{ typeLabel(q.type) }}</span>
          <span class="q-score">{{ q.score }} 分</span>
          <span v-if="savingIds.has(q.id)" class="q-saving">保存中…</span>
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
          :model-value="String(answers[q.id] || '').split('').filter(Boolean)"
          class="q-options"
          @change="(vals) => { answers[q.id] = vals.slice().sort().join(''); persist(q); }"
        >
          <el-checkbox v-for="opt in choicesOf(q)" :key="opt.key" :value="opt.key" class="q-option">
            <span class="opt-key">{{ opt.key }}</span>
            <span>{{ opt.text }}</span>
          </el-checkbox>
        </el-checkbox-group>
      </div>

      <div v-if="!paper.length && !loading" class="empty-tip">
        这张卷子还没有题目，请联系老师。
      </div>

      <div v-if="paper.length" class="submit-row">
        <span class="muted">本卷共 {{ paper.length }} 题，总分 {{ totalScore }} 分</span>
        <el-button type="primary" size="large" :loading="submitting" @click="onSubmitClick">
          交卷
        </el-button>
      </div>
    </div>
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

.paper {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.q-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px 24px;
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
</style>
