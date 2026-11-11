import DOMPurify from 'dompurify'
import { marked } from 'marked'

const escapeHtml = text => text.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

/** 浏览器使用 DOM 净化；无 DOM 的 SSR 环境降级为转义文本，绝不返回未净化 HTML。 */
export function renderReplyMarkdown(content, purifier = DOMPurify) {
  const markdown = String(content || '').replace(/([。！？；”"…])(#{1,4})(?=\s*\S)/g, '$1\n$2')
  if (typeof purifier.sanitize !== 'function') return escapeHtml(markdown)
  return purifier.sanitize(marked.parse(markdown, { breaks: true }), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'iframe', 'object', 'embed'],
    FORBID_ATTR: ['style'], ALLOW_UNKNOWN_PROTOCOLS: false
  })
}
