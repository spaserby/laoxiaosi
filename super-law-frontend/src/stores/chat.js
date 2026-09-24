import { defineStore } from 'pinia'
import { listSessions, getSessionMessages, deleteSession, stopChat } from '../api/session'

/**
 * 聊天 Store：会话列表 / 消息 / 生成状态机（idle | streaming）
 *
 * SSE 协议（与后端 ChatController 对齐）：
 *   token → 追加到当前 assistant 消息
 *   done  → 正常结束，关闭 EventSource
 *   error → 后端异常事件（带 data），追加错误文案后结束
 * 连接级 error（无 data）视为流中断，同样收尾。
 *
 * 停止 = 关 EventSource + POST /chat/stop（双中断，与后端无状态停止设计契合）。
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [],            // [{sessionId, title, createdAt, updatedAt}]
    currentSessionId: null,  // null = 欢迎态（首次发送时生成 id）
    messages: {},            // sessionId -> [{role, content, streaming?}]
    phase: 'idle',           // idle | streaming
    blankMode: false,        // 新建咨询后的干净页态（居中问候+居中输入框，非报纸首页）
    quotaDeny: null,         // 配额拒绝事件（{code,message,tier,remaining,resetAt}），ChatView 弹引导
    es: null                 // 当前 EventSource 实例
  }),

  getters: {
    currentMessages: (s) => s.messages[s.currentSessionId] || [],
    currentTitle: (s) => {
      const cur = s.sessions.find(x => x.sessionId === s.currentSessionId)
      return cur ? cur.title : '新对话'
    },
    isStreaming: (s) => s.phase === 'streaming'
  },

  actions: {
    /** 拉取会话列表（侧栏） */
    async loadSessions() {
      try {
        this.sessions = await listSessions()
      } catch (e) {
        console.warn('会话列表加载失败', e)
        this.sessions = []
      }
    },

    /** 新建对话：回到欢迎态（不立即建后端会话，首条消息发送时才建） */
    newSession() {
      if (this.isStreaming) this.stop()
      this.currentSessionId = null
      this.blankMode = true
    },

    /** 切换会话：懒加载历史消息 */
    async switchSession(sessionId) {
      if (this.isStreaming) this.stop()
      this.currentSessionId = sessionId
      this.blankMode = false
      if (!this.messages[sessionId]) {
        try {
          const list = await getSessionMessages(sessionId)
          this.messages[sessionId] = list.map(m => ({ role: m.role, content: m.content }))
        } catch (e) {
          console.warn('历史消息加载失败', e)
          this.messages[sessionId] = []
        }
      }
    },

    /** 删除会话 */
    async removeSession(sessionId) {
      await deleteSession(sessionId)
      delete this.messages[sessionId]
      if (this.currentSessionId === sessionId) this.currentSessionId = null
      await this.loadSessions()
    },

    /** 发送消息：建会话（首轮）→ 推本地消息 → 开 SSE 流 */
    send(text, files) {
      if (!text || !text.trim() || this.isStreaming) return
      this.blankMode = false
      const sid = this.currentSessionId || this.createSessionId(text)
      this.currentSessionId = sid
      // 先赋值、再从 state 重读：赋值表达式返回的是原始数组，直接使用会绕过响应式代理——
      // 后续 push/属性写入不触发重渲染（首轮流式"结束时才一次性显示"的根因）
      if (!this.messages[sid]) this.messages[sid] = []
      const msgs = this.messages[sid]
      const userMsg = { role: 'user', content: text }
      // 附件卡数据（消息泡展示文件名/字数）
      if (files && files.length) {
        userMsg.attachments = files.map(f => ({ fileId: f.fileId, name: f.name, chars: f.chars, truncated: f.truncated, kind: f.kind, url: f.url || '', imageCount: (f.images || []).length }))
      }
      msgs.push(userMsg)
      msgs.push({
        role: 'assistant', content: '', streaming: true, citations: [],
        stage: '', reasoning: '',          // 思考面板数据（阶段进度 + 模型真实推理流）
        startTs: Date.now(), firstTokenTs: 0, thinkSeconds: 0
      })
      // 取响应式代理引用（而非 raw 对象）——事件回调里改 raw 不触发视图，
      // 会导致思考内容/正文"结束后才一次性显示"；代理引用保证逐 chunk 实时渲染
      const assistant = msgs[msgs.length - 1]
      this.phase = 'streaming'

      // 登录态透传——EventSource 不支持自定义请求头，token 走 URL 参数（匿名时不拼）
      const token = localStorage.getItem('sl_token')
      const url = `/chat/stream?sessionId=${encodeURIComponent(sid)}&userMessage=${encodeURIComponent(text)}`
        + (token ? `&token=${encodeURIComponent(token)}` : '')
        + (files && files.length ? `&fileIds=${files.map(f => encodeURIComponent(f.fileId)).join(',')}` : '')
      const es = new EventSource(url)
      this.es = es

      // SSE data 已 JSON 编码（防前导空格被 SSE 协议剥离）；解析失败降级用原文
      const parseSse = (data) => { try { return JSON.parse(data) } catch { return data } }

      /* 部分 provider 对多模态请求突发整包下发
         （reasoning/content 一次性到达），按 24ms 频率自适应吐出，恢复"滚动持续输出"观感；
         正常逐 token 流到达时缓冲恒小（≈1 字符延迟），观感无损 */
      let pendingTok = ''
      let pendingRea = ''
      let drainTimer = null
      const drainTick = () => {
        if (pendingTok.length) {
          const n = Math.max(1, Math.ceil(pendingTok.length / 6))
          assistant.content += pendingTok.slice(0, n)
          pendingTok = pendingTok.slice(n)
        }
        if (pendingRea.length) {
          const n = Math.max(1, Math.ceil(pendingRea.length / 6))
          assistant.reasoning += pendingRea.slice(0, n)
          pendingRea = pendingRea.slice(n)
        }
        if (!pendingTok && !pendingRea && drainTimer) {
          clearInterval(drainTimer)
          drainTimer = null
        }
      }
      const ensureDrain = () => { if (!drainTimer) drainTimer = setInterval(drainTick, 24) }
      const flushPending = () => {
        if (drainTimer) { clearInterval(drainTimer); drainTimer = null }
        if (pendingTok) { assistant.content += pendingTok; pendingTok = '' }
        if (pendingRea) { assistant.reasoning += pendingRea; pendingRea = '' }
      }
      const discardPending = () => {
        if (drainTimer) { clearInterval(drainTimer); drainTimer = null }
        pendingTok = ''
        pendingRea = ''
      }
      this._drain = { flush: flushPending, discard: discardPending }

      es.addEventListener('token', (e) => {
        if (!assistant.firstTokenTs) assistant.firstTokenTs = Date.now()
        pendingTok += parseSse(e.data)
        ensureDrain()
      })
      // 模型真实推理内容流（reasoning_content 透传，DeepSeek 式思考面板）
      es.addEventListener('reasoning', (e) => {
        pendingRea += parseSse(e.data)
        ensureDrain()
      })
      // 思考过程阶段事件：首 token 前的黑盒等待变为可见进度（路由/改写/检索/生成）
      es.addEventListener('stage', (e) => { assistant.stage = e.data })
      // 结构化引用事件——引用卡展示"检索命中了什么"（可信），正则解析降级为兜底
      es.addEventListener('citation', (e) => {
        try {
          const list = JSON.parse(e.data)
          // 按法条去重（稠密+稀疏双路可能同条双命中）：重复 lawName+articleNo 会使
          // CitationCard 的 v-for key 冲突，Vue patch 错乱导致引用卡子树渲染冻结
          const best = new Map()
          for (const c of list) {
            const k = c.lawName + c.articleNo
            const old = best.get(k)
            if (!old || (c.score || 0) > (old.score || 0)) best.set(k, c)
          }
          assistant.citations = [...best.values()]
        } catch { /* 忽略坏 JSON */ }
      })
      // 配额拒绝事件——移除占位 assistant 消息，交 ChatView 弹引导（登录/重置倒计时）
      es.addEventListener('quota', (e) => {
        try {
          this.quotaDeny = JSON.parse(e.data)
        } catch {
          this.quotaDeny = { code: 'QUOTA_EXCEEDED', message: e.data, tier: 'guest' }
        }
        // 整数组替换（而非 splice）：替换 state 键引用确保列表渲染必定刷新，
        // 避免占位气泡在配额拒绝后残留"思考中"
        this.messages[sid] = msgs.filter(m => m !== assistant)
        discardPending()   // 占位消息已移除，排空缓冲一并丢弃防定时器泄漏
        this.finish(sid)
      })
      // 审校不通过重试——清空当前 assistant 消息（含引用），等待第二轮重新流式填充
      es.addEventListener('retry', () => {
        discardPending()
        assistant.content = ''; assistant.citations = []; assistant.stage = ''
        assistant.reasoning = ''; assistant.firstTokenTs = 0; assistant.startTs = Date.now()
      })
      es.addEventListener('done', () => {
        flushPending()   // 收尾：剩余缓冲立即落屏，保证终态完整
        // 思考时长 = 发送 → 首 token（含路由/改写/检索/模型思考），DeepSeek 式"已思考 N 秒"
        assistant.thinkSeconds = assistant.firstTokenTs
          ? ((assistant.firstTokenTs - assistant.startTs) / 1000).toFixed(1)
          : ((Date.now() - assistant.startTs) / 1000).toFixed(1)
        this.finish(sid)
      })
      es.addEventListener('error', (e) => {
        // 后端命名 error 事件带 data；连接级错误无 data（如服务中断）
        if (e.data) assistant.content += `\n\n[生成失败] ${e.data}`
        this.finish(sid)
      })
    },

    /** 停止生成：关 SSE + 通知后端取消（双中断）；排空缓冲丢弃（停止即冻结画面） */
    stop() {
      const sid = this.currentSessionId
      if (this._drain) { this._drain.discard(); this._drain = null }
      if (this.es) { this.es.close(); this.es = null }
      if (sid) stopChat(sid).catch(() => {})
      this.finish(sid, true)
    },

    /** 流收尾：标记消息完成、状态回 idle、刷新会话列表（标题/排序可能变化） */
    finish(sid, stopped = false) {
      if (this.es) { this.es.close(); this.es = null }
      const msgs = this.messages[sid] || []
      const last = msgs[msgs.length - 1]
      if (last && last.role === 'assistant') {
        last.streaming = false
        if (stopped && !last.content) last.content = '（已停止生成）'
      }
      this.phase = 'idle'
      this.loadSessions()
    },

    /** 首轮发送时本地生成会话 id，并乐观插入侧栏列表 */
    createSessionId(firstMessage) {
      const sid = 's-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
      const title = firstMessage.length > 20 ? firstMessage.slice(0, 20) : firstMessage
      this.sessions.unshift({ sessionId: sid, title, createdAt: Date.now(), updatedAt: Date.now() })
      return sid
    }
  }
})
