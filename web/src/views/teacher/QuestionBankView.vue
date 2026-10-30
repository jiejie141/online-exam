<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { listQuestions, createQuestion, updateQuestion, deleteQuestion, importQuestions } from '@/api'
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
// null = 新建；有值 = 正在改这一道题。用一个变量区分两种模式，
// 而不是两个独立的对话框 —— 两个框的字段、校验、提交前处理完全一样，
// 分成两份之后第一件事就是它们开始不一样。
const editingId = ref(null);

// ---- 批量导入 ----
// 交互刻意分成两步：预览 → 确认导入。
// 「确认导入」在预览通过校验之前是禁用的 —— 因为这一步的参数是一整批数据，
// 一旦写错，清理成本远高于多点一次按钮。批量操作的默认值应该是「什么都不做」。
const importVisible = ref(false);
const importText = ref('');
const importBusy = ref(false);
const importReport = ref(null);
const importPreviewed = ref(false);

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
  editingId.value = null;
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

/**
 * 进入编辑态。
 *
 * 判断题的 options 在库里是 null，表单里要补回四个空行 ——
 * 否则用户切到单选题时看不到选项输入框，会以为这个框坏了。
 */
function openEdit(row) {
  editingId.value = row.id;
  form.subject = row.subject;
  form.type = row.type;
  form.stem = row.stem;
  form.answer = row.answer || '';
  form.answerArr = row.type === 'multiple' ? String(row.answer || '').split('') : [];
  form.score = row.score ?? 5;
  form.difficulty = row.difficulty ?? 1;

  let parsed = [];
  if (row.options) {
    try {
      const arr = JSON.parse(row.options);
      if (Array.isArray(arr)) parsed = arr;
    } catch {
      // 库里的 options 坏了不该让整个页面打不开：退回空选项，
      // 用户重新填一遍就能救回来。这里不弹错，因为下面已经有提示
      parsed = [];
    }
  }
  form.options = parsed.length
    ? parsed
    : [
        { key: 'A', text: '' },
        { key: 'B', text: '' },
        { key: 'C', text: '' },
        { key: 'D', text: '' }
      ];
  dialogVisible.value = true;
}

/**
 * 删题。二次确认是必须的 —— 题库里一道题可能已被多场考试引用，
 * 删掉它带来的连锁反应比"少一个按钮"严重得多。后端还会再拦一道。
 */
