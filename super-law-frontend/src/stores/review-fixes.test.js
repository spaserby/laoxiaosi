import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { JSDOM } from 'jsdom'
import createDOMPurify from 'dompurify'
import axios from 'axios'

const savedStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
const savedAdapter = axios.defaults.adapter
const savedFetch = globalThis.fetch
const values = new Map()
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
  getItem: key => values.get(key) || null, setItem: (key,value) => values.set(key,value), removeItem: key => values.delete(key)
} })
let reply = config => ({ data: [] })
axios.defaults.adapter = async config => ({ data: await reply(config), status: 200, statusText: 'OK', headers: {}, config })
const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
const { useChatStore } = await vite.ssrLoadModule('/src/stores/chat.js')
const { useUserStore } = await vite.ssrLoadModule('/src/stores/user.js')
const { renderReplyMarkdown } = await vite.ssrLoadModule('/src/utils/reply-markdown.js')
after(async () => {
  await vite.close(); axios.defaults.adapter = savedAdapter; globalThis.fetch = savedFetch
  if (savedStorage) Object.defineProperty(globalThis, 'localStorage', savedStorage)
  else delete globalThis.localStorage
})
function setup() {
  values.clear(); const pinia = createPinia(); setActivePinia(pinia)
  const chat = useChatStore(pinia), user = useUserStore(pinia)
  reply = () => ({ data: [] })
  return { chat, user, pinia }
}
test('Markdown净化事件、脚本与危险链接，同时保留表格和正常链接', () => {
  const window = new JSDOM('').window
  try {
    const purifier = createDOMPurify(window)
    const html = renderReplyMarkdown('<img src="missing" onerror="window.synthetic=1"><svg onload="window.synthetic=2"></svg>'
      + '<script>window.synthetic=3</script>\n[危险](javascript:alert(1))\n\n|列|值|\n|-|-|\n|A|B|\n\n[来源](https://example.com)', purifier)
    const page = new JSDOM(html).window.document
    assert.equal(page.querySelectorAll('[onerror],[onload],script,svg').length, 0)
    assert.ok(!Array.from(page.querySelectorAll('a')).some(a => a.getAttribute('href')?.startsWith('javascript:')))
    assert.ok(page.querySelector('table')); assert.ok(page.querySelector('a[href="https://example.com"]'))
  } finally { window.close() }
})
test('换身份清理咨询/附件，并丢弃旧身份迟到的历史响应', async () => {
  const { chat, user } = setup()
  chat.currentSessionId = 'synthetic-a'; chat.messages['synthetic-a'] = [{ role: 'user', content: '合成A咨询' }]
  chat.sessions = [{ sessionId: 'synthetic-a' }]; chat.attachmentsBySession['synthetic-a'] = [{ fileId: 'synthetic-file' }]
  let resolveOld
  reply = config => config.url.includes('/messages') ? new Promise(resolve => { resolveOld = resolve }) : { data: [] }
  const pending = chat.loadHistory('synthetic-a')
  while (!resolveOld) await new Promise(resolve => setTimeout(resolve, 0))
  user.logout()
  assert.equal(chat.currentSessionId, null); assert.deepEqual(chat.messages, {}); assert.deepEqual(chat.attachmentsBySession, {})
  reply = config => config.url === '/auth/login' ? { token: 'synthetic-b', user: { id: 900002, role: 'USER' } } : { data: [] }
  await user.login({ username: 'synthetic-b', password: 'test-input' })
  resolveOld({ data: [{ id: '1', role: 'user', content: '合成A咨询' }], hasMore: false })
  await pending
  assert.deepEqual(chat.messages, {}); assert.ok(!chat.sessions.some(s => s.sessionId === 'synthetic-a'))
})
test('旧身份的附件和侧栏响应不能恢复缓存', async () => {
  const { chat } = setup()
  let resolveFiles, resolveList
  reply = config => new Promise(resolve => { if (config.url === '/session/list') resolveList=resolve; else resolveFiles=resolve })
  const list = chat.loadSessions(), files = chat.refreshAttachments('synthetic-a')
  while (!resolveList || !resolveFiles) await new Promise(resolve => setTimeout(resolve, 0))
  chat.resetIdentity()
  resolveList({ data: [{ sessionId: 'synthetic-a' }] }); resolveFiles([{ fileId: 'synthetic-file' }])
  await Promise.all([list,files]); assert.deepEqual(chat.sessions, []); assert.deepEqual(chat.attachmentsBySession, {})
})
test('断流前已接收正文保留在错误前，终态不再有缓冲追加', async () => {
  const { chat } = setup(); chat.loadSessions = async () => {}
  globalThis.fetch = async () => new Response('event: token\ndata: "合成正文"\n\nevent: error\ndata: 合成失败\n\n', { headers: { 'content-type': 'text/event-stream' } })
  chat.send('合成提问', [])
  const end = Date.now()+2000
  while (chat.phase !== 'idle' && Date.now()<end) await new Promise(resolve => setTimeout(resolve, 5))
  const answer = chat.currentMessages.at(-1)
  assert.ok(answer.content.startsWith('合成正文\n\n[生成失败]'))
  const final = answer.content; await new Promise(resolve => setTimeout(resolve, 150))
  assert.equal(answer.content, final); assert.equal(chat._drain, null); assert.equal(answer.status, 'FAILED')
})
test('历史加载保留结构化来源和覆盖信息', async () => {
  const { chat } = setup(); chat.refreshAttachments = async () => {}
  const context = { sources: [{ fileId: 'synthetic-file', parentId: 2 }], warnings: [], selectedSections: 1, totalSections: 3, fullCoverage: false }
  reply = () => ({ data: [{ id: '2', role: 'assistant', content: '合成答复', citations: [{ lawName: '合成规则', articleNo: '第1条', text: '合成条款' }], attachmentContext: context, evidenceAvailable: true }], hasMore: false })
  await chat.switchSession('synthetic-evidence')
  assert.deepEqual(chat.currentMessages[0].attachmentContext, context)
  assert.equal(chat.currentMessages[0].citations[0].text, '合成条款')
})
test('身份切换后旧SSE和缓冲不能写入新身份', async () => {
  const { chat } = setup(); chat.loadSessions=async () => {}
  let controller
  globalThis.fetch=async () => new Response(new ReadableStream({ start(c) { controller=c } }), { headers: { 'content-type': 'text/event-stream' } })
  chat.send('合成旧身份问题',[])
  while(!controller) await new Promise(resolve => setTimeout(resolve,0))
  controller.enqueue(new TextEncoder().encode('event: token\ndata: "合成旧正文"\n\n'))
  await new Promise(resolve => setTimeout(resolve,0))
  chat.resetIdentity()
  controller.enqueue(new TextEncoder().encode('event: token\ndata: "迟到正文"\n\nevent: done\ndata: [DONE]\n\n')); controller.close()
  await new Promise(resolve => setTimeout(resolve,100))
  assert.deepEqual(chat.messages,{}); assert.equal(chat.phase,'idle'); assert.equal(chat.currentSessionId,null)
})
test('退出后迟到的登录响应不能重新登录', async () => {
  const { user }=setup(); let resolveLogin
  reply=config => config.url==='/auth/login' ? new Promise(resolve => { resolveLogin=resolve }) : { data: [] }
  const pending=user.login({username:'synthetic-user',password:'test-input'})
  while(!resolveLogin) await new Promise(resolve => setTimeout(resolve,0))
  user.logout(); resolveLogin({token:'synthetic-old-session',user:{id:900001}}); await pending
  assert.equal(user.token,''); assert.equal(user.user,null)
})
test('中文输入法确认候选保留草稿，普通Enter仍可发送', async () => {
  const { chat, pinia } = setup()
  const previous = globalThis.window
  globalThis.window = { matchMedia: () => ({ matches: false, addEventListener() {} }), addEventListener() {}, removeEventListener() {} }
  try {
    const { default: Input } = await vite.ssrLoadModule('/src/components/ChatInput.vue')
    const sends = []; chat.send = (...args) => sends.push(args)
    const vnode = h(Input), app = createSSRApp({ render: () => vnode }); app.use(pinia); await renderToString(app)
    const state = vnode.component.setupState; state.text = '合成中文草稿'
    let prevented = false
    state.onKey({ key: 'Enter', isComposing: true, keyCode: 229, shiftKey: false, preventDefault() { prevented=true } })
    assert.equal(sends.length, 0); assert.equal(state.text, '合成中文草稿'); assert.equal(prevented, false)
    state.onKey({ key: 'Enter', isComposing: false, keyCode: 13, shiftKey: false, preventDefault() {} })
    assert.equal(sends.length, 1); assert.equal(state.text, '')
  } finally { if (previous === undefined) delete globalThis.window; else globalThis.window = previous }
})
