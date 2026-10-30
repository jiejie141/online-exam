<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listAvailableExams, startExam } from '@/api'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter();
const loading = ref(true);
const exams = ref([]);
const startingId = ref(null);
// 三个状态分开存，不合并成「一个 loading 管所有」：
// 请求中 / 请求失败 / 请求成功但结果为空，是三种完全不同的界面，
// 混在一起就会在失败时显示「暂无数据」—— 把错误伪装成正常结果。
const error = ref('');

async function load() {
  loading.value = true;
  error.value = '';
  try {
    exams.value = await listAvailableExams();
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

// 进入考试。这里有一个容易做错的地方：start 的返回里 status 可能是
// ongoing / submitted / graded 三种。刷新页面、换设备重进都会走到这里，
// 返回的是**原来那份**答卷。如果只看「拿到卷子了」就进答题页，
// 一个已经交过卷的学生会看到一个可以点、但每次保存都被后端拒掉的界面。
// 所以必须按 status 分流。
async function onStart(exam) {
  startingId.value = exam.id;
  try {
    const result = await startExam(exam.id);
    if (result.status === 'ongoing') {
      router.push({ name: 'exam-taking', params: { examId: exam.id } });
    } else {
      // 已交卷 / 已判分：直接去成绩页
      router.push({ name: 'exam-result', params: { recordId: result.recordId } });
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    startingId.value = null;
  }
}

function fmtTime(v) {
  if (!v) return '-';
  // 后端返回的是 ISO 本地时间（2026-10-23T09:00:00），直接替换 T 即可，
  // 不要用 new Date() 再格式化 —— 那会按浏览器时区重新解释一遍，
  // 跨时区看就成了另一个时间。
  return String(v).replace('T', ' ').slice(0, 16);
}

// 时间窗拆成「日期 / 时刻」两行，比一长串 2026-10-30 09:00 好扫。
function splitTime(v) {
  const s = fmtTime(v);
  if (s === '-') return { date: '-', time: '' };
  const [date, time] = s.split(' ');
  return { date, time: time || '' };
}

onMounted(load);
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <p class="eyebrow">Available Exams</p>
        <h1 class="page-title">我的考试</h1>
        <p class="page-desc">这里只显示当前时间窗内可以参加的考试。点开就是答题页，中途关掉也不会丢已答的内容。</p>
      </div>
    </div>

    <SkeletonBlock v-if="loading" :rows="4" />

    <ErrorState
      v-else-if="error"
      title="考试列表加载失败"
      :message="error"
      @retry="load"
    />

    <div v-else-if="exams.length" class="exam-grid">
      <article v-for="exam in exams" :key="exam.id" class="exam-card">
        <div class="ec-top">
          <span class="ec-subject">{{ exam.subject }}</span>
          <span class="ec-dur">{{ exam.durationMinutes }} 分钟</span>
        </div>

        <h2 class="ec-title">{{ exam.title }}</h2>

        <dl class="ec-meta">
          <div>
            <dt>开始</dt>
            <dd>
              <span class="ec-date">{{ splitTime(exam.startAt).date }}</span>
              <span class="ec-time">{{ splitTime(exam.startAt).time }}</span>
            </dd>
          </div>
          <div>
            <dt>结束</dt>
            <dd>
              <span class="ec-date">{{ splitTime(exam.endAt).date }}</span>
              <span class="ec-time">{{ splitTime(exam.endAt).time }}</span>
            </dd>
          </div>
        </dl>

        <div class="ec-foot">
          <div class="ec-score">
            <span class="ec-score-num display-num">{{ exam.totalScore }}</span>
            <span class="ec-score-label">总分</span>
          </div>
          <el-button
            type="primary"
            round
            :loading="startingId === exam.id"
            @click="onStart(exam)"
          >
            进入考试
          </el-button>
        </div>
      </article>
    </div>

    <EmptyState
      v-else
      text="当前没有可以参加的考试。"
      hint="考试开放时间到了才会出现在这里，刷新一下再看看。"
    />
  </div>
</template>

<style scoped>
/* 卡片网格而不是表格：这里一屏最多三五场考试，每场的信息量不大，
   表格会把「一场考试」拆成七个格子，扫起来反而累。
   auto-fill + minmax 让窄屏自然掉成单列，不用写断点。 */
.exam-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 18px;
}

.exam-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 24px 26px 22px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  transition: border-color 0.18s, box-shadow 0.18s, transform 0.18s;
}

.exam-card:hover {
  border-color: var(--ink-4);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.ec-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-4);
}

.ec-subject {
  color: var(--accent-ink);
}

.ec-title {
  margin: 0;
  font-size: 21px;
  font-weight: 600;
  letter-spacing: -0.025em;
  line-height: 1.3;
  /* 标题最多两行，超出打点。不加这一条时，一个超长考试名会把卡片撑得
     比其他卡片高一截，网格就参差了。 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ec-meta {
  margin: 0;
  display: flex;
  gap: 28px;
}

.ec-meta dt {
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-4);
  margin-bottom: 3px;
}

.ec-meta dd {
  margin: 0;
  display: flex;
  flex-direction: column;
}

.ec-date {
  font-size: 13px;
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}

.ec-time {
  font-size: 12px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

.ec-foot {
  margin-top: auto;
  padding-top: 16px;
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.ec-score {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.ec-score-num {
  font-size: 34px;
  color: var(--ink);
}

.ec-score-label {
  font-size: 12px;
  color: var(--ink-4);
}
</style>
