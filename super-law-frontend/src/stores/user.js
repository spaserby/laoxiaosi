import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as authApi from '../api/auth'

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
  const quota = ref(null)   // {tier,dailyLimit,used,remaining,resetAtEpochMs}

  /** 拉取配额状态（静默失败，不阻断 UI） */
  async function loadQuota() {
    try {
      quota.value = await authApi.fetchQuota()
    } catch {
      quota.value = null
    }
  }

  function setSession(t, u) {
    token.value = t
    user.value = u
    localStorage.setItem('sl_token', t)
  }

  async function login(form) {
    const r = await authApi.login(form)
    setSession(r.token, r.user)
    loadQuota()   // 登录态切换后刷新配额（guest→user 档）
  }

  async function register(form) {
    const r = await authApi.register(form)
    setSession(r.token, r.user)
    loadQuota()
  }

  /** 启动时恢复登录态：token 有效则拉用户信息，无效自动登出 */
  async function loadMe() {
    if (!token.value) {
      return
    }
    try {
      user.value = await authApi.fetchMe()
      loadQuota()
    } catch {
      logout()
    }
  }

  /** 律师模式开关：后端持久化 role，返回新用户信息 */
  async function setLawyerMode(enabled) {
    const u = await authApi.setLawyerMode(enabled)
    user.value = u
    return u
  }

  /** 头像保存：登录用户服务端永久化，返回并同步最新用户信息 */
  async function saveAvatar(avatar) {
    const dto = await authApi.setAvatar(avatar)
    user.value = dto
    return dto
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('sl_token')
    loadQuota()   // 回游客档配额展示
  }

  return { token, user, quota, login, register, loadMe, logout, setLawyerMode, saveAvatar, loadQuota }
})
