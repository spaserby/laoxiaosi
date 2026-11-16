<script setup>
/**
 * 投递口（V9 公报问答台皮肤）：白纸玻璃 dock + 藏青聚焦 + 宽幅"发起咨询"按钮
 * 交互逻辑与 idle 发送（空输入置灰）/ streaming 同位置变"停止"；Enter 发送
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useChatStore } from '../stores/chat'
import { uploadFiles, deleteAttachment, retryAttachment, fileCapabilities } from '../api/session'
import { attachmentState, attachmentsReady } from '../utils/attachments'
import { Scale, Paperclip, ArrowRight, ShieldCheck, FileText, Image as ImageIcon, X } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'

defineProps({
  /** 居中模式：新建咨询干净页内嵌（static 居中），默认 fixed 底部投递口 */
  centered: { type: Boolean, default: false }
})

const store = useChatStore()
const text = ref('')

/* 窄屏下长 placeholder 折行被单行 textarea 裁切（"输入框显示不全"观感），
   按视口换短文案；发送按钮同步收为图标（theme.css 隐藏 .send-label） */
const mqMobile = window.matchMedia('(max-width: 860px)')
const isMobile = ref(mqMobile.matches)
const updateMobile = (e) => { isMobile.value = e.matches }
mqMobile.addEventListener('change', updateMobile)
const placeholderText = computed(() =>
  isMobile.value ? '写下法律问题…' : '写下法律问题，Enter 发起咨询（仅附件也可直接发送）')

/* ---------- 会话附件（DeepSeek/Kimi 式文档上传） ---------- */
const fileInput = ref(null)
const attachments = computed(() => store.currentDraftAttachments)
const uploading = ref(false)
const removing = ref(false)
const statusUnavailable = computed(() => Boolean(store.attachmentErrors[store.currentSessionId]))
const mode = ref('qa')
const targets = ref([])
const nativeTarget = ref('')
const compatibleTargets = computed(() => targets.value.filter(target =>
  attachments.value.every(file => target.formats.includes(file.extension)) &&
  attachments.value.reduce((total, file) => total + (file.sizeBytes || 0), 0) <= target.maxBytes))
const selectedTarget = computed(() => compatibleTargets.value.find(target => target.id === nativeTarget.value))
const canSubmit = computed(() => Boolean((text.value.trim() || attachments.value.length) &&
  !store.isStreaming && !store.currentHistoryPage.loading && !uploading.value && !removing.value &&
  (attachments.value.length === 0 || (!statusUnavailable.value && attachmentsReady(attachments.value, Date.now(), mode.value === 'native') &&
    (mode.value !== 'native' || selectedTarget.value)))))
watch(compatibleTargets, options => {
  if (!options.some(target => target.id === nativeTarget.value)) {
    if (mode.value === 'native') {
      nativeTarget.value = ''
    } else nativeTarget.value = options[0]?.id || ''
  }
}, { immediate: true })
onMounted(async () => {
  try { targets.value = await fileCapabilities() }
  catch { /* 片段问答与全文审查仍然可用。 */ }
})
let uploadController = null
let uploadSession = null
let pollTimer = null
let disposed = false

async function refresh(sessionId) {
  try {
    await store.refreshAttachments(sessionId)
  } catch { /* 错误状态存入 store，禁止在缺少附件状态时提交 */ }
  finally { schedulePoll() }
}
function schedulePoll() {
  clearTimeout(pollTimer)
  if (!disposed && store.currentSessionId && (statusUnavailable.value || attachments.value.length)) {
    const sid = store.currentSessionId
    const processing = attachments.value.some(a => ['QUEUED', 'PROCESSING'].includes(a.status))
    pollTimer = setTimeout(() => refresh(sid), statusUnavailable.value ? 5000 : processing ? 2000 : 30000)
  }
}
watch(() => attachments.value.map(a => `${a.fileId}:${a.status}`).join(','), schedulePoll)
watch(statusUnavailable, schedulePoll)
watch(() => store.currentSessionId, sid => {
  mode.value = 'qa'
  clearTimeout(pollTimer)
  if (uploadSession && sid !== uploadSession) uploadController?.abort()
  schedulePoll()
})
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(pollTimer)
  uploadController?.abort()
  mqMobile.removeEventListener('change', updateMobile)
})

