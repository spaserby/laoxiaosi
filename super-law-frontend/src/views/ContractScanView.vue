<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight, FileText, ScanLine, Upload } from 'lucide-vue-next'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '../stores/user'
import * as api from '../api/contract-scan'
import { PHOTO_ACCEPT, SCAN_ACCEPT, prepareScanFile, scanFileSpec, scanSelectionIssue, uploadScanFile } from '../utils/contract-scan'

const user = useUserStore()
const policy = ref(null)
const jobs = ref([])
const job = ref(null)
const selected = ref([])
const stance = ref('中立')
const busy = ref('')
const issue = ref('')
const preview = ref('')
const previewIssue = ref('')
const pageNumber = ref(1)
const repairPage = ref(1)
const filter = ref('全部')
const uploadProgress = ref('')
const filePicker = ref(null)
const replacementPicker = ref(null)
let scope, pollTimer, previewRequest

const statuses = { DRAFT: '待上传', QUEUED: '排队中', SCANNING: '识别中', REVIEWING: '审查中', PAUSED: '待确认', READY: '已完成', PARTIAL: '待核对', FAILED: '待重试' }
const processing = state => ['QUEUED', 'SCANNING', 'REVIEWING'].includes(state)
const status = computed(() => job.value?.summary.status)
const totalPages = computed(() => job.value?.summary.totalPages || 0)
const isPdf = computed(() => job.value?.files[0]?.format === 'pdf')
const ready = computed(() => ['READY', 'PARTIAL', 'FAILED'].includes(status.value))
const consent = computed(() => policy.value && `扫描页图像由 ${policy.value.ocrRecipient} 识别，提取出的文字由 ${policy.value.reviewRecipient} 审查。`)
const selectedBytes = computed(() => selected.value.reduce((bytes, file) => bytes + file.size, 0))
const scanCount = computed(() => job.value?.pages.filter(item => item.scanned).length || 0)
const reviewCount = computed(() => job.value?.pages.filter(item => item.reviewed).length || 0)
const risks = computed(() => (job.value?.risks || []).filter(item => filter.value === '全部' || item.severity === filter.value)
  .sort((a, b) => ['高', '中', '低'].indexOf(a.severity) - ['高', '中', '低'].indexOf(b.severity)))
const currentPage = computed(() => job.value?.pages[pageNumber.value - 1])
const formatSize = bytes => bytes >= 1024 ** 2 ? `${(bytes / 1024 ** 2).toFixed(1)} MiB` : `${Math.ceil(bytes / 1024)} KiB`
const formatTime = value => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })

function clearSelection() {
  selected.value.forEach(file => file.thumb && URL.revokeObjectURL(file.thumb))
  selected.value = []
}

function clearPreview() {
  previewRequest?.abort()
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  previewIssue.value = ''
}

function releaseSession() {
  scope?.abort()
  clearTimeout(pollTimer)
  clearPreview()
  clearSelection()
  jobs.value = []
  job.value = null
  issue.value = ''
  busy.value = ''
  uploadProgress.value = ''
}

async function openWorkspace(token) {
  releaseSession()
  const context = new AbortController()
  scope = context
  policy.value = null
  busy.value = '正在打开合同扫描…'
  try {
    const configured = await api.scanPolicy(context.signal)
    context.signal.throwIfAborted()
    policy.value = configured
    if (token && !context.signal.aborted) {
      await refreshList(context)
      const active = jobs.value.find(item => processing(item.status) || ['DRAFT', 'PAUSED'].includes(item.status))
      if (active && !context.signal.aborted) await loadJob(active.id, context, true)
    }
  } catch (error) {
    if (!context.signal.aborted) issue.value = error.message
  } finally {
    if (!context.signal.aborted) busy.value = ''
  }
}

watch(() => user.token, openWorkspace, { immediate: true })
onBeforeUnmount(releaseSession)

async function refreshList(context) {
  const items = await api.scanJobs(context.signal)
  if (!context.signal.aborted) jobs.value = items
}

