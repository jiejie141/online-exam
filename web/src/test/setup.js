// vitest 的全局前置。jsdom 缺了几个浏览器 API，而 Element Plus 的组件会碰它们，
// 不补桩的话凡是渲染到那些组件的用例都会抛 'x is not a function' ——
// 报错信息指向的不是被测代码，而是测试环境，很容易被误当成业务 bug。
import { vi } from 'vitest'
import { config } from '@vue/test-utils'

if (!window.matchMedia) {
  window.matchMedia = (query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })
}

// Element Plus 的某些布局组件（el-table 列宽计算、el-dialog 滚动锁定）用到了它。
if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

// jsdom 没有实现滚动，而 el-dialog 打开时会调 scrollTo。
if (!window.HTMLElement.prototype.scrollTo) {
  window.HTMLElement.prototype.scrollTo = () => {}
}

// jsdom 没有实现 scrollIntoView，而答题卡的题号跳转用它。
// 不补的话点一次题号就抛 'scrollIntoView is not a function'。
if (!window.HTMLElement.prototype.scrollIntoView) {
  window.HTMLElement.prototype.scrollIntoView = () => {}
}

// v-loading 是 Element Plus 的**指令**，按需引入后由 main.js 里的
// `app.use(ElLoading)` 注册。单测只挂载组件、不走 main.js，
// 所以指令是未解析的 —— 不会崩，但每个用例都会往 stderr 打一行
// 'Failed to resolve directive: loading'，把真正的失败信息淹掉。
// 这里补一个空指令：它的视觉行为不在单测的验证范围里（那是浏览器验收的事）。
config.global.directives = { ...config.global.directives, loading: {} }
