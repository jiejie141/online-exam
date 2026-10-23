import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  parseLocal,
  formatRemaining,
  parseOptions,
  choicesOf,
  typeLabel,
  joinMultiAnswer,
  splitMultiAnswer
} from './exam'

describe('parseLocal —— 不能把本地时间当 UTC', () => {
  it('按字段构造本地时间，而不是把字符串丢给 Date', () => {
    const d = parseLocal('2026-10-23T09:00:00');
    // 这是这条测试存在的全部理由：new Date('2026-10-23T09:00:00') 在东八区的
    // 部分浏览器上会被当成 UTC 解析，于是本地时间变成 17:00 —— 倒计时一进来就少 8 小时。
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(9); // 0 基，10 月是 9
    expect(d.getDate()).toBe(23);
    expect(d.getHours()).toBe(9);
    expect(d.getMinutes()).toBe(0);
  });

  it('字符串里带毫秒或时区尾巴也能解析（正则只取前 6 个字段）', () => {
    const d = parseLocal('2026-01-02T03:04:05.678');
    expect(d.getHours()).toBe(3);
    expect(d.getSeconds()).toBe(5);
  });

  it('空值返回 null，而不是 Invalid Date', () => {
    expect(parseLocal(null)).toBeNull();
    expect(parseLocal('')).toBeNull();
  });
});

describe('formatRemaining —— 负数与小时边界', () => {
  afterEach(() => vi.useRealTimers());

  it('一小时以内只显示 MM:SS，不顶一个 00:', () => {
    expect(formatRemaining(59)).toBe('00:59');
    expect(formatRemaining(600)).toBe('10:00');
    expect(formatRemaining(3599)).toBe('59:59');
  });

  it('满一小时才补出 HH:MM:SS', () => {
    expect(formatRemaining(3600)).toBe('01:00:00');
    expect(formatRemaining(3661)).toBe('01:01:01');
  });

  it('负数钳到 00:00 —— 渲染出 -00:01 比 00:00 更让人慌', () => {
    expect(formatRemaining(-1)).toBe('00:00');
    expect(formatRemaining(-3600)).toBe('00:00');
  });

  it('undefined / null / 小数都不会崩', () => {
    expect(formatRemaining(undefined)).toBe('00:00');
    expect(formatRemaining(null)).toBe('00:00');
    expect(formatRemaining(90.9)).toBe('01:30');
  });
});

describe('parseOptions —— 一道题的数据坏了不该让整张卷子白屏', () => {
  it('正常 JSON 数组原样返回', () => {
    expect(parseOptions('[{"key":"A","text":"甲"}]')).toEqual([{ key: 'A', text: '甲' }]);
  });

  it('坏 JSON 返回空数组，而不是抛异常', () => {
    expect(parseOptions('{not json')).toEqual([]);
    expect(parseOptions('')).toEqual([]);
    expect(parseOptions(null)).toEqual([]);
  });

  it('是合法 JSON 但不是数组时也返回空数组（后端字段是字符串，可能被写成对象）', () => {
    expect(parseOptions('{"A":"甲"}')).toEqual([]);
  });
});

describe('choicesOf —— 判断题没有 options 也要能答', () => {
  it('判断题固定给「正确 / 错误」，答案键是 T / F', () => {
    expect(choicesOf({ type: 'judge', options: null })).toEqual([
      { key: 'T', text: '正确' },
      { key: 'F', text: '错误' }
    ]);
  });

  it('单选题读 options', () => {
    const opts = JSON.stringify([{ key: 'A', text: '甲' }]);
    expect(choicesOf({ type: 'single', options: opts })).toEqual([{ key: 'A', text: '甲' }]);
  });
});

describe('joinMultiAnswer / splitMultiAnswer —— 同一个选择只能有一种表示', () => {
  it('勾选顺序不影响结果：DAC 与 ACD 是同一个答案集合', () => {
    expect(joinMultiAnswer(['D', 'A', 'C'])).toBe('ACD');
    expect(joinMultiAnswer(['A', 'C', 'D'])).toBe('ACD');
  });

  it('不修改传入的数组（slice 的作用）', () => {
    const input = ['C', 'A'];
    joinMultiAnswer(input);
    expect(input).toEqual(['C', 'A']);
  });

  it('空答案拆出来是 [] 而不是 [""]', () => {
    // [''] 会让 el-checkbox-group 渲染出一个值为空串的幽灵选项，
    // 表现是「什么都没选，但列表中多一个空白项」。
    expect(splitMultiAnswer('')).toEqual([]);
    expect(splitMultiAnswer(null)).toEqual([]);
    expect(splitMultiAnswer(undefined)).toEqual([]);
  });

  it('拼好的字符串能原样拆回去', () => {
    const joined = joinMultiAnswer(['B', 'A']);
    expect(splitMultiAnswer(joined)).toEqual(['A', 'B']);
  });
});

describe('typeLabel', () => {
  it('三种题型都有中文名', () => {
    expect(typeLabel('single')).toBe('单选题');
    expect(typeLabel('multiple')).toBe('多选题');
    expect(typeLabel('judge')).toBe('判断题');
  });

  it('不认识的类型原样返回，而不是显示 undefined', () => {
    expect(typeLabel('essay')).toBe('essay');
  });
});
