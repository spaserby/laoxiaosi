import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia } from 'pinia'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import axios from 'axios'

const savedAdapter = axios.defaults.adapter
const savedStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true, value: { getItem: () => null, setItem() {} }
})
let reply = () => ({ data: [] })
const requests = []
axios.defaults.adapter = async config => {
  requests.push(config)
  return { data: await reply(config), status: 200, statusText: 'OK', headers: {}, config }
}
const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
const { useChatStore } = await vite.ssrLoadModule('/src/stores/chat.js')
const { default: MessageList } = await vite.ssrLoadModule('/src/components/MessageList.vue')
after(async () => {
  await vite.close(); axios.defaults.adapter = savedAdapter
  if (savedStorage) Object.defineProperty(globalThis, 'localStorage', savedStorage)
  else delete globalThis.localStorage
})
function setup() {
  const pinia = createPinia()
  const store = useChatStore(pinia)
  store.refreshAttachments = async () => {}
  requests.length = 0
  return { pinia, store }
}
const row = (id, role = 'user') => ({ id: String(id), role, content: `合成历史消息${id}`, status: 'COMPLETE' })

test('原文分页按 ID 去重，拒收 system/tool；切换缓存保留分页状态', async () => {
  const { store } = setup()
  reply = config => config.params?.beforeMessageId
    ? { data: [row(1), row(2, 'assistant'), row(3)], hasMore: false, nextCursor: '' }
    : { data: [row(3), row(4, 'assistant'), row(99, 'system'), row(100, 'tool')], hasMore: true, nextCursor: '3' }
  await store.switchSession('synthetic-history')
  assert.deepEqual(store.currentMessages.map(m => m.id), ['3', '4'])
  await store.loadOlderMessages()
  assert.equal(requests[1].params.beforeMessageId, '3')
  assert.deepEqual(store.currentMessages.map(m => m.id), ['1', '2', '3', '4'])
  assert.equal(store.currentHistoryPage.hasMore, false)
  await store.switchSession('synthetic-history')
  assert.equal(requests.length, 2)
})

test('真实组件不把未知角色和摘要渲染成用户问题', async () => {
  const { pinia, store } = setup()
  store.currentSessionId = 'synthetic-render'
  store.messages['synthetic-render'] = [row(1), row(2, 'assistant'),
    { role: 'system', content: 'PRIVATE_INTERNAL_SUMMARY' }, { role: 'tool', content: 'PRIVATE_TOOL_RESULT' }]
  const app = createSSRApp({ render: () => h(MessageList) }); app.use(pinia)
  const html = await renderToString(app)
  assert.equal((html.match(/class="msg-user"/g) || []).length, 1)
  assert.ok(!html.includes('PRIVATE_INTERNAL_SUMMARY'))
  assert.ok(!html.includes('PRIVATE_TOOL_RESULT'))
  assert.ok(html.includes('合成历史消息1'))
})

test('加载失败可重试，删除后迟到响应不会恢复已删除的缓存', async () => {
  const { store } = setup()
  reply = () => { throw new Error('synthetic transport failure') }
  await store.switchSession('synthetic-retry')
  assert.ok(store.currentHistoryPage.error)
  reply = () => ({ data: [row(1)], hasMore: false })
  await store.switchSession('synthetic-retry')
  assert.equal(store.currentMessages.length, 1)
  let resolveRequest
  reply = config => config.method === 'get' && config.url.includes('/messages')
    ? new Promise(resolve => { resolveRequest = resolve }) : { data: [] }
  const pending = store.switchSession('synthetic-deleted')
  while (!resolveRequest) await new Promise(resolve => setTimeout(resolve, 0))
  store.loadSessions = async () => {}
  await store.removeSession('synthetic-deleted')
  resolveRequest({ data: [row(2)], hasMore: false })
  await pending
  assert.equal(store.messages['synthetic-deleted'], undefined)
})