function displayJob(value, resetPage = false) {
  if (resetPage || job.value?.summary.id !== value.summary.id) {
    clearPreview()
    pageNumber.value = 1
    filter.value = '全部'
  }
  job.value = value
  const index = jobs.value.findIndex(item => item.id === value.summary.id)
  if (index === -1) jobs.value.unshift(value.summary)
  else jobs.value.splice(index, 1, value.summary)
  schedulePoll()
}

async function loadJob(id, context, resetPage = false) {
  const value = await api.scanJob(id, context.signal)
  if (!context.signal.aborted) displayJob(value, resetPage)
}

function schedulePoll() {
  clearTimeout(pollTimer)
  if (!processing(status.value)) return
  const context = scope
  const id = job.value.summary.id
  pollTimer = setTimeout(async () => {
    if (context.signal.aborted || job.value?.summary.id !== id) return
    try {
      const value = await api.scanJob(id, context.signal)
      if (!context.signal.aborted && job.value?.summary.id === id) {
        displayJob(value)
        issue.value = ''
      }
    } catch (error) {
      if (!context.signal.aborted && job.value?.summary.id === id) {
        issue.value = '暂时无法刷新进度。'
        if (error.status !== 404 && error.status !== 401 && error.status !== 403) schedulePoll()
      }
    }
  }, document.hidden ? 6500 : 2200)
}

async function action(label, handler) {
  if (busy.value || !scope || scope.signal.aborted) return
  const context = scope
  issue.value = ''
  busy.value = label
  try { await handler(context) }
  catch (error) {
    if (!context.signal.aborted) {
      issue.value = error.message || '操作失败，请稍后重试。'
      if (error.status === 409) {
        try {
          const value = await api.scanPolicy(context.signal)
          if (!context.signal.aborted) policy.value = value
          await refreshList(context)
        } catch { /* Keep the original explanation. */ }
      }
    }
  } finally {
    if (!context.signal.aborted) {
      busy.value = ''
      uploadProgress.value = ''
    }
  }
}

function addFiles(input) {
  if (!input?.length || !policy.value?.enabled) return
  action('正在逐张优化照片…', async context => {
    const picked = Array.from(input)
    const invalid = scanSelectionIssue([...selected.value, ...picked], policy.value)
    if (invalid) throw new Error(invalid)
    const prepared = []
    const failed = []
    for (const file of picked) {
      try {
        const result = await prepareScanFile(file, policy.value)
        prepared.push({ ...result, source: file, rotation: 0, thumb: null })
      } catch (error) {
        failed.push(`${file.name} · ${error.message}`)
      }
      context.signal.throwIfAborted()
    }
    const all = [...selected.value, ...prepared]
    const tooLarge = scanSelectionIssue(all, policy.value)
    if (tooLarge) throw new Error(tooLarge)
    prepared.forEach(file => { if (file.optimized) file.thumb = URL.createObjectURL(file.blob) })
    selected.value = all
    if (failed.length) issue.value = `${failed.slice(0, 2).join('。')}${failed.length > 2 ? `（共 ${failed.length} 个未能添加）` : ''}`
  })
}

function chooseFiles(event) {
  addFiles(event.target.files)
  event.target.value = ''
}

function move(index, direction) {
  const target = index + direction
  if (target < 0 || target >= selected.value.length) return
  const [file] = selected.value.splice(index, 1)
  selected.value.splice(target, 0, file)
}

function rotate(index) {
  action('正在旋转照片…', async context => {
    const file = selected.value[index]
    const rotation = (file.rotation + 90) % 360
    const result = await prepareScanFile(file.source, policy.value, { rotation })
    context.signal.throwIfAborted()
    const invalid = scanSelectionIssue(selected.value.map((value, idx) => idx === index ? result : value), policy.value)
    if (invalid) throw new Error(invalid)
    URL.revokeObjectURL(file.thumb)
    selected.value[index] = { ...result, source: file.source, thumb: URL.createObjectURL(result.blob), rotation }
  })
}

function removeFile(index) {
  const [file] = selected.value.splice(index, 1)
  if (file.thumb) URL.revokeObjectURL(file.thumb)
  issue.value = ''
}

