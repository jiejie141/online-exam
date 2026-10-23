<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();

const formRef = ref(null);
const loading = ref(false);

const form = reactive({ username: '', password: '' });

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
};

async function onSubmit() {
  if (!formRef.value) return;
  // validate 返回 Promise，校验不过会 reject —— 这里用 await 会抛异常，
  // 所以用 try/catch 而不是 if (!ok) return，避免控制流看起来像出错了。
  try {
    await formRef.value.validate();
  } catch {
    return;
  }

  loading.value = true;
  try {
    const user = await auth.login(form.username, form.password);
    ElMessage.success(`欢迎，${user.realName}`);
    // 登录前想去的页面记在 redirect 里；没有就按角色去首页。
    const redirect = route.query.redirect;
    if (typeof redirect === 'string' && redirect) {
      router.replace(redirect);
    } else {
      router.replace({ name: user.role === 'teacher' ? 'teacher-exams' : 'student-exams' });
    }
  } catch (e) {
    ElMessage.error(e.message || '登录失败');
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="login-title">在线考试系统</h1>
      <p class="login-sub">请使用老师或学生账号登录</p>

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" autofocus />
        </el-form-item>
        <el-form-item prop="password">
          <!-- show-password 让用户能核对输入；@keyup.enter 让回车能提交 -->
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="onSubmit">
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #ecf5ff 0%, #f5f7fa 100%);
  padding: 24px;
}

.login-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 10px;
  padding: 40px 36px 32px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
}

.login-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
  text-align: center;
}

.login-sub {
  margin: 0 0 28px;
  text-align: center;
  color: #909399;
  font-size: 13px;
}

.login-btn {
  width: 100%;
}
</style>
