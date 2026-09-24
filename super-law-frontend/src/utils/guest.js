/**
 * 匿名访问凭据（P0 安全整改配套）
 *
 * <p>会话归属从"谁都能读"改为"按主人过滤"后，匿名访客也需要一个属于自己的 key：
 * 生成一次存 localStorage，随请求头 X-Guest-Key 送给后端。换浏览器/清缓存即换身份，
 * 这符合匿名历史的预期语义（不登录就没有跨设备历史）。
 */

const KEY = 'sl_guest_key'

/** 生成随机串：优先 crypto.getRandomValues（http 站点也能用），退化到时间戳+随机 */
function randomKey() {
  try {
    const bytes = new Uint8Array(16)
    crypto.getRandomValues(bytes)
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  } catch {
    return 'g' + Date.now().toString(36) + Math.random().toString(36).slice(2, 12)
  }
}

/** 取（必要时生成并持久化）匿名凭据 */
export function getGuestKey() {
  let key = localStorage.getItem(KEY)
  if (!key || key.length < 8) {
    key = randomKey()
    localStorage.setItem(KEY, key)
  }
  return key
}

/**
 * 会话/聊天类请求的公共头：
 * Authorization（登录态）+ X-Guest-Key（匿名态）——后端按二者之一决定会话归属。
 */
export function sessionHeaders() {
  const headers = { 'X-Guest-Key': getGuestKey() }
  const token = localStorage.getItem('sl_token')
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}
