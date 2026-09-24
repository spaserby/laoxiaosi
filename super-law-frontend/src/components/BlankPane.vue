<script setup>
/**
 * 新建咨询干净页：DeepSeek 式极简新对话态
 * 无报纸容器/裁切标记/报头页脚，仅居中问候 + 居中输入框；
 * 与报纸首页（WelcomePane）区分：blankMode 由"新建咨询"触发，首次进入/点历史仍走首页
 */
import ChatInput from './ChatInput.vue'
</script>

<template>
  <div class="blank">
    <div class="blank-greet">
      <h2>你好，我是劳小司</h2>
      <p>写下你的法律问题，我会检索现行法条，以专业的法律优化人工智能大模型进行回答。</p>
    </div>
    <ChatInput centered />
  </div>
</template>

<style scoped>
.blank {
  flex: 1; min-height: 0; overflow-y: auto;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 38px; padding: 40px 24px;
}
.blank-greet { text-align: center; animation: blankIn .7s var(--soft-ease) both; }
.blank-greet h2 {
  font-family: var(--serif); font-weight: 900;
  font-size: clamp(26px, 3.4vw, 40px); letter-spacing: .02em; color: var(--ink);
}
.blank-greet p {
  margin-top: 14px; font-family: var(--sans); font-size: 13px;
  color: var(--faint); letter-spacing: .04em;
}
.blank :deep(.dockwrap) { width: 100%; animation: blankIn .7s .1s var(--soft-ease) both; }
@keyframes blankIn { from { opacity: 0; transform: translateY(16px); } to { opacity: 1; transform: none; } }
</style>
