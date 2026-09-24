<script setup>
/**
 * 法条引用卡（V9 公报问答台皮肤）：藏青侧线 + "现行有效"标签 + 版本年份 + mono 出处 + 复制
 * 数据源：优先 SSE citation 事件的结构化引用；正则降级只有法名条号
 * 折叠交互：默认只显示法条名一行，点击卡片展开正文/章节（正文在数据层是整条全文，前端不再截断）
 */
import { computed, ref } from 'vue'
import { Bookmark, ChevronDown, Copy } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import { fetchArticleDetail } from '../api/library'

const props = defineProps({
  citation: { type: Object, required: true }
})

/**
 * 展开可用性：有正文，或能按 articleId 回底账取。
 * 此前只认 citation.text，而 SSE 引用载荷以"省带宽"为由不带正文——载荷一变就整卡失去展开能力；
 * 现在正文缺失时按需拉取，展开能力不再依赖载荷形态。
 */
const expandable = computed(() => Boolean(props.citation.text || props.citation.articleId))
const expanded = ref(false)
const loading = ref(false)
const fetched = ref(null)   // 按需拉取的详情（不改动 props，避免污染上游数据）

const body = computed(() => props.citation.text || (fetched.value && fetched.value.content) || '')
const chapterLine = computed(() => {
  const chapter = props.citation.chapterInfo || (fetched.value && fetched.value.chapterInfo) || ''
  const section = props.citation.sectionInfo || (fetched.value && fetched.value.sectionInfo) || ''
  if (!chapter) return ''
  return section ? `${chapter} · ${section}` : chapter
})
const copyVersion = computed(() => props.citation.versionInfo || (fetched.value && fetched.value.versionInfo) || '')

async function toggleExpand() {
  if (!expandable.value) return
  expanded.value = !expanded.value
  // 首次展开且本地无正文：回底账取一次（失败时保持可再点，不弹错打扰阅读）
  if (expanded.value && !body.value && !fetched.value && props.citation.articleId) {
    loading.value = true
    try {
      fetched.value = await fetchArticleDetail(props.citation.articleId)
    } catch { /* 静默：网络/权限异常时卡片保持折叠内容为空 */ } finally {
      loading.value = false
    }
  }
}

async function copyCite() {
  const text = `${props.citation.lawName}${props.citation.articleNo}` +
    (body.value ? `\n${body.value}` : '')
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
      @click="toggleExpand"
    >
      <Bookmark :size="13" />
      <!-- 法名数据层统一带《》（底账/兜底解析/正则降级同源），模板不再硬包 -->
      {{ citation.lawName }}{{ citation.articleNo }}
      <span class="cite-tag">现行有效</span>
      <!-- 版本年份标签（底账 version_info，法条时效可核验） -->
      <span v-if="copyVersion" class="cite-ver">{{ copyVersion }}</span>
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
    <div v-if="expanded && loading" class="cite-text cite-loading">正在取回条文原文…</div>
    <div v-else-if="expanded && body" class="cite-text">{{ body }}</div>
    <!-- 章/节属面包屑（对齐国家法律法规数据库展示体验） -->
    <div v-if="expanded && chapterLine" class="cite-path">
      {{ chapterLine }}
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
.cite-loading { font-family: var(--mono); font-size: 11px; color: var(--faint); }
.cite-path { margin-top: 6px; font-family: var(--mono); font-size: 10px; letter-spacing: .06em; color: var(--faint); animation: up .4s var(--soft-ease) both; }
</style>
