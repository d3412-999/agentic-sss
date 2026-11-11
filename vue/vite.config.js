import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端端口必须与 application.yaml 的 server.port（环境变量 SERVER_PORT）一致。
// 若改了后端端口，请同步修改 vue/.env 的 VITE_API_TARGET，否则代理会打空。
// 注意：vite.config.js 里的 process.env 只包含 OS 环境变量，读 vue/.env
// 必须显式调用 loadEnv，否则 .env.example 里写的值根本不会生效。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || process.env.VITE_API_TARGET || 'http://127.0.0.1:8081'
  const frontendPort = Number(env.FRONTEND_PORT || process.env.FRONTEND_PORT || 5173)

  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: frontendPort,
      strictPort: false,
      watch: {
        // 有的编辑器保存时先生成临时目录/文件（如 .DefenseView.vue.xxx.tmpdir），
        // Windows 下被文件锁住时会让 Vite 的文件监听抛 EBUSY 直接退出 dev server，这里忽略掉。
        ignored: ['**/*.tmpdir/**', '**/.*.tmpdir/**', '**/*.tmp'],
      },
      proxy: {
        '/api': { target: apiTarget, changeOrigin: true },
        '/actuator': { target: apiTarget, changeOrigin: true },
        '/v3': { target: apiTarget, changeOrigin: true },
        '/swagger-ui': { target: apiTarget, changeOrigin: true },
      },
    },
    preview: {
      port: frontendPort,
    },
  }
})
