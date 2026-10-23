import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// 这一组用例全部围绕 B 档新加的答题卡，以及「加载失败该停在哪」这件事。
// 被测的是「学生看到什么、点了会发生什么」，不是 CSS 长什么样。

const routeMock = { params: { examId: '1' } };
const replaceMock = vi.fn();
const pushMock = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => ({ replace: replaceMock, push: pushMock })
}));

const startExam = vi.fn();
const saveAnswer = vi.fn();
const submitExam = vi.fn();
vi.mock('@/api', () => ({
  startExam: (...a) => startExam(...a),
  saveAnswer: (...a) => saveAnswer(...a),
  submitExam: (...a) => submitExam(...a)
}));

// ElMessageBox.confirm 会真的弹一个 DOM 对话框，测试里没法点。
// 这里换成可控的 spy，并把它收到的 tip 原样记下来断言 ——
// 「弹窗里到底写了什么」正是这次改动最要紧的部分。
const confirmSpy = vi.fn();
const warningSpy = vi.fn();
const successSpy = vi.fn();
const errorSpy = vi.fn();
vi.mock('@/utils/feedback', () => ({
  ElMessage: {
    warning: (...a) => warningSpy(...a),
    success: (...a) => successSpy(...a),
    error: (...a) => errorSpy(...a)
  },
  ElMessageBox: { confirm: (...a) => confirmSpy(...a) }
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ token: 't0ken' })
}));

import ExamTakingView from './ExamTakingView.vue'

function makePaper(n = 3) {
  const types = ['single', 'multiple', 'judge'];
  return Array.from({ length: n }, (_, i) => ({
    id: 100 + i,
    seq: i + 1,
    type: types[i % types.length],
    stem: `第 ${i + 1} 题题干`,
    options: JSON.stringify([{ key: 'A', text: '甲' }, { key: 'B', text: '乙' }]),
    score: 10
  }));
}

function okStart(paper = makePaper()) {
  startExam.mockResolvedValue({
    recordId: 7,
    status: 'ongoing',
    paper,
    deadlineAt: '2099-01-01T00:00:00'
  });
}

