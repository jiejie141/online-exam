<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();

// 登录页不显示导航栏 —— 一个还没登录的人看到「我的考试 / 题库管理」是没意义的。
const showShell = computed(() => auth.isLoggedIn && route.name !== 'login');

const homeName = computed(() => (auth.role === 'teacher' ? 'teacher-exams' : 'student-exams'));

function logout() {
  auth.clear();
  router.push({ name: 'login' });
}
</script>

<template>
  <div class="app-shell">
    <header v-if="showShell" class="app-header">
      <div class="header-inner">
        <RouterLink :to="{ name: homeName }" class="brand">
          在线考试系统
        </RouterLink>

        <nav class="nav">
          <template v-if="auth.role === 'student'">
            <RouterLink :to="{ name: 'student-exams' }" class="nav-link">我的考试</RouterLink>
          </template>
          <template v-else-if="auth.role === 'teacher'">
            <RouterLink :to="{ name: 'teacher-exams' }" class="nav-link">考试管理</RouterLink>
            <RouterLink :to="{ name: 'teacher-questions' }" class="nav-link">题库管理</RouterLink>
          </template>
        </nav>

        <div class="user-area">
          <span class="user-name">
            {{ auth.realName }}
            <span class="role-tag">{{ auth.role === 'teacher' ? '老师' : '学生' }}</span>
          </span>
          <el-button link type="primary" @click="logout">退出登录</el-button>
        </div>
      </div>
    </header>

    <main :class="showShell ? 'app-main' : 'app-main-full'">
      <RouterView />
    </main>
  </div>
</template>
