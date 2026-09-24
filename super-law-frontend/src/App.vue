<script setup>
/**
 * 应用外壳（V9 公报问答台皮肤）：冷白桌面背景 + 固定左栏 + 主区
 * ≤860px 侧栏变汉堡抽屉（首屏直出内容），
 * 遮罩点击 / 路由切换自动收起；汉堡按钮与抽屉样式见 theme.css。
 */
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { Menu } from 'lucide-vue-next'
import AppSidebar from './components/AppSidebar.vue'
import { useDrawer } from './composables/useDrawer'

const route = useRoute()
const { drawerOpen, openDrawer, closeDrawer } = useDrawer()

// 路由切换（导航/打开历史会话后跳回对话页）自动收起抽屉
watch(() => route.fullPath, closeDrawer)
</script>

<template>
  <!-- 桌面：格纸 + 颗粒 + 光晕（伪元素在 theme.css） -->
  <div class="desk"></div>

  <!-- 遮罩 + 汉堡按钮必须与抽屉同处 app-shell 层叠上下文：
       若放在 app-shell 外（兄弟节点），遮罩 z=58 会盖住 z=1 上下文内的抽屉（z=60 被压到 1），
       导致移动端"拉开抽屉屏幕变黑、点不动"。移入后层级：抽屉60 > 遮罩58 > 汉堡55 > 内容。 -->
  <div class="app-shell" :class="{ 'drawer-open': drawerOpen }">
    <div class="drawer-mask" :class="{ on: drawerOpen }" @click="closeDrawer"></div>
    <button class="menu-btn" aria-label="打开导航" @click="openDrawer">
      <Menu :size="18" />
    </button>
    <AppSidebar />
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>
