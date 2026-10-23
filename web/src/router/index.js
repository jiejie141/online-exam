import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// 为什么用 hash 模式（/#/login）而不是 history 模式（/login）：
//
// 后端 SecurityConfig 的最后一条是 .anyRequest().authenticated()。
// history 模式下刷新 /exam/1 这种深链接，浏览器请求的是 /exam/1 这个**真实路径**，
// Spring Security 会先于静态资源处理把它拦成 401 —— 用户看到的是「刷新一下就要重新登录」。
// 修法只有两条：放行 "/**"（等于把 API 也一起放行了，绝对不行），
// 或者给后端加一个把未知路径转发到 index.html 的兜底控制器。
//
// hash 模式下 / 之后的内容浏览器不会发给服务端，服务端永远只看到 / 和 /assets/*，
// 放行范围能精确到几行，而且不需要任何兜底逻辑。
//
// 代价是地址栏有个 #。这是纯后端的考试系统，不值得为它引入一个 SPA 转发控制器。
const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  { path: '/', redirect: '/exams' },
  {
    path: '/exams',
    name: 'student-exams',
    component: () => import('@/views/student/ExamListView.vue'),
    meta: { role: 'student' }
  },
  {
    path: '/exam/:examId',
    name: 'exam-taking',
    component: () => import('@/views/student/ExamTakingView.vue'),
    meta: { role: 'student' }
  },
  {
    path: '/records/:recordId/result',
    name: 'exam-result',
    component: () => import('@/views/student/ExamResultView.vue'),
    meta: { role: 'student' }
  },
  {
    path: '/teacher/exams',
    name: 'teacher-exams',
    component: () => import('@/views/teacher/ExamManageView.vue'),
    meta: { role: 'teacher' }
  },
  {
    path: '/teacher/questions',
    name: 'teacher-questions',
    component: () => import('@/views/teacher/QuestionBankView.vue'),
    meta: { role: 'teacher' }
  },
  {
    path: '/teacher/exams/:examId/stats',
    name: 'teacher-stats',
    component: () => import('@/views/teacher/ExamStatsView.vue'),
    meta: { role: 'teacher' }
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue'), meta: { public: true } }
];

const router = createRouter({
  history: createWebHashHistory(),
  routes
});

// 全局前置守卫：三个检查，顺序不能换。
// 1) 没登录 → 去登录页，并把原本要去的地址记在 query 里（登录后跳回来）
// 2) 已登录但角色不对 → 送到他自己的首页，而不是登录页
//    （角色不对时送去登录页会让人以为自己被踢了，其实是走错门）
// 3) 已登录且访问 /login → 送到首页，避免登录后按返回键又看到登录页
router.beforeEach((to) => {
  const auth = useAuthStore();
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  if (to.meta.role && auth.isLoggedIn && auth.role !== to.meta.role) {
    return { name: auth.role === 'teacher' ? 'teacher-exams' : 'student-exams' };
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: auth.role === 'teacher' ? 'teacher-exams' : 'student-exams' };
  }
  return true;
});

export default router;