function pickFiles() {
  if (store.isStreaming || uploading.value || removing.value) return
  fileInput.value?.click()
}

async function onFilesPicked(e) {
  const picked = Array.from(e.target.files || [])
  e.target.value = ''   // 同文件可重选
  if (!picked.length) return
  if (attachments.value.length + picked.length > 3) {
    ElMessage.warning('单次咨询最多 3 个附件')
    return
  }
  uploading.value = true
  const sid = store.ensureSession()
  const epoch = store.identityEpoch
  uploadSession = sid
  store.uploadingAttachmentsFor = sid
  const controller = new AbortController()
  uploadController = controller
  try {
    const fd = new FormData()
    picked.forEach(f => fd.append('files', f))
    const ups = await uploadFiles(fd, sid, controller.signal)
    if (epoch !== store.identityEpoch || sid !== store.currentSessionId || disposed) return
    // 本地缩略图预览（objectURL 仅当前页面会话有效，不进后端）
    ups.forEach((u, i) => {
      const src = picked[i]
      if (src && u.kind === 'image') u.url = URL.createObjectURL(src)
    })
    store.attachmentsBySession[sid] = [...(store.attachmentsBySession[sid] || []), ...ups]
    store.draftAttachments[sid] = [...(store.draftAttachments[sid] || []), ...ups]
    store.attachmentErrors[sid] = false
    store.loadSessions()
  } catch (err) {
    if (err.code !== 'ERR_CANCELED') ElMessage.error(err.response?.data?.message || err.message || '上传失败')
  } finally {
    if (epoch === store.identityEpoch && store.uploadingAttachmentsFor === sid) store.uploadingAttachmentsFor = null
    uploading.value = false
    uploadController = null
    uploadSession = null
  }
}

async function removeAttachment(file) {
  const sid = store.currentSessionId
  if (removing.value) return
  removing.value = true
  try {
    const epoch = store.identityEpoch
    if (!file.reused && file.expiresAt > Date.now()) await deleteAttachment(sid, file.fileId)
    if (store.identityEpoch !== epoch) return
    if (!file.reused && file.url) URL.revokeObjectURL(file.url)
    store.draftAttachments[sid] = (store.draftAttachments[sid] || []).filter(a => a.fileId !== file.fileId)
    if (!file.reused) store.attachmentsBySession[sid] = (store.attachmentsBySession[sid] || []).filter(a => a.fileId !== file.fileId)
    if (sid === store.currentSessionId && !store.draftAttachments[sid].length) mode.value = 'qa'
  } catch (err) { ElMessage.error(err.response?.data?.message || '移除失败，请重试') }
  finally { removing.value = false }
}
async function retry(file) {
  const sid = store.currentSessionId
  try { await retryAttachment(sid, file.fileId); await refresh(sid) }
  catch (err) { ElMessage.error(err.response?.data?.message || '重试失败') }
}

function submit() {
  const t = text.value.trim()
  if (!canSubmit.value) return
  if (attachments.value.some(file => file.expiresAt <= Date.now())) {
    ElMessage.warning('附件已过期，请移除或重新上传')
    return
  }
  const turnMode = attachments.value.length ? mode.value : 'qa'
  const question = t || (turnMode === 'review' ? '请逐章审查文档中的风险、限制条件和例外，给出原文依据与修改建议。' : '请结合我上传的附件内容进行解答。')
  if (store.send(question, attachments.value, turnMode, turnMode === 'native' ? nativeTarget.value : undefined)) {
    text.value = ''
    mode.value = 'qa'
  }
}

function onKey(e) {
  if (e.isComposing || e.keyCode === 229) return
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}
</script>

