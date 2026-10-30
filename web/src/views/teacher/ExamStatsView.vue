<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getStats, getDistribution, getAccuracy, getProgress, listExams } from '@/api'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute();
const router = useRouter();
const examId = Number(route.params.examId);

const loading = ref(true);
// 统计页有五个请求，任意一个失败就整页没数据。旧版只弹一下 toast，
// 于是页面上留着五张写着「—」的卡片 —— 看起来像「这门考试没人考」，
// 实际是「请求失败了」。这两种情况的处理动作完全不同（刷新 vs 等学生）。
const loadError = ref('');
const stats = ref(null);
const distribution = ref([]);
const accuracy = ref([]);
const progress = ref(null);
const examTitle = ref('');

// 分布图的柱子按百分比给高度。用纯 CSS 画而不是引 ECharts：
// 一张柱状图不值得为它多背一个 300KB 的依赖，
// 而这张图的横轴永远是 10 个分数段，形状是固定的。
const maxBucket = computed(() => {
  const m = Math.max(0, ...distribution.value.map((d) => d.cnt || 0));
  return m || 1;
});

function barHeight(d) {
  return Math.round(((d.cnt || 0) / maxBucket.value) * 100) + '%';
}

// 正确率 = 答对人数 / 作答人数。attempt 是 0 时不能除 ——
// 0/0 在 JS 里是 NaN，直接渲染出来是「NaN%」，比不显示更糟。
function rateText(row) {
  if (!row.attempt) return '—';
  return Math.round((row.correctCount / row.attempt) * 100) + '%';
}

function rateClass(row) {
  if (!row.attempt) return '';
  const r = row.correctCount / row.attempt;
  if (r < 0.4) return 'rate-bad';
  if (r < 0.7) return 'rate-mid';
  return 'rate-good';
}

const avgText = computed(() => {
  if (!stats.value || !stats.value.total) return '—';
  return Number(stats.value.avgScore).toFixed(1);
});

