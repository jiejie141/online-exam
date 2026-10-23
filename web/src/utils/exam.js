// 答题页里那些「算错了也不会报错、只会悄悄地错」的小逻辑，集中在这里。
//
// 抽出来的理由不是为了复用（只有一个页面用），而是为了能被测试直接钉住：
// 这些函数全都是纯函数，测它们不需要挂载组件、不需要假路由、不需要 mock 网络；
// 而它们恰恰是最容易写错的地方 —— 时区差 8 小时、倒计时自己减自己、多选拼成乱序字符串，
// 上线后表现都只是「数字有点怪」，不会有任何报错。

// 后端的考试时间是不带时区的本地时间字符串（2026-10-23T09:00:00）。
// 直接 new Date(s) 在部分浏览器上会被当成 UTC 解析 —— 东八区会整体差 8 小时，
// 表现为「倒计时一进来就少了 8 小时」。所以显式拆字段构造本地时间。
export function parseLocal(s) {
  if (!s) return null;
  const m = String(s).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  if (!m) return new Date(s);
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
}

// 剩余秒数 -> 显示文本。负数钳到 0：网络延迟会让服务端算出的剩余时间比本地略小，
// 渲染出「-00:01」比直接显示 00:00 更让人慌。
// 一小时以内用 MM:SS，超过一小时才显示 HH:MM:SS —— 绝大多数考试不到一小时，
// 前面永远顶着一个 00: 只是多占位置。
export function formatRemaining(seconds) {
  const s = Math.max(0, Math.floor(seconds || 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return h > 0 ? `${pad(h)}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`;
}

// 题目的 options 是后端存的 JSON 数组字符串。解析失败不能抛 ——
// 一道题的数据坏了不该让整张卷子白屏，退化成「没有选项」至少还能答题。
export function parseOptions(raw) {
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

// 判断题在数据库里 options 是 NULL，答案用 T/F。
// 这里补成两个选项，让界面上和单选长得一样，交互也一致。
export function choicesOf(q) {
  if (q.type === 'judge') {
    return [{ key: 'T', text: '正确' }, { key: 'F', text: '错误' }];
  }
  return parseOptions(q.options);
}

export function typeLabel(t) {
  return { single: '单选题', multiple: '多选题', judge: '判断题' }[t] || t;
}

// 多选题的答案在后端是一个**字符串**，按字符集合比较（见 GradingRule）。
// 前端提交前必须排序：勾选顺序是 D、A、C 时拼成 "DAC" 也是对的集合，
// 但同样的答案会因为顺序不同而生成不同的字符串，既不利于排查也不利于比对。
// 排序后 "ACD" 永远唯一。
export function joinMultiAnswer(values) {
  return (values || []).slice().sort().join('');
}

// 多选题已选的字符 -> 复选组的数组值。空答案要返回 []，不是 ['']：
// [''] 会让 el-checkbox-group 渲染出一个值为空串的幽灵选项。
export function splitMultiAnswer(value) {
  return String(value || '').split('').filter(Boolean);
}
