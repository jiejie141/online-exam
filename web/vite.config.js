import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'

// 构建产物直接落进 Spring Boot 的静态资源目录。
// 这样 `mvn package` 打出来的 jar 自带前端，不需要额外的部署步骤 ——
// 也避免了「本地跑得通、打出来的包里没有页面」这种只在部署时才暴露的问题。

// ============================================================================
// 按需引入：为什么不能直接用官方 ElementPlusResolver
//
// 官方 resolver 在 element-plus >= 1.1.0-beta.1 上返回的 `from` 是 `element-plus/es`，
// 也就是 es/index.mjs 这个 **barrel**。barrel 为了导出全部组件，必须先 import 全部组件，
// 打包器无法证明那些模块没副作用，于是一个都摇不掉 —— 实测让 ElTransfer / ElCascader /
// ElTree / ElUpload 这些项目里根本没写的组件全都进了 vendor-element（810 KB）。
//
// 所以这里自己写一个 resolver：把 <el-xxx> 解析到**单组件深路径**
// `element-plus/es/components/<dir>/index`。深路径入口只 import 它自己那条依赖链。
//
// 组件名到目录名不是简单 kebab-case：ElTableColumn 在 table/ 里、ElFormItem 在 form/ 里、
// ElOption 在 select/ 里。所以这里做一个「扫一遍目录、记住谁导出了这个名字」的索引，
// 而不是硬编码一张表 —— 以后新写一个组件也不需要回来改配置。
// ============================================================================
const EP_ROOT = fileURLToPath(new URL('./node_modules/element-plus/es/components', import.meta.url))

/** @type {Map<string, string> | null} 组件导出名 -> 组件目录名 */
let exportIndex = null

function buildExportIndex() {
  const index = new Map();
  for (const dir of fs.readdirSync(EP_ROOT)) {
    const entry = path.join(EP_ROOT, dir, 'index.mjs');
    if (!fs.existsSync(entry)) continue;
    const src = fs.readFileSync(entry, 'utf8');
    // 只看 export { ... } 那一行，避免把内部变量的引用也算进来
    const block = /export\s*\{([\s\S]*?)\}/.exec(src);
    if (!block) continue;
    for (const part of block[1].split(',')) {
      const name = part.split(/\s+as\s+/).pop().trim();
      if (/^El[A-Z]/.test(name) && !index.has(name)) index.set(name, dir);
    }
  }
  return index
}

function resolveElementComponent(name) {
  if (!/^El[A-Z]/.test(name)) return undefined;
  if (!exportIndex) exportIndex = buildExportIndex();
  const dir = exportIndex.get(name);
  if (!dir) return undefined;
  return {
    name,
    // 深路径：只拉这一个组件的依赖链
    from: `element-plus/es/components/${dir}/index`,
    // 样式同样走单组件入口；它内部会 import base 与自己依赖的组件样式
    sideEffects: [`element-plus/es/components/${dir}/style/css`]
  }
}

export default defineConfig({
  plugins: [
    vue(),
    // 模板里的 <el-xxx> 由它自动注册，不用在 main.js 里 app.use(ElementPlus)
    // （那会把 100 多个组件全打进包里）。
    Components({
      resolvers: [resolveElementComponent],
      // 生成物放进 node_modules/.tmp 而不是仓库里：它是构建缓存，不是源码。
      dts: false,
      dirs: []
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    // 关掉 sourcemap：它会显著增大产物，而生产包里不需要
    sourcemap: false,
    rollupOptions: {
      output: {
        // 按「多久变一次」分包，而不是按路由切：
        // vue / element-plus 这类依赖只有在升级时才变，缓存命中率比页面 chunk 高得多。
        // 路由级 chunk 交给 vite 默认行为（每个 view 一个），已经足够细。
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element'
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('/pinia/') || id.includes('@vue/')) return 'vendor-vue'
          if (id.includes('axios')) return 'vendor-axios'
          return 'vendor'
        }
      }
    }
  },
  server: {
    port: 5173,
    // 开发时把 /api 和 /ws 代理到后端，避免 CORS。
    // 前端代码里因此永远写相对路径，不需要区分开发/生产环境。
    proxy: {
      '/api': { target: 'http://127.0.0.1:8081', changeOrigin: true },
      '/ws': { target: 'ws://127.0.0.1:8081', ws: true }
    }
  },
  test: {
    // jsdom 而不是 happy-dom：Element Plus 的部分组件依赖真实的 DOM API（如
    // getComputedStyle、ResizeObserver 的桩），jsdom 覆盖得更全。
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{js,ts}'],
    setupFiles: ['./src/test/setup.js'],
    // element-plus 必须内联。按需引入之后，每个组件都带一条
    // `import 'element-plus/es/components/<x>/style/css'`，而那条语句最终指向
    // 一个 .css 文件。vitest 默认把 node_modules 里的包当外部依赖交给 Node 加载，
    // Node 不认识 .css，于是整个测试文件在「收集阶段」就挂了 ——
    // 报的是 Unknown file extension ".css"，看起来像环境问题，其实是外部化策略。
    server: {
      deps: {
        inline: [/element-plus/]
      }
    }
  }
})
