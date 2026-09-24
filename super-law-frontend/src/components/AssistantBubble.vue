<script setup>
/**
 * AI 答复（V9 公报问答台皮肤）：mono 标签行（HIT/RELATED/GROUNDED 元数据）+ 衬线两端对齐正文 + 脚注
 * 数据逻辑与 Markdown 渲染、结构化引用优先、正则降级、ThinkingPanel
 */
import { computed } from 'vue'
import { marked } from 'marked'
import { Scale, ShieldCheck } from 'lucide-vue-next'
import CitationCard from './CitationCard.vue'
import ThinkingPanel from './ThinkingPanel.vue'

const props = defineProps({
  message: { type: Object, required: true }
})

marked.setOptions({ breaks: true })

const html = computed(() => marked.parse(props.message.content || ''))

/** 结构化引用（citation 事件）优先；正则解析降级兜底 */
const citations = computed(() => {
  const structured = props.message.citations
  if (Array.isArray(structured) && structured.length) {
    return structured.slice(0, 5).map(c => ({ lawName: c.lawName, articleNo: c.articleNo, text: c.text, score: c.score, versionInfo: c.versionInfo || '', chapterInfo: c.chapterInfo || '', sectionInfo: c.sectionInfo || '' }))
  }
  const re = /《([^》]{2,30})》\s*(第[一二三四五六七八九十百零\d]+条)/g
  const seen = new Set()
  const list = []
  let m
  while ((m = re.exec(props.message.content || '')) !== null) {
    const key = m[1] + m[2]
    if (!seen.has(key)) {
      seen.add(key)
      list.push({ lawName: m[1], articleNo: m[2] })
    }
    if (list.length >= 3) break
  }
  return list
})

/** ai-meta 元数据标签（真实数据）：命中数 / TOP-1 相关度 / GROUNDED */
const topScore = computed(() => {
  const s = (props.message.citations || []).map(c => c.score).filter(v => v > 0)
  return s.length ? Math.max(...s) : 0
})
</script>

<template>
  <div class="msg-ai">
    <!-- mono 标签行：状态 + 检索元数据 -->
    <div class="ai-head">
      <Scale :size="13" />
      {{ message.streaming ? '劳小司 · 思考中' : '劳小司 · 依据已核验' }}
      <span v-if="citations.length" class="ai-meta">
        <span>命中 {{ citations.length }} 条</span>
        <span v-if="topScore">相关度 {{ topScore.toFixed(2) }}</span>
      </span>
    </div>

    <!-- 真实 stage/reasoning 数据） -->
    <ThinkingPanel :message="message" />

    <!-- 流式且尚无内容：三点 thinking -->
    <div v-if="message.streaming && !message.content" class="thinking">
      <i></i><i></i><i></i>
    </div>
    <div v-else class="ai-body">
      <div class="md" v-html="html"></div>
      <span v-if="message.streaming" class="cursor"></span>
      <!-- 法条引用：藏青侧线卡（脚注式） -->
      <CitationCard v-for="(c, ci) in citations" :key="c.lawName + c.articleNo + '#' + ci" :citation="c" />
      <!-- 答复脚注（有引用时展示，lucide 线条图标前缀） -->
      <div v-if="!message.streaming && citations.length" class="a-note">
        <ShieldCheck :size="12" />
        以上答复基于知识库现行法条检索生成，引用可回溯至检索命中记录；内容仅供参考，不构成正式法律意见。
      </div>
    </div>
  </div>
</template>

<style scoped>
.msg-ai { margin-top: 26px; animation: up .5s var(--soft-ease) both; }
@keyframes up { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: none; } }

.ai-head {
  display: flex; align-items: center; gap: 9px;
  font-family: var(--mono); font-size: 10px; letter-spacing: .24em;
  color: var(--faint); text-transform: uppercase;
}
.ai-head :deep(svg) { color: var(--acc); }
.ai-meta { margin-left: auto; display: flex; gap: 6px; font-family: var(--mono); font-size: 9px; letter-spacing: .1em; }
.ai-meta span { border: 1px solid var(--line); padding: 2px 7px; color: var(--soft); }

.ai-body {
  /* 黑体正文：衬线小字号屏显发虚，阅读舒适度优先 */
  margin-top: 20px; font-size: 14.5px; line-height: 2.05; color: var(--ink);
  font-family: var(--sans); text-align: justify;
}
.md :deep(p) { margin: 0 0 10px; }
.md :deep(p:last-child) { margin-bottom: 0; }
.md :deep(h1), .md :deep(h2), .md :deep(h3) { font-family: var(--serif); font-size: 16px; font-weight: 900; margin: 16px 0 8px; }
.md :deep(ul), .md :deep(ol) { padding-left: 22px; margin: 8px 0; }
.md :deep(strong) { color: var(--acc); font-weight: 700; }
.md :deep(blockquote) { border-left: 2px solid var(--acc); padding: 2px 14px; margin: 10px 0; color: var(--soft); background: var(--acc-soft); }
.md :deep(code) { font-family: var(--mono); font-size: 12.5px; background: rgba(22, 25, 31, .05); padding: 1px 5px; }

.cursor {
  display: inline-block; width: 9px; height: 16px; background: var(--acc);
  vertical-align: -3px; margin-left: 2px; animation: blk .9s steps(1) infinite;
}
@keyframes blk { 50% { opacity: 0; } }

.thinking { display: flex; gap: 5px; padding: 8px 0; }
.thinking i { width: 6px; height: 6px; background: var(--faint); animation: bc 1.2s infinite; }
.thinking i:nth-child(2) { animation-delay: .15s; }
.thinking i:nth-child(3) { animation-delay: .3s; }
@keyframes bc { 0%, 60%, 100% { transform: none; opacity: .4; } 30% { transform: translateY(-5px); opacity: 1; } }

.a-note {
  margin-top: 20px; padding-top: 12px; border-top: 1px solid var(--line);
  font-family: var(--sans); font-size: 11.5px; color: var(--faint);
  display: flex; align-items: center; gap: 8px; line-height: 1.8;
}
.a-note :deep(svg) { color: var(--acc); flex-shrink: 0; }
</style>
