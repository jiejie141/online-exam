<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { listExams, createExam, publishExam, closeExam, listQuestions, addQuestionsToExam } from '@/api'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter();
const loading = ref(true);
// 列表请求失败和「一场考试都没有」看起来一模一样，但该做的事相反：
// 一个是刷新，一个是去新建。所以它们必须是两个分支。
const loadError = ref('');
const exams = ref([]);
const questions = ref([]);

// ---- 新建考试 ----
const createVisible = ref(false);
const createFormRef = ref(null);
const creating = ref(false);
const createForm = reactive({
  title: '',
  subject: '',
  range: [],
  durationMinutes: 60,
  passScore: 60
});

const createRules = {
  title: [{ required: true, message: '请输入考试名称', trigger: 'blur' }],
  subject: [{ required: true, message: '请输入科目', trigger: 'blur' }],
  range: [{ required: true, message: '请选择考试时间窗', trigger: 'change' }]
};

// ---- 组卷 ----
const composeVisible = ref(false);
const composing = ref(false);
const composeExam = ref(null);
const selectedQuestionIds = ref([]);
const composeScore = ref(10);

const statusText = { draft: '草稿', published: '已发布', closed: '已结束' };
// 题库里存的是 single/multiple/judge，直接绑到表格上会露出原始英文；
// 题库页有同一张映射表，两页要保持一致（同一个枚举翻译不能有两种说法）。
const typeText = { single: '单选', multiple: '多选', judge: '判断' };
const statusType = { draft: 'info', published: 'success', closed: 'warning' };

// 分页状态。接口返回 { items, total, page, size }，三个值都要留着：
// 少了 total 就画不出页码，少了 page 就不知道现在停在第几页。
const page = ref(1);
const size = ref(20);
const total = ref(0);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    let p = await listExams({ page: page.value, size: size.value });
    // 停在越界的页码上会拿到一页空数据，而 EmptyState 写的是「还没有考试」——
    // 那是假话，考试明明有，只是这页没有。退回第一页重取一次。
    // 只回退一次、不递归：递归会在内层请求进行时把外层的 loading 关掉，闪一下骨架屏。
    if (!p.items.length && page.value > 1) {
      page.value = 1;
      p = await listExams({ page: 1, size: size.value });
    }
    exams.value = p.items;
    total.value = p.total;
  } catch (e) {
    loadError.value = e.message;
  } finally {
    loading.value = false;
  }
}

function onPageChange(p) {
  page.value = p;
  load();
}

// 组卷弹窗要在一屏里把可选题列出来，所以一次要 100 条（后端上限也是 100）。
// 题库真的超过 100 道时，这里只列得下前 100 道 —— 弹窗里如实写出这个上限，
// 而不是让人以为「就这么点题」。
const COMPOSE_PICK_LIMIT = 100;
const questionTotal = ref(0);

