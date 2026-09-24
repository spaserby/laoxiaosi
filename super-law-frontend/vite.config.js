import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 超级小律前端工程：dev 代理全部后端接口到 lab-chat-backend（8082）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 锁死端口：5173 被僵尸进程占用时直接报错退出，而非静默换到 5174——
    // 静默换端口会让旧标签页/旧书签永久 ERR_CONNECTION_REFUSED，"必须手动输首页"的根因之一
    strictPort: true,
    // day23：监听 0.0.0.0，手机连同一局域网可访问 http://<电脑IP>:5173 实测移动端
    host: true,
    proxy: {
      '/chat': 'http://localhost:8082',
      '/session': 'http://localhost:8082',
      '/law-article': 'http://localhost:8082',
      '/tools': 'http://localhost:8082',
      '/auth': 'http://localhost:8082',
      '/admin': 'http://localhost:8082',   // day21：管理端（向量库同步等）
      '/stats': 'http://localhost:8082'    // day22：首页事实栏统计
    }
  }
})
