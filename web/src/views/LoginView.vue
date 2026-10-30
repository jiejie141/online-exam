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
    <!-- 左侧：品牌与说明。做成不对称的两栏而不是居中的一张卡 ——
         居中卡片是「一个表单」的样子，而这里想说的是「一个系统」。 -->
    <section class="login-intro">
      <div class="intro-top">
        <span class="brand-mark" aria-hidden="true"></span>
        <span class="intro-brand">在线考试系统</span>
      </div>

      <div class="intro-main">
        <p class="eyebrow">Online Examination</p>
        <h1 class="intro-title">
          出题、组卷、<br />交卷、判分。
        </h1>
        <p class="intro-desc">
          老师建卷发布，学生在时间窗内作答，交卷后自动判分并出统计。
          每一步都可以回头查，而不是只看得到一个总分。
        </p>
      </div>

      <div class="intro-foot">
        <p class="eyebrow">Demo Accounts</p>
        <dl class="demo-list">
          <div>
            <dt>老师</dt>
            <dd><code>t001</code> / <code>teacher123</code></dd>
          </div>
          <div>
            <dt>学生</dt>
            <dd><code>s001</code> / <code>student123</code></dd>
          </div>
        </dl>
      </div>
    </section>

    <!-- 右侧：表单。标签不用 el-form-item 的 label 而用上方的 eyebrow，
         这样输入框能占满整行，视觉上更像一张「登记表」而不是「设置面板」。 -->
    <section class="login-panel">
      <div class="panel-inner">
        <p class="eyebrow">Sign in</p>
        <h2 class="panel-title">登录</h2>
        <p class="panel-sub">请使用老师或学生账号登录</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
          <el-form-item prop="username" label="用户名">
            <el-input v-model="form.username" placeholder="t001 / s001" size="large" autofocus />
          </el-form-item>
          <el-form-item prop="password" label="密码">
            <!-- show-password 让用户能核对输入；@keyup.enter 让回车能提交 -->
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              size="large"
              show-password
              @keyup.enter="onSubmit"
            />
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            round
            class="login-btn"
            :loading="loading"
            @click="onSubmit"
          >
            登录
          </el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
}

/* ---------------- 左栏 ---------------- */
.login-intro {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 56px 64px;
  /* 极淡的径向渐变：纯色大块在浅底上会显得空，
     加一层几乎看不见的暖色晕染就有「纸」的厚度了。 */
  background:
    radial-gradient(120% 90% at 8% 0%, #fff6f1 0%, transparent 55%),
    radial-gradient(90% 80% at 100% 100%, #f3ece5 0%, transparent 60%),
    var(--paper);
  border-right: 1px solid var(--line);
}

.intro-top {
  display: flex;
  align-items: center;
  gap: 10px;
}

.intro-brand {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.intro-main {
  padding: 40px 0;
}

.intro-title {
  margin: 14px 0 0;
  font-size: clamp(38px, 5.4vw, 68px);
  font-weight: 500;
  letter-spacing: -0.045em;
  line-height: 1.02;
}

.intro-desc {
  margin: 26px 0 0;
  max-width: 44ch;
  color: var(--ink-3);
  font-size: 15px;
  line-height: 1.75;
}

.demo-list {
  margin: 10px 0 0;
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
}

.demo-list dt {
  font-size: 12px;
  color: var(--ink-4);
  margin-bottom: 2px;
}

.demo-list dd {
  margin: 0;
  font-size: 13px;
  color: var(--ink-2);
}

.demo-list code {
  font-family: var(--font-mono);
  font-size: 12px;
  padding: 1px 5px;
  border-radius: var(--r-xs);
  background: var(--surface);
  border: 1px solid var(--line);
}

/* ---------------- 右栏 ---------------- */
.login-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 40px;
  background: var(--surface);
}

.panel-inner {
  width: 100%;
  max-width: 340px;
}

.panel-title {
  margin: 8px 0 0;
  font-size: 30px;
  font-weight: 600;
  letter-spacing: -0.035em;
}

.panel-sub {
  margin: 8px 0 32px;
  color: var(--ink-3);
  font-size: 13px;
}

.login-btn {
  width: 100%;
  margin-top: 8px;
}

/* el-form-item 的 label 默认偏灰偏小，这里把它抬成和 .eyebrow 同一档，
   保证「用户名」这三个字和上面的大标题属于同一套字号体系。 */
:deep(.el-form-item__label) {
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-4);
  padding-bottom: 6px;
}

:deep(.el-form-item) {
  margin-bottom: 22px;
}

/* ---------------- 窄屏 ---------------- */
@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-intro {
    padding: 32px 24px 28px;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }

  .intro-main {
    padding: 24px 0 0;
  }

  .intro-desc,
  .intro-foot {
    display: none;
  }

  .login-panel {
    padding: 32px 24px 56px;
  }
}
</style>
