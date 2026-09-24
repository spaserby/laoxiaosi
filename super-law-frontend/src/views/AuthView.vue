<script setup>
/**
 * 登录/注册/重置密码页（V9 公报问答台皮肤）
 * - 注册必须手机验证码（验真手机号，保证重置通道可用）
 * - 开发模式（sms-mock-echo=true）验证码直接回显在页面上，无需看后端日志
 * 视觉：白纸 sheet + 报头 + 下划线三态 tab + 细线输入框 + 墨黑宽按钮（与正刊同源）
 */
import { ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import { sendSmsCode, resetPassword } from '../api/auth'
import { Scale } from 'lucide-vue-next'

const router = useRouter()
const userStore = useUserStore()

const mode = ref('login')
const loading = ref(false)
const sending = ref(false)
const devCode = ref('')
const countdown = ref(0)
let timer = null

const loginForm = ref({ username: '', password: '' })
const regForm = ref({ username: '', password: '', phone: '', code: '' })
const resetForm = ref({ phone: '', code: '', newPassword: '' })

/** 报头日期 */
const today = (() => {
  const d = new Date(), week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 · 星期${week}`
})()

function startCountdown() {
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function sendCode(phone) {
  if (!/^1\d{10}$/.test(phone || '')) {
    ElMessage.warning('请先输入正确的手机号')
    return
  }
  sending.value = true
  devCode.value = ''
  try {
    const r = await sendSmsCode(phone)
    if (r.devCode) {
      devCode.value = r.devCode
      ElMessage.success('开发模式：验证码已回显在表单下方')
    } else {
      ElMessage.success('验证码已发送')
    }
    startCountdown()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    sending.value = false
  }
}

async function doLogin() {
  loading.value = true
  try {
    await userStore.login(loginForm.value)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function doRegister() {
  loading.value = true
  try {
    await userStore.register(regForm.value)
    ElMessage.success('注册成功，已自动登录')
    router.push('/')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function doReset() {
  loading.value = true
  try {
    await resetPassword(resetForm.value)
    ElMessage.success('密码已重置，请用新密码登录')
    mode.value = 'login'
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onUnmounted(() => timer && clearInterval(timer))
</script>

<template>
  <div class="auth-page">
    <div class="auth-sheet">
      <span class="crop tl"></span><span class="crop tr"></span>
      <span class="crop bl"></span><span class="crop br"></span>

      <!-- 报头：仅保留日期 -->
      <header class="runhead">
        <div class="rh-top"><span>{{ today }}</span></div>
        <div class="rh-title">
          <span class="rh-name"><b>账户服务</b></span>
        </div>
      </header>

      <!-- 品牌行 -->
      <div class="auth-brand">
        <span class="bmark"><Scale :size="18" /></span>
        <div class="ab-text">
          <b>劳小司</b>
          <span>登录后同步咨询记录与专业身份（律师 / 管理员）</span>
        </div>
      </div>

      <!-- 三态 tab：下划线报刊式 -->
      <div class="auth-tabs" role="tablist">
        <button role="tab" :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button role="tab" :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</button>
        <button role="tab" :class="{ on: mode === 'reset' }" @click="mode = 'reset'">重置密码</button>
      </div>

      <!-- 登录 -->
      <form v-if="mode === 'login'" class="auth-form" @submit.prevent="doLogin">
        <div class="field">
          <label for="lg-u">用户名</label>
          <input id="lg-u" v-model="loginForm.username" placeholder="请输入用户名" autocomplete="username">
        </div>
        <div class="field">
          <label for="lg-p">密码</label>
          <input id="lg-p" v-model="loginForm.password" type="password" placeholder="请输入密码" autocomplete="current-password">
        </div>
        <button type="submit" class="submit-btn" :disabled="loading">登 录</button>
        <div class="auth-links">
          <button type="button" class="lk" @click="mode = 'register'">没有账号？去注册</button>
          <button type="button" class="lk faint" @click="mode = 'reset'">忘记密码</button>
        </div>
      </form>

      <!-- 注册 -->
      <form v-else-if="mode === 'register'" class="auth-form" @submit.prevent="doRegister">
        <div class="field">
          <label for="rg-u">用户名</label>
          <input id="rg-u" v-model="regForm.username" placeholder="4~32 位字母、数字或下划线" autocomplete="username">
        </div>
        <div class="field">
          <label for="rg-p">密码</label>
          <input id="rg-p" v-model="regForm.password" type="password" placeholder="8~32 位，需同时含字母和数字" autocomplete="new-password">
        </div>
        <div class="field">
          <label for="rg-ph">手机号</label>
          <input id="rg-ph" v-model="regForm.phone" placeholder="用于找回密码，需短信验证" maxlength="11">
        </div>
        <div class="field">
          <label for="rg-c">短信验证码</label>
          <div class="sms-row">
            <input id="rg-c" v-model="regForm.code" placeholder="请输入验证码" maxlength="6">
            <button type="button" class="code-btn" :disabled="countdown > 0 || sending" @click="sendCode(regForm.phone)">
              {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
            </button>
          </div>
          <p v-if="devCode" class="dev-code">DEV CODE — {{ devCode }}（未接真实短信）</p>
        </div>
        <button type="submit" class="submit-btn" :disabled="loading">注册并登录</button>
      </form>

      <!-- 重置密码 -->
      <form v-else class="auth-form" @submit.prevent="doReset">
        <div class="field">
          <label for="rs-ph">注册手机号</label>
          <input id="rs-ph" v-model="resetForm.phone" placeholder="请输入注册时的手机号" maxlength="11">
        </div>
        <div class="field">
          <label for="rs-c">短信验证码</label>
          <div class="sms-row">
            <input id="rs-c" v-model="resetForm.code" placeholder="请输入验证码" maxlength="6">
            <button type="button" class="code-btn" :disabled="countdown > 0 || sending" @click="sendCode(resetForm.phone)">
              {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
            </button>
          </div>
          <p v-if="devCode" class="dev-code">DEV CODE — {{ devCode }}（未接真实短信）</p>
        </div>
        <div class="field">
          <label for="rs-p">新密码</label>
          <input id="rs-p" v-model="resetForm.newPassword" type="password" placeholder="8~32 位，需同时含字母和数字" autocomplete="new-password">
        </div>
        <button type="submit" class="submit-btn" :disabled="loading">重置密码</button>
        <div class="auth-links">
          <button type="button" class="lk" @click="mode = 'login'">返回登录</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  flex: 1; overflow-y: auto; min-height: 0;
  display: flex; align-items: flex-start; justify-content: center;
  padding: 40px 32px 60px;
}

.auth-sheet {
  position: relative; width: 100%; max-width: 520px;
  background: var(--paper); border: 1px solid var(--line);
  box-shadow: 0 1px 2px rgba(22, 25, 31, .05), 0 30px 70px rgba(22, 25, 31, .09);
  padding: 0 46px 40px;
  animation: sheetIn .9s var(--soft-ease) both;
}
@keyframes sheetIn { from { opacity: 0; transform: translateY(26px); } to { opacity: 1; transform: none; } }

.crop { position: absolute; width: 26px; height: 26px; pointer-events: none; opacity: .5; }
.crop::before, .crop::after { content: ""; position: absolute; background: rgba(22, 25, 31, .34); }
.crop::before { width: 26px; height: 1px; top: 0; left: 0; }
.crop::after { width: 1px; height: 26px; top: 0; left: 0; }
.crop.tl { left: -34px; top: -34px; }
.crop.tr { right: -34px; top: -34px; transform: scaleX(-1); }
.crop.bl { left: -34px; bottom: -34px; transform: scaleY(-1); }
.crop.br { right: -34px; bottom: -34px; transform: scale(-1); }

.runhead { border-bottom: 3px double var(--line-ink); padding: 24px 0 12px; }
.rh-top {
  display: flex; justify-content: center;
  font-family: var(--mono); font-size: 10px; letter-spacing: .18em;
  color: var(--faint);
}
.rh-title { margin-top: 12px; }
.rh-name b { font-family: var(--serif); font-weight: 900; font-size: 30px; letter-spacing: .06em; line-height: 1; }

.auth-brand { display: flex; align-items: center; gap: 12px; margin-top: 26px; }
.bmark {
  width: 38px; height: 38px; flex-shrink: 0; border: 1.5px solid var(--line-ink);
  display: grid; place-items: center; position: relative; background: var(--paper);
}
.bmark :deep(svg) { color: var(--acc); }
.bmark::after { content: ""; position: absolute; right: -3px; bottom: -3px; width: 8px; height: 8px; background: var(--acc); }
.ab-text b { display: block; font-family: var(--serif); font-weight: 900; font-size: 15px; letter-spacing: .06em; }
.ab-text span { display: block; margin-top: 3px; font-family: var(--sans); font-size: 11px; color: var(--faint); }

.auth-tabs { display: grid; grid-template-columns: repeat(3, 1fr); margin-top: 26px; border-bottom: 1px solid var(--line-ink); }
.auth-tabs button {
  padding: 11px 4px 12px; font-family: var(--serif); font-size: 14.5px; font-weight: 600;
  color: var(--faint); border-bottom: 2px solid transparent; margin-bottom: -1px;
  transition: color .3s, border-color .3s;
}
.auth-tabs button:hover { color: var(--ink); }
.auth-tabs button.on { color: var(--ink); font-weight: 900; border-bottom-color: var(--ink); }

.auth-form { padding-top: 24px; }
.field { margin-bottom: 18px; }
.field label {
  display: block; font-family: var(--sans); font-size: 10.5px; font-weight: 700;
  letter-spacing: .2em; color: var(--faint); margin-bottom: 8px;
}
.field input {
  width: 100%; border: 1px solid var(--line-strong); background: #fff;
  padding: 11px 12px; font-family: var(--sans); font-size: 13.5px; color: var(--ink);
  outline: none; transition: border-color .3s, box-shadow .3s;
}
.field input::placeholder { color: var(--faint); }
.field input:focus { border-color: var(--acc); box-shadow: 0 0 0 3px rgba(30, 58, 138, .08); }

.sms-row { display: flex; gap: 8px; }
.sms-row input { flex: 1; }
.code-btn {
  flex-shrink: 0; border: 1px solid var(--line-ink); background: #fff;
  padding: 0 14px; font-family: var(--sans); font-size: 12px; font-weight: 700; color: var(--ink);
  transition: all .3s var(--spring); white-space: nowrap;
}
.code-btn:hover:not(:disabled) { background: var(--ink); color: #fff; }
.code-btn:disabled { border-color: var(--line); color: var(--faint); cursor: not-allowed; }

.dev-code {
  margin-top: 8px; font-family: var(--mono); font-size: 10.5px; letter-spacing: .12em;
  color: var(--acc); border: 1px dashed rgba(30, 58, 138, .4); padding: 6px 10px;
  background: var(--acc-soft);
}

.submit-btn {
  width: 100%; margin-top: 6px; padding: 13px;
  border: 1px solid var(--line-ink); background: var(--ink); color: #fff;
  font-family: var(--sans); font-size: 13px; font-weight: 700; letter-spacing: .2em;
  transition: all .3s var(--spring);
}
.submit-btn:hover:not(:disabled) { background: var(--acc); border-color: var(--acc); }
.submit-btn:active:not(:disabled) { transform: scale(.98); }
.submit-btn:disabled { opacity: .5; cursor: not-allowed; }

.auth-links { display: flex; justify-content: space-between; margin-top: 14px; }
.lk {
  font-family: var(--mono); font-size: 10.5px; letter-spacing: .14em; color: var(--acc);
  text-decoration: underline; text-underline-offset: 3px; transition: opacity .2s;
}
.lk.faint { color: var(--faint); }
.lk:hover { opacity: .7; }

.colophon {
  margin-top: 30px; border-top: 3px double var(--line-ink); padding-top: 13px;
  display: flex; justify-content: space-between; gap: 10px; flex-wrap: wrap;
  font-family: var(--mono); font-size: 9px; letter-spacing: .2em; color: var(--faint); text-transform: uppercase;
}

@media (max-width: 860px) {
  .auth-page { padding: 20px 14px 40px; }
  .auth-sheet { padding: 0 24px 30px; }
  .crop { display: none; }
}
</style>
