<script setup>
/**
 * 咨询记录（V9 公报问答台皮肤）：咨询请求（墨块右对齐）/ AI 答复（公报体平铺）
 * 滚动策略（对齐 DeepSeek/豆包）：流式生成期间不跟随滚动——回答在下方持续生成，
 * 用户自行下滑阅读；仅在发送新提问时把新问题定位到视口顶部（首屏即见回答开头），
 * 打开/切换会话时滚到最新内容。
 */
import { watch, nextTick, onMounted } from 'vue'
import { FileText } from 'lucide-vue-next'
import { useChatStore } from '../stores/chat'
import AssistantBubble from './AssistantBubble.vue'

const store = useChatStore()
let scroller = null
let loadingOlder = false

onMounted(() => {
  scroller = document.querySelector('.page-scroll')
  scrollBottom()
})

// 只在"会话/消息条数"变化时定位（新提问置顶、切会话滚底）；
// token 流的持续更新不触发滚动——生成期间页面保持静止，由用户手动下滑
watch(
  () => [store.currentSessionId, store.currentMessages.length],
  async () => {
    await nextTick()
    if (loadingOlder) return
    if (store.isStreaming) scrollToLastQuestion()
    else scrollBottom()
  }
)

async function loadOlder() {
  const sid = store.currentSessionId
  const desktop = scroller && scroller.scrollHeight > scroller.clientHeight + 4
  const element = desktop ? scroller : document.documentElement
  const height = element.scrollHeight
  const top = desktop ? element.scrollTop : window.scrollY
  loadingOlder = true
  try {
    await store.loadOlderMessages()
    await nextTick()
    if (store.currentSessionId === sid) {
      const next = top + element.scrollHeight - height
      if (desktop) element.scrollTop = next
      else window.scrollTo({ top: next })
    }
  } finally { loadingOlder = false }
}

function scrollToLastQuestion() {
  const list = scroller ? scroller.querySelectorAll('.msg-user') : []
  const el = list[list.length - 1]
  if (!el) return scrollBottom()
  // 桌面（.page-scroll 内层滚动）与移动端（document 滚动）通用
  el.scrollIntoView({ block: 'start' })
}

function scrollBottom() {
  // 桌面：.page-scroll 是滚动容器；移动端（≤860px）它被内容撑开、实际滚动发生在 document——
  // 不区分则移动端定位不生效
  if (scroller && scroller.scrollHeight > scroller.clientHeight + 4) {
    scroller.scrollTop = scroller.scrollHeight
  } else {
    window.scrollTo({ top: document.documentElement.scrollHeight })
  }
}
</script>

<template>
  <section class="chat visible" aria-live="polite">
    <div class="chat-label">— 法律咨询 —</div>
    <p v-if="store.currentHistoryPage.loading && !store.currentMessages.length" class="history-controls">正在加载历史记录…</p>
    <div class="history-controls">
      <button v-if="store.currentHistoryPage.hasMore" :disabled="store.currentHistoryPage.loading || store.isStreaming" @click="loadOlder">
        {{ store.currentHistoryPage.loading ? '加载中…' : '加载更早记录' }}
      </button>
      <button v-if="store.currentHistoryPage.error" @click="store.currentHistoryPage.loaded ? loadOlder() : store.loadHistory(store.currentSessionId)">{{ store.currentHistoryPage.error }}</button>
    </div>
    <template v-for="(m, i) in store.currentMessages" :key="m.id || m.localId || `local-${i}`">
      <AssistantBubble v-if="m.role === 'assistant'" :message="m" />
      <div v-else-if="m.role === 'user'" class="msg-user">
        <span class="mu-l">咨询请求</span>
        <!-- 附件卡行（文件名+字数，与输入侧 chips 同源） -->
        <div v-if="m.attachments && m.attachments.length" class="mu-files">
          <span v-for="a in m.attachments" :key="a.fileId" class="mu-file">
            <img v-if="a.url" :src="a.url" class="mu-thumb" alt="附件缩略图" />
            <FileText v-else :size="11" />
            {{ a.name }}<template v-if="a.imageCount"> · {{ a.imageCount }} 图</template><template v-else-if="a.chars"> · {{ a.chars }} 字</template>{{ a.truncated ? '（已截取）' : '' }}
          </span>
        </div>
        {{ m.content }}
        <span v-if="m.status && m.status !== 'COMPLETE'" class="mu-l">{{ m.status === 'RUNNING' ? '生成中' : '本轮未完成' }}</span>
      </div>
    </template>
  </section>
</template>

<style scoped>
.chat { padding: 34px 0 0; border-top: 3px double var(--line-ink); margin-top: 36px; }
.chat-label {
  font-family: var(--mono); font-size: 10px; letter-spacing: .32em;
  color: var(--faint); text-transform: uppercase; text-align: center;
}
.history-controls { text-align: center; margin-top: 14px; }
.history-controls button { background: transparent; border: 1px solid var(--line-ink); padding: 5px 12px; color: var(--faint); cursor: pointer; }
.msg-user {
  margin: 24px 0 0 auto; max-width: 74%; width: fit-content;
  background: var(--ink); color: #F2F3F5; padding: 14px 20px;
  font-family: var(--sans); font-size: 13.5px; line-height: 1.8;
  white-space: pre-wrap; animation: up .5s var(--soft-ease) both;
  /* 新提问定位到视口顶部时留一点呼吸空间（见 scrollToLastQuestion） */
  scroll-margin-top: 26px;
}
.mu-l {
  display: block; font-family: var(--mono); font-size: 9.5px; letter-spacing: .24em;
  color: rgba(242, 243, 245, .55); text-transform: uppercase; margin-bottom: 7px;
}
.mu-files { display: flex; flex-direction: column; gap: 4px; margin-bottom: 8px; }
.mu-file {
  display: inline-flex; align-items: center; gap: 5px;
  font-family: var(--mono); font-size: 10.5px;
  border: 1px solid rgba(242, 243, 245, .35); padding: 3px 8px; color: rgba(242, 243, 245, .85);
}
.mu-thumb { width: 22px; height: 22px; object-fit: cover; border: 1px solid rgba(242,243,245,.4); }
@keyframes up { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: none; } }
</style>
