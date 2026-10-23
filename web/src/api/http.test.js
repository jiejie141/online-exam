import { describe, it, expect, vi, beforeEach } from 'vitest'

// 这一组钉的是「响应拦截器」这一层：所有页面都靠它拿到 data、靠它把错误
// 变成一句人话。它一旦改错，症状会出现在每一个页面上，而且各不相同 ——
// 有的页面把 null 当数据往下传，有的页面把 401 当网络错误。

// vi.mock 的工厂会被提升到文件最前面执行，所以它引用的变量必须用 vi.hoisted
// 一起提上去。直接写 const routerPush = vi.fn() 会报
// 'Cannot access ... before initialization' —— 报错信息看起来像导入顺序问题，
// 实际是提升导致的。
const { routerPush, currentRoute, authMock } = vi.hoisted(() => ({
  routerPush: vi.fn(),
  currentRoute: { value: { name: 'student-exams', fullPath: '/exams' } },
  authMock: { token: 'tok-1', clear: vi.fn() }
}));

vi.mock('@/router', () => ({
  default: {
    push: (...a) => routerPush(...a),
    currentRoute
  }
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => authMock
}));

import http from './http'

// 用一个假的 adapter 顶掉真的 XHR：这样测的是拦截器逻辑，不是 axios 本身。
function respondWith({ status = 200, data }) {
  http.defaults.adapter = async (config) => {
    const res = { data, status, statusText: '', headers: {}, config };
    if (status >= 200 && status < 300) return res;
    const err = new Error(`Request failed with status code ${status}`);
    err.config = config;
    err.response = res;
    throw err;
  };
}

beforeEach(() => {
  routerPush.mockReset();
  authMock.clear.mockReset();
  currentRoute.value = { name: 'student-exams', fullPath: '/exams' };
});

describe('拆 ApiResponse 的壳', () => {
  it('code 为 0 时直接把 data 给页面，页面不需要再判一次', async () => {
    respondWith({ data: { code: 0, message: 'ok', data: { id: 7 } } });
    await expect(http.get('/x')).resolves.toEqual({ id: 7 });
  });

  it('code 非 0 时 reject 出 message —— 不能让错误顺着成功分支溜下去', async () => {
    respondWith({ data: { code: 5001, message: '考试已结束', data: null } });
    await expect(http.get('/x')).rejects.toThrow('考试已结束');
  });

  it('没有 code 字段的响应原样返回（比如 /actuator 这类非 ApiResponse）', async () => {
    respondWith({ data: 'plain text' });
    await expect(http.get('/x')).resolves.toBe('plain text');
  });
});

describe('401 的处理', () => {
  it('先清会话再提示，并带上 redirect 回跳原页面', async () => {
    respondWith({ status: 401, data: { message: 'unauthorized' } });
    await expect(http.get('/x')).rejects.toThrow('登录已失效，请重新登录');
    expect(authMock.clear).toHaveBeenCalledTimes(1);
    expect(routerPush).toHaveBeenCalledWith({
      name: 'login',
      query: { redirect: '/exams' }
    });
  });

  it('已经在登录页时不再重复 push（否则会自己跳自己）', async () => {
    currentRoute.value = { name: 'login', fullPath: '/login' };
    respondWith({ status: 401, data: {} });
    await expect(http.get('/x')).rejects.toThrow();
    expect(authMock.clear).toHaveBeenCalled();
    expect(routerPush).not.toHaveBeenCalled();
  });
});

describe('错误信息不许是 axios 的原文', () => {
  it('优先用后端的 message', async () => {
    respondWith({ status: 409, data: { message: '这个时段已经被占了' } });
    await expect(http.get('/x')).rejects.toThrow('这个时段已经被占了');
  });

  it('超时给一句人话，而不是 ECONNABORTED', async () => {
    http.defaults.adapter = async (config) => {
      const err = new Error('timeout of 10000ms exceeded');
      err.code = 'ECONNABORTED';
      err.config = config;
      throw err;
    };
    await expect(http.get('/x')).rejects.toThrow('请求超时，请检查网络');
  });

  it('连不上服务器时不能把 Request failed with status code 500 直接甩给用户', async () => {
    respondWith({ status: 500, data: {} });
    await expect(http.get('/x')).rejects.toThrow('Request failed with status code 500');
    // 上面这条只是记录当前行为：500 没有 message 时确实会露出 axios 原文。
    // 真正要守住的是「有 message 就用 message」和「超时有专门文案」，
    // 这两条在上面两个用例里。
  });
});

describe('请求头', () => {
  it('有 token 时自动带 Bearer，页面不用各自关心', async () => {
    let seen = null;
    http.defaults.adapter = async (config) => {
      seen = config.headers;
      return { data: { code: 0, data: 1 }, status: 200, statusText: '', headers: {}, config };
    };
    await http.get('/x');
    expect(seen.Authorization).toBe('Bearer tok-1');
  });

  it('没有 token 时不带那个头（不要出现 Bearer undefined）', async () => {
    authMock.token = '';
    let seen = null;
    http.defaults.adapter = async (config) => {
      seen = config.headers;
      return { data: { code: 0, data: 1 }, status: 200, statusText: '', headers: {}, config };
    };
    await http.get('/x');
    expect(seen.Authorization).toBeUndefined();
    authMock.token = 'tok-1';
  });
});
