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

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [s, d, a, p, exams] = await Promise.all([
      getStats(examId),
      getDistribution(examId),
      getAccuracy(examId),
      getProgress(examId),
      listExams()
    ]);
    // listExams 是分页接口，返回 { items, ... }；这里只用它找标题
    const examItems = (exams && exams.items) || [];
    stats.value = s;
    distribution.value = d || [];
    accuracy.value = a || [];
    progress.value = p;
    const found = examItems.find((e) => e.id === examId);
    examTitle.value = found ? found.title : `考试 #${examId}`;
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
    <div class="toolbar">
      <div>
        <h2 class="page-title" style="margin: 0">成绩统计</h2>
        <p class="page-desc" style="margin: 4px 0 0">{{ examTitle }}</p>
      </div>
      <div class="toolbar-spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button @click="router.replace({ name: 'teacher-exams' })">返回</el-button>
    </div>

    <SkeletonBlock v-if="loading" :rows="9" />

    <ErrorState
      v-else-if="loadError"
      title="统计数据加载失败"
      :message="loadError"
      @retry="load"
    />

    <template v-else>
      <!-- 概览：五张卡片。total 为 0 时明确写「还没有成绩」，
           而不是显示 0 分平均分 —— 那是假数据 -->
      <div class="cards">
        <div class="card">
          <div class="card-label">已判分人数</div>
          <div class="card-value">{{ stats ? stats.total : '—' }}</div>
        </div>
        <div class="card">
          <div class="card-label">平均分</div>
          <div class="card-value">{{ avgText }}</div>
        </div>
        <div class="card">
          <div class="card-label">最高 / 最低</div>
          <div class="card-value">
            <template v-if="stats && stats.total">{{ stats.maxScore }} / {{ stats.minScore }}</template>
            <template v-else>—</template>
          </div>
        </div>
        <div class="card">
          <div class="card-label">及格人数</div>
          <div class="card-value">{{ stats ? stats.passCount : '—' }}</div>
        </div>
        <div class="card">
          <div class="card-label">已交 / 进行中</div>
          <div class="card-value">
            <template v-if="progress">{{ progress.submitted }} / {{ progress.ongoing }}</template>
            <template v-else>—</template>
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-title">成绩分布</div>
        <div v-if="distribution.length" class="chart">
          <div v-for="d in distribution" :key="d.bucket" class="bar-wrap">
            <div class="bar-cnt">{{ d.cnt }}</div>
            <div class="bar" :style="{ height: barHeight(d) }"></div>
            <div class="bar-label">{{ d.bucket }}~{{ d.bucket + 9 }}</div>
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
        <div class="panel-title">逐题正确率</div>
        <p class="panel-desc">按正确率升序排列 —— 排在最前面的就是全班错得最多的题，讲评时先看它。</p>
        <el-table v-if="accuracy.length" :data="accuracy" stripe size="small">
          <el-table-column prop="questionId" label="题号" width="80" />
          <el-table-column prop="stem" label="题干" min-width="280" show-overflow-tooltip />
          <el-table-column prop="attempt" label="作答人数" width="100" />
          <el-table-column prop="correctCount" label="答对人数" width="100" />
          <el-table-column label="正确率" width="100">
            <template #default="{ row }">
              <span :class="rateClass(row)">{{ rateText(row) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState v-else text="还没有判分数据。" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px 18px;
}

.card-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.card-value {
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.panel {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px 24px;
  margin-bottom: 20px;
}

.panel-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
}

.panel-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: #909399;
}

.chart {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  height: 220px;
  margin-top: 16px;
  padding-top: 20px;
}

.bar-wrap {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  gap: 4px;
}

.bar-cnt {
  font-size: 12px;
  color: #606266;
}

.bar {
  width: 100%;
  min-height: 2px;
  background: #409eff;
  border-radius: 3px 3px 0 0;
  transition: height 0.3s ease;
}

.bar-label {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}

.rate-good {
  color: #67c23a;
}

.rate-mid {
  color: #e6a23c;
}

.rate-bad {
  color: #f56c6c;
  font-weight: 600;
}
</style>
