<script setup>
/**
 * 咨询记录（V9 公报问答台皮肤）：咨询请求（墨块右对齐）/ AI 答复（公报体平铺）
 * 滚动策略（对齐 DeepSeek/豆包）：流式生成期间不跟随滚动——回答在下方持续生成，
 * 用户自行下滑阅读；仅在发送新提问时把新问题定位到视口顶部（首屏即见回答开头），
 * 打开/切换会话时滚到最新内容。
 */
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { Download, FileText, Trash2 } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useChatStore } from '../stores/chat'
import { deleteAttachment, downloadOriginal } from '../api/session'
import AssistantBubble from './AssistantBubble.vue'

const store = useChatStore()
let scroller = null
let loadingOlder = false
const now = ref(Date.now())
let clock

onMounted(() => {
  scroller = document.querySelector('.page-scroll')
  clock = setInterval(() => { now.value = Date.now() }, 15000)
  scrollBottom()
})
onBeforeUnmount(() => clearInterval(clock))

function originalAvailable(file) {
  const saved = store.attachmentsBySession[store.currentSessionId]
  return file.expiresAt > now.value && (!saved || saved.some(item => item.fileId === file.fileId))
}

async function saveOriginal(file) {
  const sid = store.currentSessionId
  const epoch = store.identityEpoch
  try {
    const blob = await downloadOriginal(sid, file.fileId)
    if (epoch !== store.identityEpoch || sid !== store.currentSessionId) return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    if (epoch !== store.identityEpoch || sid !== store.currentSessionId) return
    ElMessage.error(error.response?.status === 403 ? '原件已过期、被移除或当前身份无下载权限' : '下载失败，请稍后重试')
  }
}

async function reuse(file) {
  const sid = store.currentSessionId
  const epoch = store.identityEpoch
  try {
    await store.refreshAttachments(sid)
    if (sid !== store.currentSessionId || epoch !== store.identityEpoch || store.isStreaming || store.uploadingAttachmentsFor === sid) return
    const original = store.currentAttachments.find(item => item.fileId === file.fileId && item.expiresAt > Date.now())
    if (!original) { ElMessage.warning('原件已过期或被移除，请重新上传'); return }
    const draft = store.draftAttachments[sid] || []
    if (draft.some(item => item.fileId === file.fileId)) return
    if (draft.length >= 3) { ElMessage.warning('单次咨询最多 3 个附件'); return }
    store.draftAttachments[sid] = [...draft, { ...original, reused: true }]
    document.getElementById('input')?.focus()
  } catch {
    if (epoch === store.identityEpoch && sid === store.currentSessionId) ElMessage.error('读取附件状态失败，请稍后重试')
  }
}

async function removeOriginal(file) {
  const sid = store.currentSessionId
  const epoch = store.identityEpoch
  try {
    await ElMessageBox.confirm(`删除「${file.name}」及其解析内容？已有问答会保留，之后无法下载或再次使用原件。`, '删除原件', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }
  if (sid !== store.currentSessionId || epoch !== store.identityEpoch || store.isStreaming) return
  try {
    await deleteAttachment(sid, file.fileId)
    if (epoch !== store.identityEpoch) return
    const saved = store.attachmentsBySession[sid]
    const url = saved?.find(item => item.fileId === file.fileId)?.url
    if (url) URL.revokeObjectURL(url)
    if (saved) store.attachmentsBySession[sid] = saved.filter(item => item.fileId !== file.fileId)
    else await store.refreshAttachments(sid)
    if (epoch !== store.identityEpoch) return
    store.draftAttachments[sid] = (store.draftAttachments[sid] || []).filter(item => item.fileId !== file.fileId)
  } catch {
    if (epoch === store.identityEpoch && sid === store.currentSessionId) ElMessage.error('原件删除失败，请刷新后重试')
  }
}

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
        <div v-if="m.attachments && m.attachments.length" class="mu-files">
          <span v-for="a in m.attachments" :key="a.fileId" class="mu-file">
            <img v-if="a.url && originalAvailable(a)" :src="a.url" class="mu-thumb" alt="附件缩略图" />
            <FileText v-else :size="11" />
            <span class="mu-filename">{{ a.name }}</span>
            <template v-if="originalAvailable(a)">
              <button type="button" title="下载原件" :aria-label="`下载原件 ${a.name}`" @click="saveOriginal(a)"><Download :size="13" /></button>
              <button type="button" :disabled="store.isStreaming || store.uploadingAttachmentsFor === store.currentSessionId" @click="reuse(a)">用于下一条</button>
              <button type="button" :disabled="store.isStreaming" title="删除原件" :aria-label="`删除原件 ${a.name}`" @click="removeOriginal(a)"><Trash2 :size="12" /></button>
            </template>
            <span v-else-if="a.expiresAt">{{ a.expiresAt <= now ? '原件已过期' : '原件不可用' }}</span>
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
.mu-file button { color: inherit; padding: 2px 4px; flex-shrink: 0; }
.mu-file button:hover:not(:disabled) { color: white; text-decoration: underline; }
.mu-file button:disabled { opacity: .5; }
.mu-filename { overflow-wrap: anywhere; min-width: 0; }
@keyframes up { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: none; } }
</style>
