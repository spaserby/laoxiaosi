/**
 * EventSource 兼容的 SSE 客户端（fetch + ReadableStream 实现）
 *
 * <p><b>为什么不用 EventSource</b>：它不支持自定义请求头，凭证只能拼进 URL，
 * 而 URL 会进 nginx/代理访问日志——24h 有效的 JWT 与咨询原文都曾因此落盘。
 * 本实现把凭证与问题放进请求头/请求体，URL 里不再出现任何敏感参数。
 *
 * <p><b>接口刻意对齐 EventSource 子集</b>（addEventListener / close / e.data），
 * 让调用方的十几个事件监听一行都不用改。
 *
 * <p><b>中断语义</b>：流在没收到 done 事件的情况下结束（服务端崩溃、代理断链）时
 * 补发一次 error，调用方据此结束"流式占位"，不会永久卡在"生成中"。
 */
export class SseClient {
  /**
   * @param {string} url 请求地址
   * @param {{method?: string, headers?: object, body?: any}} options
   *        body 存在时以 POST JSON 发送（问题与凭证都在请求体/头里，不进 URL）
   */
  constructor(url, options = {}) {
    this.url = url
    this.method = options.method || (options.body ? 'POST' : 'GET')
    this.headers = { ...(options.headers || {}) }
    this.body = options.body
    this.readyState = 0
    this._handlers = Object.create(null)
    this._closed = false
    this._sawDone = false
    this._controller = new AbortController()
    this._run()
  }

  addEventListener(type, fn) {
    ;(this._handlers[type] || (this._handlers[type] = [])).push(fn)
  }

  close() {
    this._closed = true
    this.readyState = 2
    try {
      this._controller.abort()
    } catch {
      /* 已关闭 */
    }
  }

  _emit(type, data) {
    const list = this._handlers[type]
    if (!list || !list.length) return
    for (const fn of list) {
      try {
        fn({ type, data })
      } catch (e) {
        console.error('[sse] 事件处理异常:', type, e)
      }
    }
  }

  async _run() {
    try {
      const init = { method: this.method, headers: this.headers, signal: this._controller.signal }
      if (this.body !== undefined) {
        init.headers['Content-Type'] = 'application/json'
        init.body = JSON.stringify(this.body)
      }
      const resp = await fetch(this.url, init)
      if (!resp.ok || !resp.body) {
        // 403（会话越权/配额）等：把状态码交给 error 处理器，调用方决定提示文案
        this._emit('error', { status: resp.status })
        return
      }
      this.readyState = 1
      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buf = ''
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          this._dispatchBlock(buf.slice(0, idx))
          buf = buf.slice(idx + 2)
        }
        if (this._closed) break
      }
      if (!this._closed && !this._sawDone) {
        this._emit('error', { message: '连接中断' })
      }
    } catch (e) {
      if (!this._closed) {
        this._emit('error', { message: String((e && e.message) || e) })
      }
    }
  }

  /** 解析一个 SSE 事件块：event: xxx / data: yyy（多行 data 按协议用 \n 连接） */
  _dispatchBlock(block) {
    let type = 'message'
    const data = []
    for (const rawLine of block.split('\n')) {
      const line = rawLine.replace(/\r$/, '')
      if (!line || line.startsWith(':')) continue
      if (line.startsWith('event:')) type = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (type === 'done') this._sawDone = true
    if (data.length) this._emit(type, data.join('\n'))
  }
}
