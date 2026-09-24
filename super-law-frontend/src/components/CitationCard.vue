<script setup>
/**
 * 法条引用卡（V9 公报问答台皮肤）：藏青侧线 + "现行有效"标签 + 版本年份 + mono 出处 + 复制
 * 数据源：优先 SSE citation 事件的结构化引用；正则降级只有法名条号
 * 折叠交互：默认只显示法条名一行，点击卡片展开正文/章节（正文在数据层是整条全文，前端不再截断）
 */
import { ref } from 'vue'
import { Bookmark, ChevronDown, Copy } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'

const props = defineProps({
  citation: { type: Object, required: true }
})

// 无正文可看时（正则降级引用）不提供展开
const expandable = Boolean(props.citation.text)
const expanded = ref(false)

async function copyCite() {
  const text = `${props.citation.lawName}${props.citation.articleNo}` +
    (props.citation.text ? `\n${props.citation.text}` : '')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('法条引用已复制')
  } catch { /* 剪贴板不可用时静默 */ }
}
</script>

<template>
  <div class="cite-card" :class="{ 'is-collapsed': !expanded }">
    <div
      class="cite-top"
      :role="expandable ? 'button' : undefined"
      :aria-expanded="expandable ? expanded : undefined"
      @click="expandable && (expanded = !expanded)"
    >
      <Bookmark :size="13" />
      <!-- 法名数据层统一带《》（底账/兜底解析/正则降级同源），模板不再硬包 -->
      {{ citation.lawName }}{{ citation.articleNo }}
      <span class="cite-tag">现行有效</span>
      <!-- 版本年份标签（底账 version_info，法条时效可核验） -->
      <span v-if="citation.versionInfo" class="cite-ver">{{ citation.versionInfo }}</span>
      <span class="cite-source">
        知识库检索命中<template v-if="citation.score"> · TOP {{ citation.score.toFixed(2) }}</template>
      </span>
      <!-- 复制/展开与 meta 文本同色同大小（10px mono faint 400），展开与箭头恒并排 -->
      <span class="cite-actions">
        <button class="cite-btn" aria-label="复制引用" @click.stop="copyCite"><Copy :size="12" /></button>
        <span v-if="expandable" class="cite-fold" :class="{ open: expanded }" aria-hidden="true">
          <span class="fold-label">{{ expanded ? '收起' : '展开' }}</span><ChevronDown :size="12" />
        </span>
      </span>
    </div>
    <div v-if="expanded && citation.text" class="cite-text">{{ citation.text }}</div>
    <!-- 章/节属面包屑（对齐国家法律法规数据库展示体验） -->
    <div v-if="expanded && citation.chapterInfo" class="cite-path">
      {{ citation.chapterInfo }}<template v-if="citation.sectionInfo"> · {{ citation.sectionInfo }}</template>
    </div>
  </div>
</template>

<style scoped>
.cite-card {
  margin: 20px 0 0; padding: 15px 18px;
  border: 1px solid var(--line); border-left: 3px solid var(--acc);
  background: var(--acc-soft);
  animation: up .5s var(--soft-ease) both;
}
.cite-card.is-collapsed { padding: 10px 18px; }
@keyframes up { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: none; } }
.cite-top {
  display: flex; align-items: center; gap: 8px;
  font-family: var(--sans); font-size: 12px; font-weight: 700; color: var(--acc); flex-wrap: wrap;
}
.cite-top[role='button'] { cursor: pointer; }
.cite-top[role='button']:hover { color: #1d4ed8; }
.cite-top :deep(svg) { flex-shrink: 0; }
.cite-tag {
  font-family: var(--mono); font-size: 9px; font-weight: 400; letter-spacing: .1em;
  border: 1px solid rgba(30, 58, 138, .32); padding: 1px 6px; color: var(--acc);
}
.cite-ver {
  font-family: var(--mono); font-size: 9px; letter-spacing: .06em;
  color: var(--soft); border: 1px solid var(--line); padding: 1px 6px;
}
.cite-source {
  margin-left: auto; display: flex; align-items: center; gap: 3px;
  font-weight: 400; color: var(--faint); font-size: 10px; font-family: var(--mono);
}
/* 右簇间隔重排——复制/展开与 meta 文本视觉同质，段间 6px 呼吸 */
.cite-actions { display: inline-flex; align-items: center; gap: 6px; margin-left: 4px; }
.cite-btn { color: var(--faint); padding: 2px; display: grid; place-items: center; transition: all .3s var(--spring); }
.cite-btn:hover { color: var(--acc); background: rgba(30, 58, 138, .12); transform: scale(1.15); }
.cite-fold {
  display: inline-flex; flex-direction: row; align-items: center; gap: 3px;
  flex-wrap: nowrap; white-space: nowrap;
  font-family: var(--mono); font-size: 10px; font-weight: 400; color: var(--faint); padding: 2px 0;
  transition: color .3s var(--spring); user-select: none;
}
.cite-fold .fold-label { line-height: 1; }
.cite-fold :deep(svg) { flex-shrink: 0; }
.cite-top:hover .cite-fold { color: var(--acc); }
.cite-fold :deep(svg) { transition: transform .3s var(--spring); }
.cite-fold.open :deep(svg) { transform: rotate(180deg); }
.cite-text { font-family: var(--sans); font-size: 12.5px; color: var(--soft); line-height: 1.85; margin-top: 9px; text-align: justify; animation: up .4s var(--soft-ease) both; }
.cite-path { margin-top: 6px; font-family: var(--mono); font-size: 10px; letter-spacing: .06em; color: var(--faint); animation: up .4s var(--soft-ease) both; }
</style>
