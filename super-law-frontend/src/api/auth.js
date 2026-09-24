import axios from 'axios'

/**
 * 认证 API：带 token 拦截器的独立实例
 * 错误统一转成 Error(message)，组件里 catch 后 ElMessage.error(e.message) 直接展示
 */
const http = axios.create({ timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('sl_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (r) => r,
  (err) => {
    const msg = err.response?.data?.message || err.message || '网络异常，请稍后重试'
    return Promise.reject(new Error(msg))
  }
)

export const register = (data) => http.post('/auth/register', data).then((r) => r.data)
export const login = (data) => http.post('/auth/login', data).then((r) => r.data)
export const sendSmsCode = (phone) => http.post('/auth/sms-code', { phone }).then((r) => r.data)
export const resetPassword = (data) => http.post('/auth/reset-password', data).then((r) => r.data)
export const fetchMe = () => http.get('/auth/me').then((r) => r.data)
export const fetchQuota = () => http.get('/auth/quota').then((r) => r.data)
export const setLawyerMode = (enabled) => http.put('/auth/lawyer-mode', { enabled }).then((r) => r.data)
// 头像选择（登录用户永久保存：编号 1-12 存服务端）
export const setAvatar = (avatar) => http.put('/auth/avatar', { avatar }).then((r) => r.data)
// 管理端向量库手动同步（ADMIN only，403 由调用方提示）
export const syncVectors = () => http.post('/admin/sync-vectors').then((r) => r.data)
export const syncStatus = () => http.get('/admin/sync-status').then((r) => r.data)

export default http