async function uploadFile(value, target, file, context, label) {
  await uploadScanFile(file.blob, policy.value.chunkBytes, target.received,
    (offset, chunk) => api.uploadScanChunk(value.summary.id, target.id, offset, chunk, context.signal),
    offset => { uploadProgress.value = `${label} ${Math.floor(offset * 100 / target.size)}%` }, context.signal)
}

async function resume(value, context) {
  for (const [index, target] of value.files.entries()) {
    if (target.received === target.size) continue
    const source = selected.value[index]
    if (!source || source.name !== target.name || source.size !== target.size) {
      throw new Error(`请重新选择「${target.name}」继续上传。`)
    }
    await uploadFile(value, target, source, context, `正在上传 ${index + 1}/${value.files.length}`)
  }
  const queued = await api.startScan(value.summary.id, policy.value.revision, context.signal)
  context.signal.throwIfAborted()
  displayJob(queued)
  clearSelection()
}

function startNew() {
  action('正在提交合同…', async context => {
    const files = []
    for (const file of selected.value) files.push(await scanFileSpec(file, policy.value.chunkBytes, context.signal))
    const created = await api.createScan({ files, stance: stance.value, revision: policy.value.revision }, context.signal)
    context.signal.throwIfAborted()
    displayJob(created, true)
    try { await resume(created, context) }
    catch (error) { if (!context.signal.aborted) await loadJob(created.summary.id, context); throw error }
  })
}

function continueDraft() {
  action('正在继续合同任务…', async context => {
    const value = job.value
    try { await resume(value, context) }
    catch (error) { if (!context.signal.aborted) await loadJob(value.summary.id, context); throw error }
  })
}

function chooseResume(event, index) {
  const raw = event.target.files?.[0]
  event.target.value = ''
  if (!raw) return
  action('正在校验分片…', async context => {
    const value = job.value
    const target = value.files[index]
    const file = selected.value[index]?.source === raw ? selected.value[index] : await prepareScanFile(raw, policy.value)
    if (file.name !== target.name || file.size !== target.size || file.format !== target.format) {
      throw new Error('请选择上传时的同一个文件。调整过方向的照片可以重新创建任务。')
    }
    await uploadFile(value, target, file, context, `正在续传 ${index + 1}/${value.files.length}`)
    await loadJob(value.summary.id, context)
    if (job.value.files.every(item => item.received === item.size)) await resume(job.value, context)
  })
}

function newForm() {
  clearTimeout(pollTimer)
  clearPreview()
  clearSelection()
  job.value = null
  issue.value = ''
  stance.value = '中立'
}

function openJob(id) {
  action('正在打开任务…', async context => { await loadJob(id, context, true); clearSelection() })
}

function retry(number) {
  action('正在提交重试…', async context => {
    const value = await api.retryScanPage(job.value.summary.id, number, policy.value.revision, context.signal)
    context.signal.throwIfAborted()
    clearPreview()
    displayJob(value)
  })
}

function selectReplacement(number) {
  repairPage.value = number
  replacementPicker.value?.click()
}

function replacePhoto(event) {
  const raw = event.target.files?.[0]
  event.target.value = ''
  if (!raw) return
  action('正在处理高清照片…', async context => {
    const value = job.value
    const number = repairPage.value
    const file = await prepareScanFile(raw, policy.value, { highDetail: true })
    const spec = await scanFileSpec(file, policy.value.chunkBytes, context.signal)
    const updated = await api.prepareReplacement(value.summary.id, number, spec, policy.value.revision, context.signal)
    context.signal.throwIfAborted()
    displayJob(updated)
    try {
      await uploadFile(updated, updated.replacement, file, context, '正在上传替换页')
      const queued = await api.retryScanPage(value.summary.id, number, policy.value.revision, context.signal)
      context.signal.throwIfAborted()
      displayJob(queued)
      clearPreview()
    } catch (error) { if (!context.signal.aborted) await loadJob(value.summary.id, context); throw error }
  })
}