<template>
  <div class="dockwrap" :class="{ centered }">
    <form class="dock" @submit.prevent="submit">
      <!-- 附件 chips 行 -->
      <div v-if="attachments.length || uploading" class="attach-row">
        <span v-for="a in attachments" :key="a.fileId" class="attach-chip" :title="[a.error, ...(a.warnings || [])].filter(Boolean).join('；')">
          <ImageIcon v-if="a.kind === 'image' || a.kind === 'scanned'" :size="12" />
          <FileText v-else :size="12" />
          {{ a.name }}
          <em>{{ attachmentState(a) }}</em>
          <button v-if="mode !== 'native' && (a.status === 'FAILED' || a.failedPages?.length || a.warnings?.some(w => w.includes('向量化')))" type="button" :disabled="store.isStreaming" @click="retry(a)">重试解析</button>
          <button type="button" :disabled="store.isStreaming || removing" aria-label="移除附件" @click="removeAttachment(a)"><X :size="11" /></button>
        </span>
        <span v-if="uploading" class="attach-chip up">上传中…</span>
        <span v-if="statusUnavailable" class="attach-chip up">附件状态暂不可用，正在重试…</span>
      </div>
      <div v-if="attachments.length" class="attach-mode">
        <label><input v-model="mode" type="radio" value="qa" :disabled="store.isStreaming" /> 相关片段问答</label>
        <label><input v-model="mode" type="radio" value="review" :disabled="store.isStreaming" /> 逐章全文审查</label>
        <label v-if="compatibleTargets.length || mode === 'native'"><input v-model="mode" type="radio" value="native" :disabled="store.isStreaming" /> 原件直传</label>
        <template v-if="mode === 'native'">
          <label v-if="compatibleTargets.length">接收方
            <select v-model="nativeTarget" aria-label="原件接收方">
              <option disabled value="">请选择</option>
              <option v-for="target in compatibleTargets" :key="target.id" :value="target.id">{{ target.label }}</option>
            </select>
          </label>
          <small v-if="selectedTarget">发送时会将本轮原件交给 {{ selectedTarget.label }} 处理，PDF 合计最多 {{ selectedTarget.maxPdfPages }} 页。</small>
          <small v-else>请选择支持本轮文件格式和大小的接收方。</small>
        </template>
        <small v-else-if="targets.length && !compatibleTargets.length">当前附件超出已配置直传模型的格式或大小限制。</small>
      </div>
      <div v-for="a in attachments.filter(a => a.error || a.warnings?.length)" :key="a.fileId + '-notice'" class="attach-notice">
        {{ a.name }}：{{ a.error || a.warnings.join('；') }}<template v-if="a.failedPages?.length">（失败页：{{ a.failedPages.join('、') }}）</template>
      </div>
      <div class="dock-row">
        <span class="dock-icon"><Scale :size="17" /></span>
        <textarea id="input" v-model="text" rows="1"
                  :placeholder="placeholderText"
                  aria-label="法律问题输入框"
                  @keydown="onKey" />
        <!-- 附件由后端限制大小并排队解析；超限明确报错。 -->
        <input ref="fileInput" type="file" hidden multiple
               accept=".txt,.md,.pdf,.doc,.docx,.png,.jpg,.jpeg,.webp" @change="onFilesPicked" />
        <button type="button" class="dock-btn" :class="{ busy: uploading }"
                :disabled="uploading || removing || store.isStreaming" aria-label="上传文档或图片" title="支持 PDF、DOC、DOCX 和图片，单次最多 3 个"
                @click="pickFiles">
          <Paperclip :size="16" />
        </button>
        <!-- 发送/停止一体化：streaming 时同位置变停止；有附件无文字也可发 -->
        <button v-if="!store.isStreaming" type="submit" class="send-btn" :disabled="!canSubmit">
          <span class="send-label">发起咨询</span><ArrowRight :size="14" />
        </button>
        <button v-else type="button" class="send-btn stop" title="停止生成" @click="store.stop()">
          停止<span class="stop-square"></span>
        </button>
      </div>
    </form>
    <div class="disclaimer">
      <ShieldCheck :size="12" />
      AI 生成内容仅供参考，不构成正式法律意见 · 重大事项请咨询执业律师
    </div>
  </div>
