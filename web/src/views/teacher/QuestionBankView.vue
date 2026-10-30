<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { listQuestions, createQuestion } from '@/api'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const loading = ref(true);
const loadError = ref('');
const questions = ref([]);
const filter = reactive({ subject: '', type: '' });

const dialogVisible = ref(false);
const formRef = ref(null);
const creating = ref(false);

const form = reactive({
  subject: '',
  type: 'single',
  stem: '',
  options: [
    { key: 'A', text: '' },
    { key: 'B', text: '' },
    { key: 'C', text: '' },
    { key: 'D', text: '' }
  ],
  answer: '',
  // 多选时复选框绑的是数组，提交要的是 'ABC' 这种字符串。
  // 保留两份状态并在 change 时同步，是因为 el-checkbox-group 只认数组，
  // 而后端的 GradingRule 只认字符集合 —— 两边都不肯让步。
  answerArr: [],
  score: 5,
  difficulty: 1
});

const rules = {
  subject: [{ required: true, message: '请输入科目', trigger: 'blur' }],
  stem: [{ required: true, message: '请输入题干', trigger: 'blur' }]
};

const typeText = { single: '单选', multiple: '多选', judge: '判断' };

// 判断题没有选项，答案只能是 T / F；单选多选的答案必须是已有选项的字母。
// 把这条约束写进前端不是为了替代后端校验，而是为了让人在提交前就知道错了 ——
// 等到后端回一句「答案不合法」，人已经想不起来刚才勾的是哪个了。
const answerChoices = computed(() => {
  if (form.type === 'judge') {
    return [
      { key: 'T', text: '正确' },
      { key: 'F', text: '错误' }
    ];
  }
  return form.options.filter((o) => o.text.trim()).map((o) => ({ key: o.key, text: o.text }));
});

const isMultiple = computed(() => form.type === 'multiple');

// 分页状态。题库比考试列表更容易超过一页 —— 一位老师攒几十道题是常态，
// 所以这一页的分页条不是「以防万一」，而是日常会用到的控件。
const page = ref(1);
const size = ref(20);
const total = ref(0);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const params = { page: page.value, size: size.value };
    if (filter.subject) params.subject = filter.subject;
    if (filter.type) params.type = filter.type;
    let p = await listQuestions(params);
    // 改了筛选条件之后总数会变少，原先停在第 3 页可能已经越界。
    // 越界时后端返回空数组，而空态写的是「没有符合条件的题目」——
    // 那句在「其实有，只是这页没有」的时候是错的，所以退回第一页重查。
    if (!p.items.length && page.value > 1) {
      page.value = 1;
      p = await listQuestions({ ...params, page: 1 });
    }
    questions.value = p.items;
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

// 换筛选条件时必须回到第一页：停在第 5 页换一个只有 3 条结果的科目，
// 用户看到的是空表格，会以为「这个科目没题」。
function onFilter() {
  page.value = 1;
  load();
}

function openCreate() {
  form.subject = '';
  form.type = 'single';
  form.stem = '';
  form.options = [
    { key: 'A', text: '' },
    { key: 'B', text: '' },
    { key: 'C', text: '' },
    { key: 'D', text: '' }
  ];
  form.answer = '';
  form.answerArr = [];
  form.score = 5;
  form.difficulty = 1;
  dialogVisible.value = true;
}

// 切换题型时要清掉不合适的答案，否则会把「多选 ABCD」带到判断题上
function onTypeChange() {
  form.answer = '';
  form.answerArr = [];
}

async function doCreate() {
  if (!formRef.value) return;
  try {
    await formRef.value.validate();
  } catch {
    return;
  }

  if (!form.answer) {
    ElMessage.warning('请选择正确答案');
    return;
  }

  // 判断题的 options 必须是 null —— 后端 schema 里判断题的 options 就是 NULL，
  // 传一个空数组进去会在 PaperItem 里变成一个 '[]' 字符串，前端还得再判一次。
  let optionsPayload = null;
  if (form.type !== 'judge') {
    const filled = form.options.filter((o) => o.text.trim());
    if (filled.length < 2) {
      ElMessage.warning('至少需要两个有内容的选项');
      return;
    }
    optionsPayload = JSON.stringify(filled);
  }

  creating.value = true;
  try {
    await createQuestion({
      subject: form.subject,
      type: form.type,
      stem: form.stem,
      options: optionsPayload,
      answer: form.answer,
      score: form.score,
      difficulty: form.difficulty
    });
    ElMessage.success('题目已创建');
    dialogVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    creating.value = false;
  }
}

