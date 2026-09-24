import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import LibraryView from '../views/LibraryView.vue'
import ToolsView from '../views/ToolsView.vue'
import AuthView from '../views/AuthView.vue'

// 三个功能区：智能问答（含欢迎态）/ 法条知识库 / 法律工具箱 + 登录页
// 设置页暂缓（C 端安全能力强制内置，不暴露配置入口）
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'chat', component: ChatView },
    { path: '/library', name: 'library', component: LibraryView },
    { path: '/tools', name: 'tools', component: ToolsView },
    { path: '/auth', name: 'auth', component: AuthView },
    // 兜底重定向：任何未知/失效路径（旧书签、手输错、重启前的深链）一律回首页，
    // 避免无匹配路由时的白屏——"必须手动输首页"体验的根因之一
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

// 已登录用户再进登录页 → 直接回首页。
// 场景：登录页被分享给朋友（或加书签/收藏），登录成功后再次打开该链接时，
// 不该还停在登录表单上。
router.beforeEach((to) => {
  if (to.name === 'auth' && localStorage.getItem('sl_token')) {
    return { path: '/' }
  }
  return true
})

export default router
