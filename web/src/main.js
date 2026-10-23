import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 注意 import 的来源：这里**不能**写 `from 'element-plus'`。
// 'element-plus' 的主入口（es/index.mjs）是一个 barrel —— 它把全部 100 多个组件
// 都 import 进来再统一导出，打包器无法证明那些模块没有副作用，于是一个都摇不掉。
// 实测：只把这两行的来源从 'element-plus' 换成深路径，vendor-element 从 810 KB 掉到百 KB 级。
// 深路径 = 只引入真正用到的那一个组件。
import { ElLoading } from 'element-plus/es/components/loading/index'
import 'element-plus/es/components/loading/style/css'

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App);
app.use(createPinia());
app.use(router);

// v-loading 是**指令**，不是模板里的组件，所以 unplugin-vue-components 自动按需
// 引入不到它（那个插件只解析 <el-xxx> 标签）。漏掉这一行不会报任何错，
// 只会让 6 个页面上的 loading 遮罩静默失效 —— 页面照常能用，只是请求期间一片空白。
// 所以这里显式注册，同时显式引入它自己的样式（指令的样式不会跟着组件样式走）。
app.use(ElLoading);

app.mount('#app');