function removeJob() {
  action('正在等待删除确认…', async context => {
    try { await ElMessageBox.confirm('删除后将清除这个任务的文件、识别文字和审查结果。', '删除审查任务', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }) }
    catch { return }
    context.signal.throwIfAborted()
    const id = job.value.summary.id
    await api.deleteScan(id, context.signal)
    context.signal.throwIfAborted()
    jobs.value = jobs.value.filter(item => item.id !== id)
    newForm()
  })
}

function download(file) {
  action('正在下载…', async context => {
    const blob = await api.downloadScanFile(job.value.summary.id, file.id, context.signal)
    context.signal.throwIfAborted()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  })
}

watch([() => job.value?.summary.id, pageNumber, () => ready.value, () => job.value?.files[isPdf.value ? 0 : pageNumber.value - 1]?.id], async () => {
  clearPreview()
  if (!job.value || !ready.value || !totalPages.value) return
  const request = new AbortController()
  previewRequest = request
  try {
    const image = await api.scanPreview(job.value.summary.id, pageNumber.value, request.signal)
    if (!request.signal.aborted) preview.value = URL.createObjectURL(image)
  } catch (error) {
    if (!request.signal.aborted) previewIssue.value = error.message || '本页预览暂不可用。'
  }
})
</script>

<template>
  <div class="scan-scroll">
    <article class="scan-sheet">
      <header class="scan-head">
        <div><ScanLine :size="23" aria-hidden="true" /><h1>合同扫描与审查</h1></div>
        <p>上传一组合同照片或一份 PDF，查看逐页审查结果。</p>
      </header>

      <p v-if="issue" class="scan-error" role="alert">{{ issue }}</p>
      <p v-if="busy" class="scan-status" role="status">{{ uploadProgress || busy }}</p>
      <div v-if="!user.token" class="login-state">
        <p>登录后可以提交合同，并在审查记录中查看进度和结果。</p>
        <RouterLink class="primary" :to="{ name: 'auth', query: { from: 'contract-scan' } }">登录</RouterLink>
      </div>
      <template v-else>
        <nav v-if="jobs.length" class="job-history" aria-label="审查记录">
          <div class="history-head"><h2>审查记录</h2><button type="button" :disabled="!!busy" @click="newForm">新建审查</button></div>
          <ul>
            <li v-for="item in jobs" :key="item.id">
              <button type="button" :aria-current="job?.summary.id === item.id ? 'true' : undefined" :disabled="!!busy" @click="openJob(item.id)">
                <span class="file-name">{{ item.title }}</span><small>{{ statuses[item.status] }} · {{ formatTime(item.createdAt) }}</small>
              </button>
            </li>
          </ul>
        </nav>

        <template v-if="!job && policy?.enabled">
          <div class="drop-area" @dragover.prevent @drop.prevent="event => addFiles(event.dataTransfer.files)">
            <Upload :size="23" aria-hidden="true" />
            <button class="primary" type="button" :disabled="!!busy" @click="filePicker?.click()">选择合同文件</button>
            <p>最多 {{ policy.maxPhotos }} 张照片，或一份 {{ policy.maxPdfPages }} 页以内的 PDF。整批 {{ formatSize(policy.maxBytes) }}。</p>
            <input ref="filePicker" type="file" hidden multiple :accept="SCAN_ACCEPT" @change="chooseFiles" />
          </div>

          <ol v-if="selected.length" class="files">
            <li v-for="(file, index) in selected" :key="`${file.name}-${index}`">
              <img v-if="file.thumb" :src="file.thumb" alt="" /><FileText v-else :size="25" aria-hidden="true" />
              <div class="grow"><strong class="file-name">{{ index + 1 }}. {{ file.name }}</strong><small>{{ formatSize(file.size) }}{{ file.optimized ? ' · 扫描副本' : '' }}</small></div>
              <div class="actions">
                <button v-if="file.thumb" type="button" :disabled="!!busy" @click="rotate(index)">旋转</button>
                <button v-if="selected.length > 1" type="button" :disabled="!!busy || index === 0" :aria-label="`第 ${index + 1} 页上移`" @click="move(index, -1)"><ArrowUp :size="15" /></button>
                <button v-if="selected.length > 1" type="button" :disabled="!!busy || index === selected.length - 1" :aria-label="`第 ${index + 1} 页下移`" @click="move(index, 1)"><ArrowDown :size="15" /></button>
                <button type="button" :disabled="!!busy" @click="removeFile(index)">移除</button>
              </div>
            </li>
          </ol>

          <div class="submit-area">
            <label>审查立场<select v-model="stance" :disabled="!!busy">
              <option v-for="item in ['中立', '劳动者', '用人单位', '承租方', '出租方', '甲方', '乙方']" :key="item">{{ item }}</option>
            </select></label>
            <p>{{ consent }} 照片以优化后的扫描副本上传，手机原图保留。文件和结果在任务完成后保留 {{ policy.retentionHours }} 小时。</p>
            <button class="primary" type="button" :disabled="!!busy || !selected.length" @click="startNew">开始审查<span v-if="selected.length"> · {{ formatSize(selectedBytes) }}</span></button>
          </div>
        </template>

        <p v-else-if="!job && policy && !policy.enabled" class="scan-status">合同扫描暂不可用。</p>
        <button v-else-if="!job && !policy && !busy" type="button" @click="openWorkspace(user.token)">重新加载</button>

        <section v-if="job" class="job-detail">
          <div class="detail-head"><div><h2>{{ job.summary.title }}</h2><small>{{ statuses[status] }} · {{ job.stance }} · 保存至 {{ formatTime(job.summary.expiresAt) }}</small></div>
            <button type="button" :disabled="!!busy" @click="removeJob">删除任务</button>
          </div>
          <p v-if="job.issue" class="scan-status">{{ job.issue }}</p>
          <template v-if="status === 'DRAFT'">
            <ul class="files">
              <li v-for="(file, index) in job.files" :key="file.id">
                <FileText :size="22" aria-hidden="true" /><div class="grow"><strong class="file-name">{{ file.name }}</strong><small>{{ formatSize(file.received) }} / {{ formatSize(file.size) }}</small></div>
                <label v-if="file.received < file.size" class="file-select" :class="{ disabled: busy }">选择文件续传<input type="file" :accept="SCAN_ACCEPT" :disabled="!!busy" @change="event => chooseResume(event, index)" /></label>
              </li>
            </ul>
            <p>{{ consent }}</p>
            <button type="button" class="primary" :disabled="!!busy || (!selected.length && job.files.some(file => file.received < file.size))" @click="continueDraft">继续审查</button>
          </template>
          <div v-else-if="status === 'PAUSED'" class="submit-area">
            <p>{{ consent }}</p><button type="button" class="primary" :disabled="!!busy || !policy?.enabled" @click="continueDraft">确认并继续</button>
          </div>

          <template v-if="processing(status) || ready">
            <div v-if="totalPages" class="coverage">
              <span>已识别 {{ scanCount }} / {{ totalPages }} 页</span><span>已审查 {{ reviewCount }} / {{ totalPages }} 页</span>
              <progress :max="totalPages" :value="status === 'SCANNING' ? scanCount : reviewCount" :aria-label="status === 'SCANNING' ? '识别进度' : '审查进度'" />
            </div>
            <p v-if="processing(status)" class="scan-status" role="status">任务在后台运行，可以离开页面，稍后从审查记录回来查看。</p>
          </template>

          <template v-if="ready">
            <div class="downloads" v-if="job.files.length">
              <details><summary>{{ isPdf ? '下载 PDF' : '下载扫描副本' }}</summary>
                <ul><li v-for="(file, index) in job.files" :key="file.id"><button type="button" :disabled="!!busy || file.received !== file.size" @click="download(file)">{{ isPdf ? file.name : `第 ${index + 1} 页 · ${file.name}` }}</button></li></ul>
              </details>
            </div>
            <div v-if="totalPages" class="report">
              <section class="page-preview">
                <div class="page-nav"><button type="button" :disabled="pageNumber === 1" aria-label="上一页" @click="pageNumber--"><ChevronLeft :size="18" /></button>
                  <label>页码 <select v-model.number="pageNumber"><option v-for="number in totalPages" :key="number" :value="number">{{ number }} / {{ totalPages }}</option></select></label>
                  <button type="button" :disabled="pageNumber === totalPages" aria-label="下一页" @click="pageNumber++"><ChevronRight :size="18" /></button></div>
                <p v-if="currentPage?.warning" class="scan-error">{{ currentPage.warning }}</p>
                <div v-if="currentPage?.warning || !currentPage?.reviewed" class="page-actions">
                  <button type="button" :disabled="!!busy || !policy?.enabled" @click="retry(pageNumber)">重试此页</button>
                  <button v-if="!isPdf" type="button" :disabled="!!busy || !policy?.enabled" @click="selectReplacement(pageNumber)">补传高清照片</button>
                </div>
                <p v-if="job.replacementPage && job.replacement?.received === job.replacement?.size" class="scan-status">
                  第 {{ job.replacementPage }} 页替换文件已上传。 <button type="button" :disabled="!!busy" @click="retry(job.replacementPage)">提交替换页</button>
                </p>
                <input ref="replacementPicker" type="file" hidden :accept="PHOTO_ACCEPT" @change="replacePhoto" />
                <img v-if="preview" class="page-image" :src="preview" :alt="`合同第 ${pageNumber} 页`" />
                <p v-else class="scan-status">{{ previewIssue || '正在准备预览…' }}</p>
              </section>
              <section class="findings">
                <div class="history-head"><h3>风险清单</h3><label>等级 <select v-model="filter"><option v-for="item in ['全部', '高', '中', '低']" :key="item">{{ item }}</option></select></label></div>
                <p v-if="job.crossChecked === null && reviewCount" class="scan-status">跨页核对待完成。 <button :disabled="!!busy" type="button" @click="retry(0)">重新汇总</button></p>
                <div v-if="job.pages.some(item => item.warning || !item.reviewed)" class="page-warnings">
                  <strong>待核对页</strong>
                  <button v-for="item in job.pages.filter(item => item.warning || !item.reviewed)" :key="item.page" type="button" @click="pageNumber = item.page">第 {{ item.page }} 页</button>
                </div>
                <p v-if="!risks.length" class="scan-status">{{ reviewCount ? '当前筛选下没有可定位的风险记录。' : '审查结果待补充。' }}</p>
                <ol v-else>
                  <li v-for="(risk, index) in risks" :key="index">
                    <h4><span class="severity" :data-level="risk.severity">{{ risk.severity }}</span>{{ risk.title }}</h4>
                    <p>{{ risk.suggestion }}</p>
                    <blockquote v-for="source in risk.evidence" :key="`${source.page}-${source.quote}`">
                      <button type="button" @click="pageNumber = source.page">第 {{ source.page }} 页</button> {{ source.quote }}
                    </blockquote>
                  </li>
                </ol>
                <p class="disclosure">请结合原件复核识别内容与审查建议。</p>
              </section>
            </div>
          </template>
        </section>
      </template>
    </article>
  </div>
