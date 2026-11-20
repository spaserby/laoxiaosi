export const PHOTO_ACCEPT = '.jpg,.jpeg,.png,.webp,.heic,.heif,image/jpeg,image/png,image/webp,image/heic,image/heif'
export const SCAN_ACCEPT = `${PHOTO_ACCEPT},.pdf,application/pdf`

export function scanKind(file) {
  if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) return 'pdf'
  return /\.(?:jpe?g|png|webp|hei[cf])$/i.test(file.name) || /^image\/(?:jpeg|png|webp|hei[cf])$/i.test(file.type) ? 'photo' : null
}

export function scanDimensions(width, height, edge, pixels) {
  const scale = Math.min(1, edge / Math.max(width, height), Math.sqrt(pixels / (width * height)))
  return { width: Math.max(1, Math.floor(width * scale)), height: Math.max(1, Math.floor(height * scale)) }
}

function decodeWithImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => { URL.revokeObjectURL(url); resolve(image) }
    image.onerror = () => { URL.revokeObjectURL(url); reject(new Error('无法读取这张照片，请重新选图或使用 JPEG 格式。')) }
    image.src = url
  })
}

async function decodePhoto(file) {
  if (typeof createImageBitmap === 'function') {
    try { return await createImageBitmap(file, { imageOrientation: 'from-image' }) }
    catch { /* Some mobile browsers decode additional formats with an image element. */ }
  }
  return decodeWithImage(file)
}

const encode = (canvas, mime, quality) => new Promise((resolve, reject) =>
  canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('照片优化失败，请重新选择。')), mime, quality))

export async function prepareScanFile(file, policy, { rotation = 0, highDetail = false } = {}) {
  const kind = scanKind(file)
  if (!file.size || !kind) throw new Error('请选择有内容的 PDF 或合同照片。')
  if (kind === 'pdf') {
    if (file.size > policy.maxBytes) throw new Error('PDF 超出本次上传体积上限。')
    return { blob: file, name: file.name, format: 'pdf', size: file.size, optimized: false }
  }
  if (file.size > 80 * 1024 * 1024) throw new Error('该照片超出手机可优化的体积，请选择普通分辨率版本。')
  const image = await decodePhoto(file)
  const canvas = document.createElement('canvas')
  try {
    const sourceWidth = image.naturalWidth || image.width
    const sourceHeight = image.naturalHeight || image.height
    const { width, height } = scanDimensions(sourceWidth, sourceHeight, highDetail ? 6500 : 3000, policy.maxPhotoPixels)
    const upright = rotation % 180 === 0
    canvas.width = upright ? width : height
    canvas.height = upright ? height : width
    const context = canvas.getContext('2d', { alpha: false })
    if (!context) throw new Error('当前浏览器无法优化图片，请尝试使用系统浏览器。')
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.translate(canvas.width / 2, canvas.height / 2)
    context.rotate(rotation * Math.PI / 180)
    context.imageSmoothingQuality = 'high'
    context.drawImage(image, -width / 2, -height / 2, width, height)

    const isPng = file.type === 'image/png' || /\.png$/i.test(file.name)
    let blob = await encode(canvas, isPng ? 'image/png' : 'image/jpeg', highDetail ? 0.96 : 0.92)
    const softBytes = Math.min(policy.maxPhotoBytes, 2 * 1024 * 1024)
    if ((!highDetail && blob.size > softBytes) || blob.size > policy.maxPhotoBytes) {
      const compact = await encode(canvas, 'image/jpeg', highDetail ? 0.94 : 0.88)
      if (compact.size < blob.size) blob = compact
    }
    if (blob.size > policy.maxPhotoBytes) throw new Error('照片优化后仍超出上限，请从相册选取普通分辨率版本。')
    const format = blob.type === 'image/png' ? 'png' : 'jpg'
    return { blob, format, name: `${file.name.replace(/\.[^.]+$/, '')}.${format}`, size: blob.size, optimized: true }
  } finally {
    image.close?.()
    canvas.width = canvas.height = 0
  }
}

export function scanSelectionIssue(files, policy) {
  if (files.length > policy.maxPhotos) return `一次最多上传 ${policy.maxPhotos} 张照片。`
  if (files.length > 1 && files.some(file => (file.format || scanKind(file)) === 'pdf')) return '请选择一份 PDF 或一组照片。'
  if (files.every(file => !!file.format) && files.reduce((total, file) => total + file.size, 0) > policy.maxBytes) {
    return '优化后的文件总大小超出限制，请减少本次选择的照片。'
  }
  return ''
}

export async function scanFileSpec({ blob, name, format, size, optimized }, chunkBytes, signal) {
  const spec = { name, format, size, optimized }
  if (!globalThis.crypto?.subtle) return spec
  spec.chunkHashes = []
  for (let offset = 0; offset < size; offset += chunkBytes) {
    signal?.throwIfAborted()
    const bytes = await blob.slice(offset, offset + chunkBytes).arrayBuffer()
    const digest = await crypto.subtle.digest('SHA-256', bytes)
    spec.chunkHashes.push(Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join(''))
  }
  return spec
}

export async function uploadScanFile(file, chunkBytes, received, sendChunk, progress, signal) {
  if (file.size === received) return
  // Recheck the latest persisted chunk on resume. This also makes a lost acknowledgement safe to retry.
  let offset = received > 0 ? Math.floor((received - 1) / chunkBytes) * chunkBytes : 0
  while (offset < file.size) {
    signal?.throwIfAborted()
    const end = Math.min(file.size, offset + chunkBytes)
    const body = file.slice(offset, end, 'application/octet-stream')
    let saved
    for (let attempt = 0; attempt < 3; attempt++) {
      try { saved = await sendChunk(offset, body); break }
      catch (error) {
        signal?.throwIfAborted()
        if ((error.status >= 400 && error.status < 500) || attempt === 2) throw error
        await new Promise(resolve => setTimeout(resolve, 300 * (attempt + 1)))
      }
    }
    if (!Number.isSafeInteger(saved?.received) || saved.received < end || saved.received > file.size) {
      throw new Error('服务器返回的上传进度异常，请刷新后重试。')
    }
    offset = saved.received
    progress(offset)
  }
}