async function mountView() {
  const wrapper = mount(ExamTakingView, { attachTo: document.body });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.clearAllMocks();
  startExam.mockReset();
  saveAnswer.mockResolvedValue(undefined);
  submitExam.mockResolvedValue({ score: null });
  confirmSpy.mockResolvedValue('confirm');
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('答题卡', () => {
  it('每题一个格子，序号从 1 开始', async () => {
    okStart();
    const wrapper = await mountView();
    const cells = wrapper.findAll('.ac-cell');
    expect(cells).toHaveLength(3);
    expect(cells.map((c) => c.text())).toEqual(['1', '2', '3']);
  });

  it('已答的格子带 is-done，未答的不带 —— 两种状态必须能一眼分开', async () => {
    okStart();
    const wrapper = await mountView();
    // 第 1 题是单选，直接选 A
    const firstRadio = wrapper.findAll('.q-card')[0].find('input[type="radio"]');
    await firstRadio.setValue();
    await flushPromises();
    const cells = wrapper.findAll('.ac-cell');
    expect(cells[0].classes()).toContain('is-done');
    expect(cells[1].classes()).not.toContain('is-done');
  });

  it('点题号跳到对应题卡，并短暂高亮', async () => {
    okStart();
    const wrapper = await mountView();
    await wrapper.findAll('.ac-cell')[2].trigger('click');
    expect(wrapper.findAll('.q-card')[2].classes()).toContain('q-card-flash');
    expect(wrapper.findAll('.q-card')[0].classes()).not.toContain('q-card-flash');
  });

  it('标记是本地状态：点一下变「已标记」，再点回到「标记」', async () => {
    okStart();
    const wrapper = await mountView();
    const markBtn = () => wrapper.findAll('.q-mark')[0];
    expect(markBtn().text()).toBe('标记');
    expect(markBtn().attributes('aria-pressed')).toBe('false');

    await markBtn().trigger('click');
    expect(markBtn().text()).toBe('已标记');
    expect(markBtn().attributes('aria-pressed')).toBe('true');
    // 标记只影响本地展示，绝不能顺手提交给后端（后端没有这个字段）
    expect(saveAnswer).not.toHaveBeenCalled();

    await markBtn().trigger('click');
    expect(markBtn().text()).toBe('标记');
  });

  it('已答 + 标记能同时成立（两个类都要在，不能互相覆盖）', async () => {
    okStart();
    const wrapper = await mountView();
    await wrapper.findAll('.q-card')[0].find('input[type="radio"]').setValue();
    await wrapper.findAll('.q-mark')[0].trigger('click');
    await flushPromises();
    const cell = wrapper.findAll('.ac-cell')[0];
    expect(cell.classes()).toContain('is-done');
    expect(cell.classes()).toContain('is-marked');
  });
});

describe('交卷确认框', () => {
  it('有未答时把题号列出来，而不是只说一句「还有 N 题」', async () => {
    okStart(makePaper(4));
    const wrapper = await mountView();
    // 只答第 1 题，剩下 2/3/4 未答
    await wrapper.findAll('.q-card')[0].find('input[type="radio"]').setValue();
    await flushPromises();

    await wrapper.find('.bar .el-button').trigger('click');
    await flushPromises();

    expect(confirmSpy).toHaveBeenCalled();
    const tip = confirmSpy.mock.calls[0][0];
    expect(tip).toContain('还有 <b>3</b> 道题没有作答');
    // 三个未答题号必须都能在弹窗里看到
    expect(tip).toContain('<b class="mb-no">2</b>');
    expect(tip).toContain('<b class="mb-no">3</b>');
    expect(tip).toContain('<b class="mb-no">4</b>');
    // 已答的 1 不该出现
    expect(tip).not.toContain('<b class="mb-no">1</b>');
  });

  it('全部答完时按钮改成「交卷」，不再说「仍然交卷」', async () => {
    okStart(makePaper(1));
    const wrapper = await mountView();
    await wrapper.findAll('.q-card')[0].find('input[type="radio"]').setValue();
    await flushPromises();

    await wrapper.find('.bar .el-button').trigger('click');
    await flushPromises();

    const opts = confirmSpy.mock.calls[0][2];
    expect(opts.confirmButtonText).toBe('交卷');
    expect(opts.type).toBe('info');
    expect(confirmSpy.mock.calls[0][0]).toContain('全部题目都已作答');
  });

  it('取消就什么都不发生：不交卷', async () => {
    okStart();
    confirmSpy.mockRejectedValue('cancel');
    const wrapper = await mountView();
    await wrapper.find('.bar .el-button').trigger('click');
    await flushPromises();
    expect(submitExam).not.toHaveBeenCalled();
  });
});

describe('加载失败', () => {
  it('停在答题页并给重试按钮，不把人送回列表页', async () => {
    startExam.mockRejectedValue(new Error('考试不存在'));
    const wrapper = await mountView();
    expect(wrapper.find('.error-state, [role="alert"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('这张卷子没能打开');
    expect(wrapper.text()).toContain('考试不存在');
    // 失败时不能顺手 replace 走人：那样学生连错在哪都看不到
    expect(replaceMock).not.toHaveBeenCalled();
  });

  it('点重试会重新请求', async () => {
    startExam.mockRejectedValueOnce(new Error('网络断了'));
    const wrapper = await mountView();
    expect(startExam).toHaveBeenCalledTimes(1);
    okStart();
    await wrapper.find('[role="alert"] button').trigger('click');
    await flushPromises();
    expect(startExam).toHaveBeenCalledTimes(2);
  });
});

describe('作答与保存', () => {
  it('多选提交前排序：勾 B 再勾 A，发出去的是 AB 而不是 BA', async () => {
    startExam.mockResolvedValue({
      recordId: 7,
      status: 'ongoing',
      paper: [makePaper(2)[1]],
      deadlineAt: '2099-01-01T00:00:00'
    });
    const wrapper = await mountView();
    const boxes = wrapper.findAll('input[type="checkbox"]');
    await boxes[1].setValue(true);
    await boxes[0].setValue(true);
    await flushPromises();
    const last = saveAnswer.mock.calls[saveAnswer.mock.calls.length - 1];
    expect(last[2]).toBe('AB');
  });

  it('每答一题立刻保存，不攒到交卷', async () => {
    okStart();
    const wrapper = await mountView();
    expect(saveAnswer).not.toHaveBeenCalled();
    await wrapper.findAll('.q-card')[0].find('input[type="radio"]').setValue();
    await flushPromises();
    expect(saveAnswer).toHaveBeenCalledTimes(1);
    expect(saveAnswer.mock.calls[0][0]).toBe(7);
    expect(saveAnswer.mock.calls[0][1]).toBe(100);
  });
});
