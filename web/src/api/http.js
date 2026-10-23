import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

// 统一的 axios 实例。所有请求都走相对路径 ——
// 开发时由 vite 的 proxy 转给 8081，生产时前端和后端同一个 jar，天然同源。
const http = axios.create({
  baseURL: '/api',
  timeout: 10000
});

// 请求拦截器：自动带上 token。
// 这里读的是 pinia store，不是 localStorage —— 单一数据源，
// 退出登录时只要清 store 就够，不用去追还有哪里存了副本。
http.interceptors.request.use((config) => {
  const auth = useAuthStore();
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`;
  }
  return config;
});

// 响应拦截器：只做两件事 —— 拆开 ApiResponse 的壳、把错误变成一句能看懂的话。
//
// 后端统一返回 { code, message, data }，code 为 0 表示成功。
// 如果每个页面都自己判断 res.data.code，那这个判断迟早会漏掉一处，
// 漏掉的那处就会把错误当成功、把 null 当数据往下传。所以只在这里判一次。
http.interceptors.response.use(
  (response) => {
    const body = response.data;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data;
      }
      return Promise.reject(new Error(body.message || '请求失败'));
    }
    return body;
  },
  (error) => {
    // 401 单独处理：token 过期或没登录，清掉本地状态送回登录页。
    // 注意不能用 router.push('/login') 硬跳，那样会丢掉当前地址；
    // 带上 redirect，登录后能回到原来那一页。
    if (error.response && error.response.status === 401) {
      const auth = useAuthStore();
      auth.clear();
      const current = router.currentRoute.value;
      if (current.name !== 'login') {
        router.push({ name: 'login', query: { redirect: current.fullPath } });
      }
      return Promise.reject(new Error('登录已失效，请重新登录'));
    }
    // 后端的错误信息在 response.data.message 里；网络错误没有 response。
    const msg =
      (error.response && error.response.data && error.response.data.message) ||
      (error.code === 'ECONNABORTED' ? '请求超时，请检查网络' : '') ||
      error.message ||
      '请求失败';
    return Promise.reject(new Error(msg));
  }
);

export default http;