async function doDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除第 ${row.id} 题吗？已被试卷引用的题会被后端拒绝，并说明在哪一份卷子里。`,
      '删除题目',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    await deleteQuestion(row.id);
    ElMessage.success('已删除');
    await load();
  } catch (e) {
    // 后端的拒绝理由（"还在 N 份试卷里"）比"删除失败"有用得多，原样展示
    ElMessage.error(e.message);
  }
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
    const payload = {
      subject: form.subject,
      type: form.type,
      stem: form.stem,
      options: optionsPayload,
      answer: form.answer,
      score: form.score,
      difficulty: form.difficulty
    };
    if (editingId.value === null) {
      await createQuestion(payload);
      ElMessage.success('题目已创建');
    } else {
      await updateQuestion(editingId.value, payload);
      ElMessage.success('已保存');
    }
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
    return arr.map((o) => o.key + '. ' + o.text).join('   ');
  } catch {
    return raw;
  }
}

// 难度用三个小方块表示，而不是数字 —— 「难度 2」需要一个映射表，
// 三格里有几格亮是一眼的事。
function difficultyText(n) {
  if (n >= 4) return '难';
  if (n >= 3) return '较难';
  if (n >= 2) return '中';
  return '易';
}

// ---------------- 批量导入 ----------------

function openImport() {
  importVisible.value = true;
  importReport.value = null;
  importPreviewed.value = false;
}

async function runImport(dryRun) {
  if (!importText.value.trim()) {
    ElMessage.warning('先粘贴 JSONL 内容，每行一个 JSON 对象');
    return;
  }
  importBusy.value = true;
  try {
    const report = await importQuestions({ jsonl: importText.value, dryRun });
    importReport.value = report;
    if (dryRun) {
      importPreviewed.value = true;
      if (report.clean) {
        ElMessage.success(`校验通过，${report.inserted} 道题可以导入`);
      } else {
        ElMessage.warning(`${report.issues.length} 条未通过校验，改完再预览一次`);
      }
      return;
    }
    ElMessage.success(`已导入 ${report.inserted} 道题`);
    importVisible.value = false;
    importReport.value = null;
    importPreviewed.value = false;
    importText.value = '';
    await load();
  } catch (e) {
    // 后端在真导入遇到坏数据时会整批拒绝，错误信息里带「第 N 行: 原因」。
    // 这条信息比任何前端校验都准确，原样展示，不要改写成「导入失败」
    ElMessage.error(e.message);
  } finally {
    importBusy.value = false;
  }
}

// 预览通过之前不允许确认导入。判据用后端返回的 clean，
// 而不是前端自己数 issues —— 判定规则只应该有一份
const canConfirmImport = computed(
  () => importPreviewed.value && importReport.value && importReport.value.clean
);

onMounted(load);
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <p class="eyebrow">Question Bank</p>
        <h1 class="page-title">题库管理</h1>
        <p class="page-desc">题目是共享的，同一道题可以出现在多场考试里，分值各自指定。</p>
      </div>
      <div class="head-actions">
        <el-button round @click="openImport">批量导入</el-button>
        <el-button type="primary" round @click="openCreate">新建题目</el-button>
      </div>
    </div>

    <div class="toolbar">
      <el-input
        v-model="filter.subject"
        placeholder="按科目筛选"
        clearable
        style="width: 180px"
        @keyup.enter="onFilter"
      />
      <el-select v-model="filter.type" placeholder="按题型筛选" clearable style="width: 150px">
        <el-option label="单选题" value="single" />
        <el-option label="多选题" value="multiple" />
        <el-option label="判断题" value="judge" />
      </el-select>
      <el-button round @click="onFilter">查询</el-button>
      <div class="toolbar-spacer" />
      <span class="count-note">共 <strong>{{ total }}</strong> 道题</span>
    </div>

    <SkeletonBlock v-if="loading" :rows="5" dense />

    <ErrorState
      v-else-if="loadError"
      title="题库加载失败"
      :message="loadError"
      @retry="load"
    />

    <div v-else-if="questions.length" class="panel panel-flush">
      <el-table :data="questions">
        <el-table-column label="ID" width="64">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column prop="subject" label="科目" width="110" />
        <el-table-column label="题型" width="80">
          <template #default="{ row }">{{ typeText[row.type] || row.type }}</template>
        </el-table-column>
        <el-table-column prop="stem" label="题干" min-width="260" show-overflow-tooltip />
        <el-table-column label="选项" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="opts">{{ optionsPreview(row.options) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="答案" width="80">
          <template #default="{ row }"><span class="answer">{{ row.answer }}</span></template>
        </el-table-column>
        <el-table-column label="分值" width="70">
          <template #default="{ row }"><span class="mono">{{ row.score }}</span></template>
        </el-table-column>
        <el-table-column label="难度" width="80">
          <template #default="{ row }">
            <span class="diff" :data-level="row.difficulty">{{ difficultyText(row.difficulty) }}</span>
          </template>
        </el-table-column>
        <!-- 在加这两列之前，这个页面的所有动作都是"新增"。
             一道题录错了只能再建一道正确的，错的那道永远留在库里、
             还会被自动组卷抽中 —— 这就是"只能往前、不能回头"的代价。 -->
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="doDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

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

    <EmptyState
      v-else
      text="没有符合条件的题目。"
      hint="清掉上面的筛选条件再查一次，或者点「新建题目」。"
    />

    <!-- 新建与编辑共用一个对话框：字段、校验、提交前处理完全一致，
         分成两个框之后第一件事就是它们开始不一样。
         关闭时把 editingId 清掉，否则下次点"新建"会带着上次的 id 去改题。 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建题目' : '编辑题目'"
      width="640px"
      @closed="editingId = null"
    >
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
              <el-input v-model="opt.text" :placeholder="'选项 ' + opt.key" />
            </div>
          </div>
        </el-form-item>

        <el-form-item label="正确答案">
          <el-checkbox-group
            v-if="isMultiple"
            v-model="form.answerArr"
            @change="(v) => (form.answer = v.slice().sort().join(''))"
          >
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
          <!-- 上限是 3 不是 5：库和后端的口径都是「1 易 2 中 3 难」，
               而自动组卷按 1/2/3 筛。填到 4/5 的题只有在「不限难度」时才会被抽中，
               却看不出哪里错 —— 把上限收到 3，问题在做题时就暴露。 -->
          <el-input-number v-model="form.difficulty" :min="1" :max="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button round @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" round :loading="creating" @click="doCreate">
          {{ editingId === null ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 批量导入：预览 → 确认导入，两步不能合并 -->
    <el-dialog v-model="importVisible" title="批量导入题库" width="760px">
      <p class="import-hint">
        每行一个 JSON 对象。先点「预览」——它会把解析、校验、去重、答案冲突全部跑一遍，
        但<b>一个字都不写库</b>。确认无误后再点「确认导入」。
      </p>
      <el-input
        v-model="importText"
        type="textarea"
        :rows="9"
        resize="vertical"
        placeholder='{"subject":"计算机网络","type":"single","stem":"TCP 三次握手的第二步是？","options":[{"key":"A","text":"SYN"},{"key":"B","text":"SYN+ACK"}],"answer":"B","difficulty":1}'
        class="mono-input"
      />

      <div v-if="importReport" class="import-report">
        <div class="report-line">
          <span>总行数 <strong>{{ importReport.total }}</strong></span>
          <span>可入库 <strong>{{ importReport.inserted }}</strong></span>
          <span>重复 <strong>{{ importReport.duplicate }}</strong></span>
          <span :class="{ 'is-bad': importReport.conflicting > 0 }">
            答案冲突 <strong>{{ importReport.conflicting }}</strong>
          </span>
          <span v-if="importReport.backfilled">回填指纹 <strong>{{ importReport.backfilled }}</strong></span>
        </div>

        <div v-if="importReport.conflicts.length" class="report-block">
          <h4>答案冲突 —— 这些题的答案和库里已有的不一致，需要你来裁决</h4>
          <p class="report-note">
            导入不会覆盖已有答案：覆盖会让「已按旧答案判过的卷」和新答案之间悄悄产生矛盾。
          </p>
          <ul>
            <li v-for="c in importReport.conflicts" :key="c.lineNo">
              第 {{ c.lineNo }} 行 · 库里 <span class="mono">{{ c.existingAnswer }}</span>
              → 本次 <span class="mono">{{ c.incomingAnswer }}</span> · {{ c.stem }}
            </li>
          </ul>
        </div>

        <div v-if="importReport.issues.length" class="report-block is-bad">
          <h4>未通过校验 {{ importReport.issues.length }} 条 —— 整批不会写入</h4>
          <ul>
            <li v-for="i in importReport.issues" :key="i.lineNo + i.reason">
              <span v-if="i.lineNo">第 {{ i.lineNo }} 行：</span>{{ i.reason }}
            </li>
          </ul>
          <p v-if="importReport.issuesTruncated" class="report-note">
            只显示了前 {{ importReport.issues.length }} 条，实际更多。分批导入更容易定位。
          </p>
        </div>

        <div v-if="importReport.samples.length" class="report-block">
          <h4>抽样核对（真实题库里确实有错题，建议扫一眼）</h4>
          <ul>
            <li v-for="s in importReport.samples" :key="s.lineNo">
              第 {{ s.lineNo }} 行 · {{ s.subject }} · {{ s.type }} · {{ s.stem }}
              → <span class="mono">{{ s.answer }}</span>
            </li>
          </ul>
        </div>
      </div>

      <template #footer>
        <el-button round @click="importVisible = false">取消</el-button>
        <el-button round :loading="importBusy" @click="runImport(true)">预览</el-button>
        <el-button
          type="primary"
          round
          :disabled="!canConfirmImport"
          :loading="importBusy"
          @click="runImport(false)"
        >
          确认导入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  gap: 8px;
}

.import-hint {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink-3);
  line-height: 1.7;
}

.mono-input :deep(textarea) {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  line-height: 1.6;
}

.import-report {
  margin-top: 12px;
  border-top: 1px solid var(--line, #e5e7eb);
  padding-top: 10px;
  font-size: 13px;
}

.report-line {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: var(--ink-3);
}

.report-line strong {
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.report-line .is-bad strong {
  color: #c0392b;
}

.report-block {
  margin-top: 10px;
}

.report-block h4 {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
}

.report-block.is-bad h4 {
  color: #c0392b;
}

.report-block ul {
  margin: 4px 0 0;
  padding-left: 18px;
  color: var(--ink-3);
  line-height: 1.7;
  max-height: 180px;
  overflow: auto;
}

.report-note {
  margin: 4px 0 0;
  color: var(--ink-4);
  font-size: 12px;
}

.count-note {
  font-size: 13px;
  color: var(--ink-4);
}

.count-note strong {
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.panel-flush {
  padding: 8px 10px 14px;
}

.mono {
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  color: var(--ink-3);
}

/* 选项预览压成一行浅色小字：它是「辅助确认」，不是要读的内容，
   和题干用同一个字重会把两者拉平。 */
.opts {
  font-size: 12px;
  color: var(--ink-3);
}

.answer {
  display: inline-block;
  min-width: 22px;
  padding: 1px 7px;
  border-radius: var(--r-xs);
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
}

.diff {
  font-size: 12px;
  color: var(--ink-3);
}

.diff[data-level='3'],
.diff[data-level='4'],
.diff[data-level='5'] {
  color: var(--warn);
}

.opt-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.opt-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.opt-key {
  width: 16px;
  font-weight: 600;
  color: var(--ink-3);
  font-family: var(--font-mono);
  font-size: 13px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
