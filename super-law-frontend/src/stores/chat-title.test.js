import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia } from 'pinia'

// 使用真实 store/Pinia/SSE 调用链，网络仅返回合成的 done 事件。
const savedFetch = globalThis.fetch
const savedStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
const values = new Map()
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value) }
})
globalThis.fetch = async () => new Response('event: done\ndata: [DONE]\n\n', { headers: { 'content-type': 'text/event-stream' } })
const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
const { useChatStore } = await vite.ssrLoadModule('/src/stores/chat.js')
after(async () => {
  await vite.close()
  globalThis.fetch = savedFetch
  if (savedStorage) Object.defineProperty(globalThis, 'localStorage', savedStorage)
  else delete globalThis.localStorage
})
function store() {
  const chat = useChatStore(createPinia())
  chat.loadSessions = async () => {}
  return chat
}
function finish(chat) {
  chat.es?.close()
  chat.es = null
  chat.phase = 'idle'
}

test('普通咨询立即使用首条问题作标题', () => {
  const chat = store()
  chat.send('合成测试：解除合同需要什么条件', [])
  assert.equal(chat.currentTitle, '合成测试：解除合同需要什么条件')
  finish(chat)
})
test('上传预建会话的占位标题在首条提问时转正', () => {
  const chat = store()
  const sid = chat.ensureSession()
  assert.equal(chat.currentTitle, '新对话')
  chat.send('合成测试：审查付款与验收条件', [{ fileId: 'synthetic-file' }])
  assert.equal(chat.currentSessionId, sid)
  assert.equal(chat.currentTitle, '合成测试：审查付款与验收条件')
  finish(chat)
})
test('追问和既有自定义标题不会被重命名', () => {
  const chat = store()
  chat.send('合成测试：首个问题', [])
  finish(chat)
  chat.send('合成测试：后续追问', [])
  assert.equal(chat.currentTitle, '合成测试：首个问题')
  finish(chat)
  chat.sessions[0].title = '保留已有标题'
  chat.messages[chat.currentSessionId] = []
  chat.send('合成测试：另一问题', [])
  assert.equal(chat.currentTitle, '保留已有标题')
  finish(chat)
})
