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
    const e = new Error(msg)
    // ★ 保留 HTTP 状态码：调用方需要区分「登录过期 401 / 无权限 403 / 服务端 500 /
    //   纯网络错误(0)」——此前 Error 只带 message，调用方只能笼统提示"加载失败"，
    //   线上排障时看不出到底是哪一种（本次勾选清单报错即踩到）
    e.status = err.response?.status ?? 0
    return Promise.reject(e)
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
// 管理端向量库同步（ADMIN only，403 由调用方提示）
// laws 为空/缺省 = 全量同步；传入法名数组 = 只同步勾选的法律（未勾选的法条向量不受影响）
export const syncVectors = (laws) => http.post('/admin/sync-vectors', laws?.length ? { laws } : {}).then((r) => r.data)
export const syncStatus = () => http.get('/admin/sync-status').then((r) => r.data)
// 勾选清单：按法名汇总 条数 / 已向量化条数 / 业务领域 / 效力位阶
export const syncScope = () => http.get('/admin/sync-scope').then((r) => r.data)

export default http
