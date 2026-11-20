export function formatMiB(bytes) {
  if (bytes > 0 && bytes < 0.05 * 1024 * 1024) return '< 0.1 MiB'
  return `${Number((bytes / (1024 * 1024)).toFixed(1))} MiB`
}

export function attachmentIssue(files, policy, now = Date.now()) {
  if (!files.length) return ''
  if (!policy) return '附件处理暂不可用，请稍后重试'
  if (files.length > policy.maxFiles) return `本轮最多发送 ${policy.maxFiles} 个附件，请移除后重试`
  let total = 0
  for (const file of files) {
    if (file.expiresAt != null && file.expiresAt <= now) return `「${file.name}」已过期，请重新上传`
    const extension = file.extension || (file.name.includes('.') ? file.name.split('.').at(-1).toLowerCase() : '')
    if (!policy.formats.includes(extension)) return `「${file.name}」的格式暂不支持`
    const bytes = file.sizeBytes ?? file.size
    if (!bytes) return `「${file.name}」为空，请移除后重试`
    const limit = policy.imageFormats.includes(extension) ? policy.maxImageBytes : policy.maxDocumentBytes
    if (bytes > limit) return `「${file.name}」超过单文件上限 ${formatMiB(limit)}`
    total += bytes
  }
  if (total > policy.maxTotalBytes) return `所选附件共 ${formatMiB(total)}，本轮最多 ${formatMiB(policy.maxTotalBytes)}，请移除文件或分次发送`
  return ''
}
