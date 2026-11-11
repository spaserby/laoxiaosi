import { defineStore } from 'pinia'
import { ref, onScopeDispose } from 'vue'
import * as authApi from '../api/auth'
import { useChatStore } from './chat'

/**
 * 用户登录态 store
 *
 * 设计：
 *  - token 持久化 localStorage（sl_token），刷新页面登录态不丢
 *  - 聊天匿名可用、登录增强：user store 只影响顶栏展示与 SSE token 透传，不做路由拦截
 *  - loadMe 失败（token 过期/无效）自动登出，静默降级为匿名
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('sl_token') || '')
  const user = ref(null)
  const quota = ref(null)   // {tier,limit,period,used,remaining,resetAtEpochMs}（period: daily|monthly）
  let epoch = 0
  let authAttempt = 0

  /** 拉取配额状态（静默失败，不阻断 UI） */
  async function loadQuota() {
    const requestEpoch = epoch
    try {
      const result = await authApi.fetchQuota()
      if (requestEpoch === epoch) quota.value = result
    } catch {
      if (requestEpoch === epoch) quota.value = null
    }
  }

  function setSession(t, u) {
    useChatStore().resetIdentity()
    epoch++
    token.value = t
    user.value = u
    localStorage.setItem('sl_token', t)
    useChatStore().loadSessions()
  }

  async function login(form) {
    const attempt = ++authAttempt
    const r = await authApi.login(form)
    if (attempt !== authAttempt) return
    setSession(r.token, r.user)
    loadQuota()   // 登录态切换后刷新配额（guest→user 档）
  }

  async function register(form) {
    const attempt = ++authAttempt
    const r = await authApi.register(form)
    if (attempt !== authAttempt) return
    setSession(r.token, r.user)
    loadQuota()
  }

  /** 启动时恢复登录态：token 有效则拉用户信息，无效自动登出 */
  async function loadMe() {
    if (!token.value) {
      return
    }
    const requestEpoch = epoch
    try {
      const result = await authApi.fetchMe()
      if (requestEpoch !== epoch) return
      user.value = result
      loadQuota()
    } catch (error) {
      if (requestEpoch === epoch && (error.status === 401 || error.status === 403)) logout()
    }
  }

  /** 律师模式开关：后端持久化 role，返回新用户信息 */
  async function setLawyerMode(enabled) {
    const requestEpoch = epoch
    const u = await authApi.setLawyerMode(enabled)
    if (requestEpoch === epoch) user.value = u
    return u
  }

  /** 头像保存：登录用户服务端永久化，返回并同步最新用户信息 */
  async function saveAvatar(avatar) {
    const requestEpoch = epoch
    const dto = await authApi.setAvatar(avatar)
    if (requestEpoch === epoch) user.value = dto
    return dto
  }

  function logout() {
    authAttempt++
    epoch++
    useChatStore().resetIdentity()
    token.value = ''
    user.value = null
    localStorage.removeItem('sl_token')
    useChatStore().loadSessions()
    loadQuota()   // 回游客档配额展示
  }

  if (typeof window !== 'undefined') {
    const syncIdentity = event => {
      if (event.key !== 'sl_token' && event.key !== null) return
      const current = localStorage.getItem('sl_token') || ''
      if (current === token.value) return
      authAttempt++; epoch++
      useChatStore().resetIdentity()
      token.value = current; user.value = null; quota.value = null
      useChatStore().loadSessions()
      if (current) loadMe()
      else loadQuota()
    }
    window.addEventListener('storage', syncIdentity)
    onScopeDispose(() => window.removeEventListener('storage', syncIdentity))
  }

  return { token, user, quota, login, register, loadMe, logout, setLawyerMode, saveAvatar, loadQuota }
})
