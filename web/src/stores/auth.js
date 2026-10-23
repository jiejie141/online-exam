import { defineStore } from 'pinia'
import { login as loginApi } from '@/api'

// token 存 sessionStorage 而不是 localStorage：
// localStorage 永不过期，一旦被 XSS 偷走就等于永久拿到这个账号；
// sessionStorage 关掉标签页就没了，把窗口从「永久」压到「本次会话」。
// 这是有意的取舍：代价是开新标签页要重新登录。
const TOKEN_KEY = 'exam_token';
const USER_KEY = 'exam_user';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: sessionStorage.getItem(TOKEN_KEY) || '',
    user: JSON.parse(sessionStorage.getItem(USER_KEY) || 'null')
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    role: (state) => (state.user ? state.user.role : ''),
    realName: (state) => (state.user ? state.user.realName : ''),
    userId: (state) => (state.user ? state.user.userId : null)
  },

  actions: {
    async login(username, password) {
      const data = await loginApi(username, password);
      this.token = data.token;
      this.user = {
        userId: data.userId,
        role: data.role,
        realName: data.realName
      };
      sessionStorage.setItem(TOKEN_KEY, this.token);
      sessionStorage.setItem(USER_KEY, JSON.stringify(this.user));
      return this.user;
    },

    clear() {
      this.token = '';
      this.user = null;
      sessionStorage.removeItem(TOKEN_KEY);
      sessionStorage.removeItem(USER_KEY);
    }
  }
});