const passRate = computed(() => {
  if (!stats.value || !stats.value.total) return null;
  return Math.round((stats.value.passCount / stats.value.total) * 100);
});

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [s, d, a, p, exams] = await Promise.all([
      getStats(examId),
      getDistribution(examId),
      getAccuracy(examId),
      getProgress(examId),
      // 这里只是拿标题，不需要翻页，但也不能用默认的 20 条 ——
      // 老师手上超过 20 场考试时，正在看的这场可能不在第一页，
      // 标题就会退化成「考试 #12」这种没有信息量的东西。100 是后端上限。
      listExams({ page: 1, size: 100 })
    ]);
    // listExams 是分页接口，返回 { items, ... }；这里只用它找标题
    const examItems = (exams && exams.items) || [];
    stats.value = s;
    distribution.value = d || [];
    accuracy.value = a || [];
    progress.value = p;
    const found = examItems.find((e) => e.id === examId);
    examTitle.value = found ? found.title : '考试 #' + examId;
  } catch (e) {
    loadError.value = e.message;
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <p class="eyebrow">Statistics</p>
        <h1 class="page-title">成绩统计</h1>
        <p class="page-desc">{{ examTitle }}</p>
      </div>
      <div class="head-actions">
        <el-button round @click="load">刷新</el-button>
        <el-button round @click="router.replace({ name: 'teacher-exams' })">返回</el-button>
      </div>
    </div>

    <SkeletonBlock v-if="loading" :rows="9" />

    <ErrorState
      v-else-if="loadError"
      title="统计数据加载失败"
      :message="loadError"
      @retry="load"
    />

    <template v-else>
      <!-- 概览：五个数字并排，用大字号和细竖线分隔。
           total 为 0 时明确写「—」，而不是显示 0 分平均分 —— 那是假数据 -->
      <section class="metric-row">
        <div class="metric">
          <p class="eyebrow">Graded</p>
          <div class="metric-value display-num">{{ stats ? stats.total : '—' }}</div>
          <div class="metric-label">已判分人数</div>
        </div>
        <div class="metric">
          <p class="eyebrow">Average</p>
          <div class="metric-value display-num">{{ avgText }}</div>
          <div class="metric-label">平均分</div>
        </div>
        <div class="metric">
          <p class="eyebrow">Range</p>
          <div class="metric-value display-num">
            <template v-if="stats && stats.total">{{ stats.maxScore }}<span class="sep">/</span>{{ stats.minScore }}</template>
            <template v-else>—</template>
          </div>
          <div class="metric-label">最高 / 最低</div>
        </div>
        <div class="metric">
          <p class="eyebrow">Passed</p>
          <div class="metric-value display-num">{{ stats ? stats.passCount : '—' }}</div>
          <div class="metric-label">
            及格人数<span v-if="passRate !== null"> · {{ passRate }}%</span>
          </div>
        </div>
        <div class="metric">
          <p class="eyebrow">Progress</p>
          <div class="metric-value display-num">
            <template v-if="progress">{{ progress.submitted }}<span class="sep">/</span>{{ progress.ongoing }}</template>
            <template v-else>—</template>
          </div>
          <div class="metric-label">已交 / 进行中</div>
        </div>
      </section>

      <div class="panel">
        <p class="eyebrow">Distribution</p>
        <h2 class="panel-title">成绩分布</h2>
        <p class="panel-desc">横轴是分数段，纵轴是人数。柱子最高的那一段就是这门课的主力区间。</p>

        <div v-if="distribution.length" class="chart">
          <div v-for="d in distribution" :key="d.bucket" class="bar-wrap">
            <div class="bar-cnt">{{ d.cnt }}</div>
            <div class="bar" :style="{ height: barHeight(d) }"></div>
            <div class="bar-label">{{ d.bucket }}–{{ d.bucket + 9 }}</div>
          </div>
        </div>
        <!-- 「还没有已判分的答卷」是结果为空，不是加载失败 —— 这两种界面
             在上面已经分开了，这里只负责「真的没人考」。 -->
        <EmptyState
          v-else
          text="还没有已判分的答卷。"
          hint="学生交卷后判分是异步完成的，等一会儿点上面的刷新。"
        />
      </div>

      <div class="panel">
        <p class="eyebrow">Accuracy</p>
        <h2 class="panel-title">逐题正确率</h2>
        <p class="panel-desc">
          按正确率升序排列 —— 排在最前面的就是全班错得最多的题，讲评时先看它。
        </p>
        <el-table v-if="accuracy.length" :data="accuracy" size="small">
          <el-table-column label="题号" width="90">
            <template #default="{ row }"><span class="mono">{{ row.questionId }}</span></template>
          </el-table-column>
          <el-table-column prop="stem" label="题干" min-width="300" show-overflow-tooltip />
          <el-table-column label="作答人数" width="110">
            <template #default="{ row }"><span class="mono">{{ row.attempt }}</span></template>
          </el-table-column>
          <el-table-column label="答对人数" width="110">
            <template #default="{ row }"><span class="mono">{{ row.correctCount }}</span></template>
          </el-table-column>
          <el-table-column label="正确率" width="120">
            <template #default="{ row }">
              <span class="rate" :class="rateClass(row)">{{ rateText(row) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState v-else text="还没有判分数据。" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 10px;
  flex: none;
}

/* ---------------- 指标行 ----------------
   五个数字并排，中间用一条极淡的竖线分隔。
   比五张独立卡片轻得多 —— 卡片会把一屏切成五块，读起来是五个孤立的值。 */
.metric-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 1px;
  margin-bottom: 20px;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.metric {
  background: var(--surface);
  padding: 22px 24px;
}

.metric-value {
  margin-top: 8px;
  font-size: 40px;
  color: var(--ink);
  display: flex;
  align-items: baseline;
  gap: 4px;
}

/* 「87 / 0」里那个斜杠要明显小于两边的数字，否则会被读成一个分数 */
.sep {
  font-size: 22px;
  color: var(--ink-4);
  margin: 0 2px;
}

.metric-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-4);
}

/* ---------------- 分布图 ---------------- */
.chart {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  height: 240px;
  margin-top: 20px;
  padding-top: 24px;
  border-bottom: 1px solid var(--line);
}

.bar-wrap {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
}

.bar-cnt {
  font-size: 12px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

.bar {
  width: 100%;
  min-height: 2px;
  background: var(--accent);
  border-radius: 3px 3px 0 0;
  transition: height 0.3s ease;
}

.bar-label {
  font-size: 11px;
  color: var(--ink-4);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  margin-bottom: -22px;
  padding-top: 6px;
}

/* ---------------- 正确率 ---------------- */
.mono {
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  color: var(--ink-3);
}

.rate {
  font-variant-numeric: tabular-nums;
  font-size: 13px;
}

.rate-good { color: var(--ok); }
.rate-mid { color: var(--warn); }
.rate-bad { color: var(--bad); font-weight: 600; }

@media (max-width: 900px) {
  .metric-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric-value {
    font-size: 30px;
  }

  .head-actions {
    width: 100%;
  }
}
</style>