</template>

<style scoped>
.scan-scroll { height: 100%; overflow: auto; padding: 28px clamp(16px, 3vw, 46px) 64px; }
.scan-sheet { max-width: 1240px; margin: auto; padding: clamp(20px, 4vw, 52px); background: var(--paper); border: 1px solid var(--line); }
.scan-head { padding-bottom: 22px; border-bottom: 2px solid var(--ink); }
.scan-head > div { display: flex; align-items: center; gap: 12px; color: var(--blue, #213988); }
.scan-head h1 { color: var(--ink); margin: 0; font-size: clamp(25px, 3vw, 38px); }
.scan-head p, .drop-area p, .submit-area p { color: #555e6c; margin: 8px 0 0; line-height: 1.65; }
.scan-sheet h2, .scan-sheet h3 { margin: 0; }
.scan-sheet small { color: #5a6371; display: block; margin-top: 4px; }
.scan-sheet button, .file-select, .scan-sheet select { border: 1px solid #d5d9e0; padding: 7px 10px; color: var(--ink); background: var(--paper); font: inherit; }
.scan-sheet button:not(:disabled), .file-select, .scan-sheet select { cursor: pointer; }
.scan-sheet button:disabled, .file-select.disabled { opacity: .45; }
.scan-sheet :focus-visible { outline: 2px solid #213988; outline-offset: 2px; }
.scan-sheet .primary { display: inline-block; padding: 10px 18px; border: 1px solid var(--ink); background: var(--ink); color: #fff; font-weight: 600; text-decoration: none; }
.login-state { padding: 20px 0; }
.scan-error, .scan-status { margin: 14px 0; padding: 9px 12px; line-height: 1.5; background: #f1f3f7; }
.scan-error { background: #fff3ed; color: #8c331f; }
.job-history { margin: 20px 0; padding-bottom: 11px; border-bottom: 1px solid #d5d9e0; }
.history-head, .detail-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.job-history ul { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(220px, 100%), 1fr)); gap: 8px; list-style: none; padding: 0; max-height: 210px; overflow: auto; }
.job-history button { text-align: left; }
.job-history li button { width: 100%; }
.job-history button[aria-current] { background: #edf0f9; border-color: #5366a4; }
.drop-area { display: grid; justify-items: center; gap: 10px; text-align: center; padding: 24px 14px; margin-top: 22px; border: 1px dashed #8491af; background: #f7f8fc; }
.files { padding: 0; list-style: none; margin: 14px 0; }
.files li { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 8px 0; border-bottom: 1px solid #e4e7ed; }
.files img { width: 38px; height: 44px; object-fit: cover; }
.grow { flex: 1 1 100px; min-width: 0; }
.file-name { display: block; overflow-wrap: anywhere; }
.actions, .page-actions, .page-warnings { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; }
.actions button, .page-nav button { display: inline-flex; align-items: center; min-height: 34px; }
.submit-area, .job-detail { margin-top: 22px; }
.submit-area > button { margin-top: 12px; }
.submit-area label select, .history-head select { margin-left: 8px; }
.file-select { position: relative; }
.file-select input { position: absolute; inline-size: 1px; block-size: 1px; opacity: 0; }
.file-select:focus-within { outline: 2px solid #213988; }
.detail-head h2 { overflow-wrap: anywhere; }
.detail-head > button { flex: none; }
.coverage { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 18px; margin-top: 16px; }
.coverage progress { width: min(180px, 100%); accent-color: #213988; }
.downloads { margin: 16px 0; }
.downloads summary { cursor: pointer; width: fit-content; }
.downloads ul { max-height: 200px; overflow: auto; margin: 8px 0; padding-left: 24px; }
.downloads li { margin-bottom: 3px; }
.report { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; margin-top: 14px; }
.page-preview { background: #f6f7fa; padding: 12px; align-self: start; }
.page-nav { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.page-image { display: block; width: 100%; margin-top: 10px; }
.findings ol { padding: 0; list-style: none; }
.findings li { padding: 12px 0; border-bottom: 1px solid #d5d9e0; }
.findings h4 { margin: 0; line-height: 1.55; }
.findings p { overflow-wrap: anywhere; line-height: 1.6; }
.findings blockquote { margin: 8px 0; padding: 6px 10px; border-left: 2px solid #5366a4; background: #f6f7fa; overflow-wrap: anywhere; }
.findings blockquote button { padding: 4px; color: #213988; }
.page-warnings { margin-top: 10px; }
.page-warnings strong { margin-right: 6px; }
.severity { display: inline-block; margin-right: 8px; padding: 0 4px; color: #213988; border: 1px solid #8491af; font-size: .85em; }
.severity[data-level="高"] { color: #922925; border-color: #bd736f; }
.disclosure { color: #555e6c; font-size: .9em; }
.page-actions { margin-top: 8px; }
@media (max-width: 860px) { .scan-scroll { padding-top: 60px; } }
@media (max-width: 700px) { .report { grid-template-columns: minmax(0, 1fr); } }
</style>
