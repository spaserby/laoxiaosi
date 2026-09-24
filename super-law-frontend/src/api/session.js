import axios from 'axios'
import { sessionHeaders } from '../utils/guest'

const http = axios.create({ timeout: 15000 })

// ★ 会话接口必须带凭证（P0 安全整改）：后端按 Authorization（登录态）/ X-Guest-Key（匿名凭据）
//   判定会话归属，不带凭证的请求拿不到任何历史，读取/删除他人会话返回 403。
http.interceptors.request.use((config) => {
  Object.assign(config.headers, sessionHeaders())
  return config
})

// 会话列表（updatedAt 倒序）
export const listSessions = () => http.get('/session/list').then(r => r.data.data)

// 会话历史消息 [{role, content}]
export const getSessionMessages = (sessionId) =>
  http.get(`/session/${encodeURIComponent(sessionId)}/messages`).then(r => r.data.data)

// 删除会话（记忆 + 索引）
export const deleteSession = (sessionId) =>
  http.delete(`/session/${encodeURIComponent(sessionId)}`).then(r => r.data)

// 停止生成
export const stopChat = (sessionId) =>
  http.post('/chat/stop', null, { params: { sessionId } }).then(r => r.data)

// 首页事实栏真实统计（检索池 COUNT + 审校通过率）
export const fetchHomeStats = () => http.get('/stats/home').then(r => r.data)

// 文档上传（会话附件）：multipart → [{fileId,name,chars,truncated}]；大文件解析放宽超时
export const uploadFiles = (formData) =>
  http.post('/chat/files', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000
  }).then(r => r.data)