function optionsPreview(raw) {
  if (!raw) return '—';
  try {
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return raw;
    return arr.map((o) => `${o.key}. ${o.text}`).join('  ');
  } catch {
    return raw;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <h2 class="page-title">题库管理</h2>
    <p class="page-desc">题目是共享的，同一道题可以出现在多场考试里，分值各自指定。</p>

    <div class="toolbar">
      <el-input v-model="filter.subject" placeholder="按科目筛选" clearable style="width: 160px" @keyup.enter="onFilter" />
      <el-select v-model="filter.type" placeholder="按题型筛选" clearable style="width: 140px">
        <el-option label="单选题" value="single" />
        <el-option label="多选题" value="multiple" />
        <el-option label="判断题" value="judge" />
      </el-select>
      <el-button @click="onFilter">查询</el-button>
      <div class="toolbar-spacer" />
      <el-button type="primary" @click="openCreate">新建题目</el-button>
    </div>

    <el-card shadow="never">
      <div>
        <SkeletonBlock v-if="loading" :rows="5" dense />

        <ErrorState
          v-else-if="loadError"
          title="题库加载失败"
          :message="loadError"
          @retry="load"
        />

        <el-table v-else-if="questions.length" :data="questions" stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="subject" label="科目" width="90" />
          <el-table-column label="题型" width="80">
            <template #default="{ row }">{{ typeText[row.type] || row.type }}</template>
          </el-table-column>
          <el-table-column prop="stem" label="题干" min-width="240" show-overflow-tooltip />
          <el-table-column label="选项" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ optionsPreview(row.options) }}</template>
          </el-table-column>
          <el-table-column prop="answer" label="答案" width="80" />
          <el-table-column prop="score" label="分值" width="70" />
          <el-table-column prop="difficulty" label="难度" width="70" />
        </el-table>

        <EmptyState
          v-else
          text="没有符合条件的题目。"
          hint="清掉上面的筛选条件再查一次，或者点「新建题目」。"
        />

        <!-- total 是后端 COUNT 出来的总题数；只有超过一页才画分页条 -->
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

    <el-dialog v-model="dialogVisible" title="新建题目" width="600px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="科目" prop="subject">
          <el-input v-model="form.subject" placeholder="如：Java" />
        </el-form-item>
        <el-form-item label="题型">
          <el-radio-group v-model="form.type" @change="onTypeChange">
            <el-radio value="single">单选题</el-radio>
            <el-radio value="multiple">多选题</el-radio>
            <el-radio value="judge">判断题</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="题干" prop="stem">
          <el-input v-model="form.stem" type="textarea" :rows="3" placeholder="输入题目内容" />
        </el-form-item>

        <el-form-item v-if="form.type !== 'judge'" label="选项">
          <div class="opt-list">
            <div v-for="(opt, i) in form.options" :key="opt.key" class="opt-row">
              <span class="opt-key">{{ opt.key }}</span>
              <el-input v-model="opt.text" :placeholder="`选项 ${opt.key}`" />
            </div>
          </div>
        </el-form-item>

        <el-form-item label="正确答案">
          <el-checkbox-group v-if="isMultiple" v-model="form.answerArr" @change="(v) => (form.answer = v.slice().sort().join(''))">
            <el-checkbox v-for="c in answerChoices" :key="c.key" :value="c.key">{{ c.key }}</el-checkbox>
          </el-checkbox-group>
          <el-radio-group v-else v-model="form.answer">
            <el-radio v-for="c in answerChoices" :key="c.key" :value="c.key">{{ c.key }}</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="分值">
          <el-input-number v-model="form.score" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="难度">
          <el-input-number v-model="form.difficulty" :min="1" :max="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.opt-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.opt-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.opt-key {
  width: 18px;
  font-weight: 600;
  color: #606266;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
