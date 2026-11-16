export function attachmentsReady(files, now = Date.now(), native = false) {
  return files.every(f => f.expiresAt > now &&
    (native || ['STORED', 'QUEUED', 'PROCESSING', 'READY', 'PARTIAL'].includes(f.status)))
}

export function attachmentState(file, now = Date.now()) {
  if (file.expiresAt <= now) return '已过期，请移除或重新上传'
  if (file.status === 'STORED') return '待发送'
  if (file.status === 'QUEUED') return '排队中'
  if (file.status === 'PROCESSING') return '解析 / OCR / 建索引中'
  if (file.status === 'FAILED') return '处理失败'
  const pages = file.totalPages > 1 ? ` · ${file.processedPages}/${file.totalPages} 页` : ''
  return `${file.status === 'PARTIAL' ? '部分可用' : '已就绪'} · ${file.chars || 0} 字${pages}`
}