async function loadQuestions() {
  try {
    const p = await listQuestions({ page: 1, size: COMPOSE_PICK_LIMIT });
    questions.value = p.items;
    questionTotal.value = p.total;
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function fmtTime(v) {
  if (!v) return '-';
  return String(v).replace('T', ' ').slice(0, 16);
}

function openCreate() {
  createForm.title = '';
  createForm.subject = '';
  createForm.range = [];
  createForm.durationMinutes = 60;
  createForm.passScore = 60;
  createVisible.value = true;
}

async function doCreate() {
  if (!createFormRef.value) return;
  try {
    await createFormRef.value.validate();
  } catch {
    return;
  }
  if (!createForm.range || createForm.range.length !== 2) {
    ElMessage.warning('请选择完整的考试时间窗');
    return;
  }

  creating.value = true;
  try {
    await createExam({
      title: createForm.title,
      subject: createForm.subject,
      // 后端要 LocalDateTime，用 'YYYY-MM-DDTHH:mm:ss'。
      // el-date-picker 的 value-format 已经配成这个格式，这里直接透传。
      startAt: createForm.range[0],
      endAt: createForm.range[1],
      durationMinutes: createForm.durationMinutes,
      passScore: createForm.passScore
    });
    ElMessage.success('考试已创建，接下来请给它组卷');
    createVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    creating.value = false;
  }
}

async function openCompose(exam) {
  composeExam.value = exam;
  selectedQuestionIds.value = [];
  composeScore.value = 10;
  composeVisible.value = true;
  if (!questions.value.length) {
    await loadQuestions();
  }
}

async function doCompose() {
  if (!selectedQuestionIds.value.length) {
    ElMessage.warning('请至少选择一道题');
    return;
  }
  composing.value = true;
  try {
    const added = await addQuestionsToExam(composeExam.value.id, selectedQuestionIds.value, composeScore.value);
    // 返回值是实际加进去的条数 —— 重复加的题会被唯一键挡掉并跳过，
    // 所以「选了几道」和「加进去几道」可能不一样，要如实告诉用户。
    ElMessage.success(`已加入 ${added} 道题`);
    composeVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    composing.value = false;
  }
}

async function doPublish(exam) {
  try {
    await ElMessageBox.confirm(
      `发布后学生就能在时间窗内进入「${exam.title}」答题了。确定发布吗？`,
      '确认发布',
      { confirmButtonText: '发布', cancelButtonText: '取消', type: 'info' }
    );
  } catch {
    return;
  }
  try {
    await publishExam(exam.id);
    ElMessage.success('已发布');
    await load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function doClose(exam) {
  try {
    await ElMessageBox.confirm(
      `结束后学生不能再进入「${exam.title}」，已经交的卷子不受影响。确定结束吗？`,
      '确认结束',
      { confirmButtonText: '结束考试', cancelButtonText: '取消', type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    await closeExam(exam.id);
    ElMessage.success('考试已结束');
    await load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function gotoStats(exam) {
  router.push({ name: 'teacher-stats', params: { examId: exam.id } });
}

const composeTotal = computed(() => selectedQuestionIds.value.length * (composeScore.value || 0));

onMounted(load);
</script>

<template>
  <div>
    <h2 class="page-title">考试管理</h2>
    <p class="page-desc">建考试 → 组卷 → 发布，三步之后学生才能看到这场考试。</p>

    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建考试</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <div>
        <SkeletonBlock v-if="loading" :rows="5" dense />

        <ErrorState
          v-else-if="loadError"
          title="考试列表加载失败"
          :message="loadError"
          @retry="load"
        />

        <el-table v-else-if="exams.length" :data="exams" stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="title" label="考试名称" min-width="180" />
          <el-table-column prop="subject" label="科目" width="100" />
          <el-table-column label="开始" width="150">
            <template #default="{ row }">{{ fmtTime(row.startAt) }}</template>
          </el-table-column>
          <el-table-column label="结束" width="150">
            <template #default="{ row }">{{ fmtTime(row.endAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusType[row.status] || 'info'" size="small">
                {{ statusText[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openCompose(row)">组卷</el-button>
              <el-button
                v-if="row.status === 'draft'"
                link
                type="primary"
                size="small"
                @click="doPublish(row)"
              >
                发布
              </el-button>
              <el-button
                v-if="row.status === 'published'"
                link
                type="warning"
                size="small"
                @click="doClose(row)"
              >
                结束
              </el-button>
              <el-button link type="primary" size="small" @click="gotoStats(row)">成绩统计</el-button>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState v-else text="还没有考试。" hint="点上面的「新建考试」开始：建考试 → 组卷 → 发布。" />

        <!-- 分页条只在真的不止一页时出现：一页装得下的时候它只是一行噪音。
             total 是后端 COUNT 出来的总数，不是本页条数 —— 拿本页条数画页码
             会出现「永远只有 1 页」的假象。 -->
        <div v-if="total > size" class="pager">
          <el-pagination
            layout="total, prev, pager, next"
            :total="total"
            :page-size="size"
            :current-page="page"
            background
            @current-change="onPageChange"
          />
        </div>
      </div>
    </el-card>

    <!-- 新建考试 -->
    <el-dialog v-model="createVisible" title="新建考试" width="520px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="96px">
        <el-form-item label="考试名称" prop="title">
          <el-input v-model="createForm.title" placeholder="如：Java 基础期中测试" />
        </el-form-item>
        <el-form-item label="科目" prop="subject">
          <el-input v-model="createForm.subject" placeholder="如：Java" />
        </el-form-item>
        <el-form-item label="考试时间" prop="range">
          <!-- value-format 必须是 'YYYY-MM-DDTHH:mm:ss'，后端字段是 LocalDateTime，
               Jackson 只认这个格式；差一个 T 或秒就会反序列化失败 -->
          <el-date-picker
            v-model="createForm.range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="考试时长">
          <el-input-number v-model="createForm.durationMinutes" :min="1" :max="600" />
          <span class="unit">分钟</span>
        </el-form-item>
        <el-form-item label="及格分">
          <el-input-number v-model="createForm.passScore" :min="0" :max="1000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 组卷 -->
    <el-dialog v-model="composeVisible" title="组卷" width="640px">
      <p class="dialog-tip">
        给「{{ composeExam && composeExam.title }}」选题。分值在这里指定，
        同一道题在不同考试里可以给不同权重。
      </p>
      <el-form label-width="96px">
        <el-form-item label="每题分值">
          <el-input-number v-model="composeScore" :min="1" :max="100" />
        </el-form-item>
      </el-form>
      <el-table :data="questions" max-height="320" size="small" @selection-change="(v) => (selectedQuestionIds = v.map((x) => x.id))">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="stem" label="题干" min-width="240" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ typeText[row.type] || row.type }}</template>
        </el-table-column>
        <el-table-column prop="subject" label="科目" width="90" />
      </el-table>
      <!-- 组卷弹窗一次只列 100 道题（后端单页上限）。题库真的超过 100 道时
           必须说出来，否则老师会以为「我的题库就这么点题」，然后去重复出题。 -->
      <p v-if="questionTotal > COMPOSE_PICK_LIMIT" class="dialog-tip">
        题库共 {{ questionTotal }} 道，这里只列出最近的前 {{ COMPOSE_PICK_LIMIT }} 道。
        要选更早的题，请先去「题库管理」确认题目还在。
      </p>
      <div class="compose-sum">
        已选 {{ selectedQuestionIds.length }} 题，合计 {{ composeTotal }} 分
      </div>
      <template #footer>
        <el-button @click="composeVisible = false">取消</el-button>
        <el-button type="primary" :loading="composing" @click="doCompose">加入试卷</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.unit {
  margin-left: 8px;
  color: #909399;
  font-size: 13px;
}

.dialog-tip {
  margin: 0 0 16px;
  color: #909399;
  font-size: 13px;
  line-height: 1.6;
}

.compose-sum {
  margin-top: 12px;
  text-align: right;
  color: #606266;
  font-size: 13px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