</template>

<style scoped>
.dockwrap {
  position: fixed; left: var(--sw); right: 0; bottom: 0; z-index: 35;
  padding: 0 32px 18px; pointer-events: none;
  transition: left .5s var(--spring);
}
body.rail .dockwrap { left: var(--sw-rail); }
/* 居中模式：干净页内嵌，不 fixed */
.dockwrap.centered {
  position: static; left: auto; right: auto; padding: 0;
  pointer-events: auto; max-width: 680px; margin: 0 auto;
}

.dock {
  max-width: var(--sheet); margin: 0 auto; pointer-events: auto;
  background: rgba(255, 255, 255, .95);
  backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--line-ink); padding: 10px 9px 9px 14px;
  box-shadow: 0 16px 44px rgba(22, 25, 31, .13);
  transition: border-color .35s, box-shadow .4s;
}
/* dock 改纵向容器（chips 行 + 输入行），chips 收进框内 */
.dock-row { display: flex; align-items: center; gap: 10px; }
.attach-row {
  display: flex; flex-wrap: wrap; gap: 6px; margin: 0 2px 8px 6px;
}
.attach-chip {
  display: inline-flex; align-items: center; gap: 5px;
  background: rgba(255,255,255,.95); border: 1px solid var(--line);
  padding: 4px 8px; font-family: var(--sans); font-size: 11px; color: var(--ink);
}
.attach-chip em { font-style: normal; font-family: var(--mono); font-size: 9.5px; color: var(--faint); }
.attach-chip button { display: grid; place-items: center; color: var(--faint); padding: 2px; }
.attach-chip button:hover { color: var(--acc); }
.attach-chip.up { color: var(--faint); }
.attach-mode { display: flex; flex-wrap: wrap; gap: 12px; margin: 5px 6px 10px; font-size: 12px; color: var(--soft); }
.attach-mode label { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }
.attach-mode small { color: var(--faint); }
.attach-notice { margin: 4px 6px 8px; font-size: 11px; color: var(--soft); line-height: 1.6; }
.dock-btn.busy { opacity: .5; pointer-events: none; }
.dock:focus-within { border-color: var(--acc); box-shadow: 0 16px 44px rgba(30, 58, 138, .18); }

.dock-icon { color: var(--faint); display: grid; place-items: center; flex-shrink: 0; }
#input {
  flex: 1; border: none; background: none; outline: none; resize: none;
  font-size: 14.5px; font-family: var(--sans); color: var(--ink);
  padding: 10px 0; min-width: 0; line-height: 1.5; max-height: 120px;
}
#input::placeholder { color: var(--faint); }

.dock-btn {
  width: 34px; height: 34px; color: var(--faint); display: grid; place-items: center;
  flex-shrink: 0; transition: all .3s var(--spring);
}
.dock-btn:hover { color: var(--acc); transform: translateY(-2px); }

.send-btn {
  height: 40px; padding: 0 20px; background: var(--ink); color: #fff;
  font-family: var(--sans); font-size: 13px; font-weight: 700;
  display: flex; align-items: center; gap: 8px; flex-shrink: 0;
  transition: all .3s var(--spring);
}
.send-btn:hover:not(:disabled) { background: var(--acc); }
.send-btn:active:not(:disabled) { transform: scale(.96); }
.send-btn:disabled { opacity: .4; cursor: not-allowed; }
.send-btn.stop { background: var(--ink); }
.stop-square { width: 11px; height: 11px; background: #fff; }

.disclaimer {
  max-width: var(--sheet); margin: 9px auto 0; text-align: center;
  font-family: var(--sans); font-size: 11px; color: var(--faint);
  display: flex; align-items: center; justify-content: center; gap: 6px;
  pointer-events: auto;
}
.disclaimer :deep(svg) { color: var(--acc); }
</style>
