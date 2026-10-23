<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listAvailableExams, startExam } from '@/api'

const router = useRouter();
const loading = ref(false);
const exams = ref([]);
const startingId = ref(null);

async function load() {
  loading.value = true;
  try {
    exams.value = await listAvailableExams();
  } catch (e) {
    ElMessage.error(e.message);
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
    ElMessage.error(e.message);
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

onMounted(load);
</script>

<template>
  <div>
    <h2 class="page-title">我的考试</h2>
    <p class="page-desc">这里只显示当前时间窗内可以参加的考试。</p>

    <el-card shadow="never">
      <div v-loading="loading">
        <el-table v-if="exams.length" :data="exams" stripe>
          <el-table-column prop="title" label="考试名称" min-width="200" />
          <el-table-column prop="subject" label="科目" width="120" />
          <el-table-column label="开始时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.startAt) }}</template>
          </el-table-column>
          <el-table-column label="结束时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.endAt) }}</template>
          </el-table-column>
          <el-table-column label="时长" width="90">
            <template #default="{ row }">{{ row.durationMinutes }} 分钟</template>
          </el-table-column>
          <el-table-column label="总分" width="80">
            <template #default="{ row }">{{ row.totalScore }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                type="primary"
                size="small"
                :loading="startingId === row.id"
                @click="onStart(row)"
              >
                进入考试
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-else-if="!loading" class="empty-tip">
          当前没有可以参加的考试。
        </div>
      </div>
    </el-card>
  </div>
</template>
