import { ref } from 'vue'

/**
 * 移动端抽屉状态
 * <p>
 * App.vue 挂载汉堡按钮/遮罩并持有状态；AppSidebar 在导航动作（切页/新建/打开历史/去登录）
 * 后调用 closeDrawer 收起，保证点完就走。
 * 模块级单例 ref：跨组件共享同一份状态，无需 provide/inject 或 store 膨胀。
 */
const drawerOpen = ref(false)

export function useDrawer() {
  return {
    drawerOpen,
    openDrawer: () => { drawerOpen.value = true },
    closeDrawer: () => { drawerOpen.value = false }
  }
}
