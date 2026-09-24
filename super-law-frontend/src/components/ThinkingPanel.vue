<script setup>
/**
 * 思考面板（V9 公报问答台皮肤）
 * 相位格（phase）展示后端真实 stage 事件；折叠体内是 reasoning 推理流；
 * 流式期间自动展开滚动，完成自动收起为"已思考 N 秒"，点击回看
 */
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  message: { type: Object, required: true }
})

const open = ref(true)   // 默认展开（等待期可见进度不空等；结束后也保持展开，用户手动收起）
const bodyRef = ref(null)

// 思考内容增长时自动滚到底（仅展开态）
watch(() => props.message.reasoning, async () => {
  if (open.value && bodyRef.value) {
    await nextTick()
    bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  }
})

/** 有任何思考数据即渲染 */
function hasThinkData() {
  const m = props.message
  return m.streaming || m.reasoning || m.thinkSeconds > 0
}
</script>

<template>
  <div v-if="hasThinkData()" class="think">
    <!-- 相位格：当前链路阶段（V9 phase 样式，真实 stage 数据） -->
    <div class="phases" @click="open = !open">
      <span class="phase" :class="message.streaming ? 'run' : 'done'">
        <i></i>{{ message.streaming ? (message.stage || '拆解法律要件') : `已思考 ${message.thinkSeconds} 秒` }}
      </span>
      <span v-if="!message.streaming && message.citations && message.citations.length" class="phase done">
        <i></i>检索命中 {{ message.citations.length }} 条
      </span>
      <span class="phase-toggle">{{ open ? '收起' : '展开' }}</span>
    </div>
    <!-- 折叠体：模型推理流（reasoning_content 透传） -->
    <div v-show="open && message.reasoning" ref="bodyRef" class="think-body">
      <div class="think-label">思考过程</div>
      <div class="think-reasoning">{{ message.reasoning }}</div>
    </div>
  </div>
</template>

<style scoped>
.think { margin-top: 14px; }
.phases { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; cursor: pointer; user-select: none; }
.phase {
  display: flex; align-items: center; gap: 8px; border: 1px solid var(--line);
  padding: 6px 12px; font-family: var(--mono); font-size: 10px;
  letter-spacing: .12em; color: var(--faint); text-transform: uppercase;
  transition: all .45s var(--spring);
}
.phase i { width: 5px; height: 5px; background: currentColor; opacity: .45; }
.phase.run { color: var(--acc); border-color: rgba(30, 58, 138, .4); background: var(--acc-soft); }
.phase.run i { opacity: 1; animation: blk .9s steps(1) infinite; }
.phase.done { color: var(--soft); }
.phase.done i { opacity: 1; }
@keyframes blk { 50% { opacity: .15; } }
.phase-toggle {
  margin-left: auto; font-family: var(--mono); font-size: 9.5px;
  letter-spacing: .2em; color: var(--faint); text-transform: uppercase;
}
.phases:hover .phase-toggle { color: var(--acc); }

.think-body {
  margin-top: 10px; border: 1px solid var(--line); border-left: 3px solid var(--acc);
  background: rgba(22, 25, 31, .02); padding: 12px 14px;
  max-height: 200px; overflow-y: auto;
}
.think-label {
  font-family: var(--mono); font-size: 9px; letter-spacing: .3em;
  color: var(--faint); text-transform: uppercase; margin-bottom: 8px;
}
.think-reasoning {
  font-family: var(--mono); font-size: 11.5px; color: var(--soft);
  line-height: 1.9; white-space: pre-wrap;
}
</style>
