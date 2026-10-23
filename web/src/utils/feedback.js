// Element Plus 里有两类东西：模板里写的组件（<el-button>）和函数式调用的 API
// （ElMessage / ElMessageBox）。前者能被 unplugin-vue-components 自动识别并按需引入，
// 后者不行 —— 它不在模板里，插件看不到。
//
// 所以这里统一收口：要弹提示的地方都从本模块 import。
// 好处是「弹窗的样式有没有被引进来」这个问题只需要在这个文件里回答一次，
// 而不是在 6 个 view 里各引一遍、漏一个就少一份样式（漏了不报错，只是弹出的框没样式）。
//
// import 的来源必须是**深路径** `element-plus/es/components/<name>/index`：
// 从 'element-plus' 主入口取的话，barrel 会把全部组件一起拉进产物。
import { ElMessage } from 'element-plus/es/components/message/index'
import { ElMessageBox } from 'element-plus/es/components/message-box/index'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

export { ElMessage, ElMessageBox }
