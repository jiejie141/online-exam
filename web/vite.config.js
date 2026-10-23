import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 构建产物直接落进 Spring Boot 的静态资源目录。
// 这样 `mvn package` 打出来的 jar 自带前端，不需要额外的部署步骤 ——
// 也避免了「本地跑得通、打出来的包里没有页面」这种只在部署时才暴露的问题。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    // 关掉 sourcemap：它会显著增大产物，而生产包里不需要
    sourcemap: false
  },
  server: {
    port: 5173,
    // 开发时把 /api 和 /ws 代理到后端，避免 CORS。
    // 前端代码里因此永远写相对路径，不需要区分开发/生产环境。
    proxy: {
      '/api': { target: 'http://127.0.0.1:8081', changeOrigin: true },
      '/ws': { target: 'ws://127.0.0.1:8081', ws: true }
    }
  }
})
