<script setup>
/**
 * 左栏（V9 公报问答台皮肤）：品牌 + 新建咨询 + 功能导航 + 咨询历史 + 账户卡
 * 逻辑与 chat store 会话操作 + user store 登录态；折叠态用 body.rail
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Scale, ChevronLeft, Plus, MessageSquare, Calculator, Library, Clock, User, Settings } from 'lucide-vue-next'
import { syncVectors, syncStatus, syncScope } from '../api/auth'
import { useDrawer } from '../composables/useDrawer'

const store = useChatStore()
const userStore = useUserStore()
const router = useRouter()
const route = useRoute()

/** 导航动作后收起抽屉 */
const { closeDrawer } = useDrawer()

/* 登录用户存服务端（跨设备永久）；游客存 localStorage（guest 键）；
   交互：点缩略图 → 放大预览原图 → “确认选择”才落选择 */
const GUEST_AVATAR_KEY = 'sl_avatar_guest'
const AVATARS = Array.from({ length: 12 }, (_, i) => String(i + 1))
const thumbUrl = (n) => `/avatars/${n}.jpg`
const guestAvatar = ref(localStorage.getItem(GUEST_AVATAR_KEY) || '')
/** 当前头像编号：登录态以服务端为源，游客读本地 */
const myAvatar = computed(() =>
  userStore.user ? (userStore.user.avatar || '') : guestAvatar.value)
const avatarUrl = computed(() => myAvatar.value ? thumbUrl(myAvatar.value) : '')
const previewAvatar = ref('')
const showPreview = ref(false)
function openPreview(n) {
  previewAvatar.value = n
  showPreview.value = true
}
async function confirmAvatar() {
  const n = previewAvatar.value
  try {
    if (userStore.user) {
      await userStore.saveAvatar(n)          // 服务端永久保存（编号 1-12，空串=默认）
    } else {
      guestAvatar.value = n
      n ? localStorage.setItem(GUEST_AVATAR_KEY, n) : localStorage.removeItem(GUEST_AVATAR_KEY)
    }
    showPreview.value = false
    ElMessage.success('头像已更新')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '头像保存失败')
  }
}
/* 游客转正：登录后服务端无头像而本地有游客选择 → 自动同步上去（老选择不白选） */
watch(() => userStore.user, async (u) => {
  if (u && !u.avatar && guestAvatar.value) {
    try { await userStore.saveAvatar(guestAvatar.value) } catch { /* 静默，不阻断 */ }
  }
})

onMounted(() => {
  store.loadSessions()
  userStore.loadQuota()
})

const navItems = [
  { name: 'chat', label: '首页', path: '/', icon: MessageSquare },
  { name: 'tools', label: '法律工具箱', path: '/tools', icon: Calculator },
  { name: 'library', label: '法条知识库', path: '/library', icon: Library }
]

function toggleRail() {
  document.body.classList.toggle('rail')
}

function go(item) {
  closeDrawer()
  // 点"首页"：断开当前会话 + 清除干净页态——首页永远是报纸首页，与历史会话无关联
  // （消息缓存保留，点历史会话可回到该对话）
  if (item.name === 'chat') {
    store.blankMode = false
    store.currentSessionId = null
    nextTick(() => {
      const sc = document.querySelector('.page-scroll')
      if (sc) sc.scrollTop = 0
    })
  }
  router.push(item.path)
}

/** 新建咨询：清空当前会话进入空白新对话（回顶部 + 聚焦输入框，强化"新对话"体感） */
function newChat() {
  closeDrawer()
  store.newSession()
  router.push('/')
  nextTick(() => {
    const sc = document.querySelector('.page-scroll')
    if (sc) sc.scrollTop = 0
    const input = document.getElementById('input')
    if (input) input.focus()
  })
}

/** 点击历史会话：切换会话并跳回问答页（在知识库/工具箱页点击也能回到对话） */
function openSession(sessionId) {
  closeDrawer()
  store.switchSession(sessionId)
  router.push('/')
}

/** 去登录页（移动端抽屉先收起） */
function goAuth() {
  closeDrawer()
  router.push('/auth')
}

/** 会话时间短格式：今天显示 HH:mm，其余显示 MM-DD */
function fmtTime(s) {
  const t = s.updatedAt || s.createdAt
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
  }
  return (d.getMonth() + 1) + '-' + String(d.getDate()).padStart(2, '0')
}

// 登录态：角色中文映射
const roleLabel = computed(() => {
  const role = userStore.user?.role
  return role === 'ADMIN' ? '管理员' : role === 'LAWYER' ? '执业律师' : '标准用户'
})

// 账户卡副标题含剩余配额（透明展示）
const accountSub = computed(() => {
  const q = userStore.quota
  if (!userStore.user) {
    return q && q.remaining >= 0 ? `今日剩余 ${q.remaining} 次 · 登录解锁更多` : '游客咨询次数有限 · 登录解锁更多'
  }
  if (!q) return roleLabel.value
  return q.dailyLimit < 0 ? `${roleLabel.value} · 不限额度` : `${roleLabel.value} · 今日剩余 ${q.remaining} 次`
})

/* ---------- 设置对话框 ---------- */
const showSettings = ref(false)

/** 律师模式开关：绑定 role，切换走 handleLawyerToggle */
const lawyerOn = computed({
  get: () => userStore.user?.role === 'LAWYER',
  set: (v) => handleLawyerToggle(v)
})

async function handleLawyerToggle(enabled) {
  if (enabled) {
    // 开启前置免责声明确认（大厂标准：身份确认 + 范围说明 + 责任归属三段式）
    try {
      await ElMessageBox.confirm(
        '律师模式当前处于内测阶段，开启前请知悉：\n\n' +
        '· 身份确认：请确认您已持有有效律师执业证，本模式仅面向执业律师开放；\n' +
        '· 范围扩展：咨询范围扩展至诉讼策略、条款审查、证据组织等专业场景，回答以专业同行视角呈现；\n' +
        '· 责任归属：AI 生成内容仅供参考，不构成正式法律意见，据此作出的判断与使用后果由您自行承担。',
        '开启律师模式？',
        {
          confirmButtonText: '确认开启',
          cancelButtonText: '再想想',
          type: 'warning',
          customStyle: { whiteSpace: 'pre-line' }
        }
      )
    } catch {
      return // 取消：开关保持关（computed get 自动回弹）
    }
  }
  try {
    await userStore.setLawyerMode(enabled)
    ElMessage.success(enabled ? '律师模式已开启' : '律师模式已关闭')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '操作失败')
  }
}

function logout() {
  userStore.logout()
  showSettings.value = false
  ElMessage.success('已退出登录')
}

/* ---------- 向量库手动同步（仅 ADMIN） ---------- */
const syncing = ref(false)
const syncLabel = ref('可全量，或按法条勾选增量同步')

/* 勾选清单（法名 → 条数 / 已向量化条数） */
const showScope = ref(false)
const scope = ref({ rows: [], laws: 0, articles: 0, synced: 0 })
const scopeTableRef = ref(null)
const selectedLaws = ref([])
const allSelected = ref(false)

async function refreshSyncStatus() {
  try {
    const s = await syncStatus()
    syncLabel.value = s.running ? '同步执行中…'
      : `${s.lastResult}${s.lastSyncAt ? ' · ' + new Date(s.lastSyncAt).toLocaleString() : ''}`
    return s
  } catch {
    syncLabel.value = '状态查询失败（仅管理员）'
    return null
  }
}

async function openScope() {
  showScope.value = true
  allSelected.value = false
  selectedLaws.value = []
  try {
    scope.value = await syncScope()
  } catch (e) {
    ElMessage.error(e.response?.status === 403 ? '需要管理员权限' : '法条清单加载失败')
  }
}

function onScopeSelect(rows) {
  selectedLaws.value = rows.map((r) => r.lawName)
}

function toggleAll() {
  allSelected.value = !allSelected.value
  scope.value.rows.forEach((r) => scopeTableRef.value?.toggleRowSelection(r, allSelected.value))
}

/** laws 为空 = 全量同步；传入法名数组 = 只同步选中的法律 */
async function onSync(laws) {
  syncing.value = true
  try {
    const r = await syncVectors(laws)
    if (!r.started) {
      ElMessage.warning(r.reason || '同步任务正在执行中')
      syncing.value = false
      return
    }
    showScope.value = false
    ElMessage.success(`同步任务已启动（${laws?.length ? laws.length + ' 部法律' : '全量'}），完成后自动刷新状态`)
    syncLabel.value = '同步执行中…'
    const timer = setInterval(async () => {
      const s = await refreshSyncStatus()
      if (s && !s.running) {
        clearInterval(timer)
        syncing.value = false
        ElMessage.info('同步结束：' + s.lastResult)
      }
    }, 2000)
  } catch (e) {
    syncing.value = false
    ElMessage.error(e.response?.status === 403 ? '需要管理员权限' : (e.message || '触发同步失败'))
  }
}

watch(showSettings, (v) => {
  if (v && userStore.user?.role === 'ADMIN') refreshSyncStatus()
})

async function onDelete(sessionId, title) {
  try {
    await ElMessageBox.confirm(`删除咨询「${title}」？删除后不可恢复`, '删除咨询', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    await store.removeSession(sessionId)
  } catch { /* 取消 */ }
}
</script>

<template>
  <aside class="sidebar side" aria-label="主导航">
    <!-- 品牌 -->
    <div class="brand">
      <span class="bmark"><Scale :size="18" /></span>
      <span class="btext"><b>劳小司</b><span>LAOXIAOSI</span></span>
      <button class="fold" aria-label="折叠侧栏" @click="toggleRail"><ChevronLeft :size="15" /></button>
    </div>

    <!-- 新建咨询 -->
    <button class="newbtn" @click="newChat"><Plus :size="15" /><span class="newtext">新建咨询</span></button>

    <!-- 功能导航 -->
    <div class="nav-label">功能</div>
    <nav class="wsn" aria-label="功能导航">
      <button v-for="item in navItems" :key="item.name"
              :class="{ on: route.name === item.name }" @click="go(item)">
        <component :is="item.icon" :size="16" />
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <!-- 咨询历史 -->
    <div class="nav-label">最近咨询</div>
    <div class="hist">
      <button v-for="s in store.sessions" :key="s.sessionId"
              :class="{ on: s.sessionId === store.currentSessionId }"
              @click="openSession(s.sessionId)">
        <Clock :size="13" />
        <span>{{ s.title }}</span>
        <em>{{ fmtTime(s) }}</em>
        <i class="del" title="删除咨询" @click.stop="onDelete(s.sessionId, s.title)">×</i>
      </button>
      <div v-if="!store.sessions.length" class="hist-empty">暂无咨询记录</div>
    </div>

    <!-- 账户区：未登录=权益提示卡；已登录=账户卡+设置按钮 -->
    <div v-if="userStore.user" class="account-row">
      <button class="account" title="账户设置" @click="showSettings = true">
        <span class="av"><img v-if="avatarUrl" :src="avatarUrl" alt="头像" class="av-img" /><User v-else :size="15" /></span>
        <span class="at">
          <b>{{ userStore.user.username || userStore.user.phone }}</b>
          <span>{{ accountSub }}</span>
        </span>
      </button>
      <button class="gear-btn" aria-label="设置" title="设置" @click="showSettings = true">
        <Settings :size="15" />
      </button>
    </div>
    <!-- 未登录：同 account-row 包裹（flex:1 只在行内生效，避免被侧栏纵容器拉成巨卡） -->
    <div v-else class="account-row">
      <button class="account" title="点击登录 / 注册" @click="goAuth">
        <span class="av"><img v-if="avatarUrl" :src="avatarUrl" alt="头像" class="av-img" /><User v-else :size="15" /></span>
        <span class="at">
          <b>未登录</b>
          <span>{{ accountSub }}</span>
        </span>
      </button>
    </div>

    <!-- 设置对话框：律师模式开关 + 退出登录 -->
    <el-dialog v-model="showSettings" title="设置" width="400px" append-to-body>
      <!-- 头像选择（12 张静态图 + 恢复默认） -->
      <div class="set-section">
        <div class="set-row">
          <div class="set-txt">
            <b>头像</b>
            <span>点击头像放大预览，确认后保存（登录用户永久保存，跨设备生效）</span>
          </div>
        </div>
        <div class="avatar-grid">
          <button v-for="n in AVATARS" :key="n" type="button" class="avatar-opt"
                  :class="{ on: myAvatar === n }" @click="openPreview(n)">
            <img :src="thumbUrl(n)" alt="头像候选" />
          </button>
          <button type="button" class="avatar-opt default" :class="{ on: !myAvatar }"
                  title="恢复默认图标" @click="openPreview('')">
            <User :size="16" />
          </button>
        </div>
      </div>
      <div class="set-section">
        <div class="set-row">
          <div class="set-txt">
            <b>律师模式</b>
            <span>扩展咨询范围 · 仅面向执业律师（内测）</span>
          </div>
          <el-switch v-model="lawyerOn" :disabled="userStore.user?.role === 'ADMIN'" />
        </div>
        <p v-if="userStore.user?.role === 'ADMIN'" class="set-hint">管理员账号不参与律师模式</p>
        <!-- 向量库手动同步（仅 ADMIN 可见；启动自动同步已关闭） -->
        <div v-if="userStore.user?.role === 'ADMIN'" class="set-row sync-row">
          <div class="set-txt">
            <b>向量库同步</b>
            <span>{{ syncLabel }}</span>
          </div>
          <div class="sync-btns">
            <el-button size="small" @click="openScope">按法条勾选…</el-button>
            <el-button size="small" type="primary" :loading="syncing" @click="onSync()">全量同步</el-button>
          </div>
        </div>
      </div>
      <div class="set-footer">
        <el-button text type="danger" @click="logout">退出登录</el-button>
      </div>
    </el-dialog>

    <!-- 向量库同步 · 按法条勾选（只同步选中的法律，未勾选法条的向量不受影响） -->
    <el-dialog v-model="showScope" title="向量库同步 · 按法条勾选" width="760px" append-to-body>
      <div class="scope-bar">
        <span>共 {{ scope.laws }} 部法律 · {{ scope.articles }} 条，已向量化 {{ scope.synced }} 条</span>
        <el-button text size="small" @click="toggleAll">{{ allSelected ? '取消全选' : '全选' }}</el-button>
      </div>
      <el-table ref="scopeTableRef" :data="scope.rows" height="360" size="small" @selection-change="onScopeSelect">
        <el-table-column type="selection" width="42" />
        <el-table-column prop="lawName" label="法律名称" min-width="280" show-overflow-tooltip />
        <el-table-column prop="docType" label="位阶" width="90" />
        <el-table-column prop="category" label="领域" width="80" />
        <el-table-column label="已同步 / 总数" width="120">
          <template #default="{ row }">{{ row.synced }} / {{ row.cnt }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="showScope = false">取消</el-button>
        <el-button type="primary" :loading="syncing" :disabled="!selectedLaws.length" @click="onSync(selectedLaws)">
          同步选中 {{ selectedLaws.length }} 部
        </el-button>
      </template>
    </el-dialog>

    <!-- 头像预览确认（点缩略图先放大原图，确认后才落选择） -->
    <el-dialog v-model="showPreview" title="头像预览" width="320px" append-to-body>
      <div class="avatar-preview">
        <img v-if="previewAvatar" :src="thumbUrl(previewAvatar)" alt="头像预览" />
        <div v-else class="avatar-preview-default"><User :size="40" /></div>
      </div>
      <template #footer>
        <el-button @click="showPreview = false">取消</el-button>
        <el-button type="primary" @click="confirmAvatar">确认选择</el-button>
      </template>
    </el-dialog>
  </aside>
</template>

<style scoped>
.side {
  position: fixed; left: 0; top: 0; bottom: 0; width: var(--sw); z-index: 30;
  background: rgba(255, 255, 255, .94);
  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
  border-right: 1px solid var(--line);
  display: flex; flex-direction: column; padding: 20px 16px 16px;
  transition: width .5s var(--spring);
}
body.rail .side { width: var(--sw-rail); padding: 20px 10px 16px; }

.brand { display: flex; align-items: center; gap: 11px; padding: 2px 4px 0; }
.bmark {
  width: 38px; height: 38px; flex-shrink: 0; border: 1.5px solid var(--line-ink);
  display: grid; place-items: center; position: relative; background: var(--paper);
}
.bmark :deep(svg) { color: var(--acc); }
.bmark::after { content: ""; position: absolute; right: -3px; bottom: -3px; width: 8px; height: 8px; background: var(--acc); }
.btext { display: flex; flex-direction: column; line-height: 1.22; white-space: nowrap; transition: opacity .25s; }
.btext b { font-size: 16px; font-weight: 900; letter-spacing: .06em; }
.btext span { font-family: var(--itight); font-size: 9.5px; font-weight: 600; letter-spacing: .3em; color: var(--faint); }
body.rail .btext { opacity: 0; pointer-events: none; }
.fold {
  margin-left: auto; width: 28px; height: 28px; border: 1px solid var(--line);
  display: grid; place-items: center; color: var(--soft); flex-shrink: 0;
  transition: all .35s var(--spring);
}
.fold:hover { border-color: var(--line-ink); color: var(--ink); }
.fold :deep(svg) { transition: transform .5s var(--spring); }
body.rail .fold { margin: 0 auto; }
body.rail .fold :deep(svg) { transform: rotate(180deg); }

.newbtn {
  margin-top: 18px; display: flex; align-items: center; justify-content: center; gap: 9px;
  border: 1px solid var(--line-ink); background: var(--ink); color: #fff;
  padding: 12px; font-family: var(--sans); font-size: 13px; font-weight: 700; white-space: nowrap;
  transition: all .3s var(--spring);
}
.newbtn:hover { background: var(--acc); border-color: var(--acc); }
body.rail .newtext { display: none; }

.nav-label {
  margin: 22px 6px 8px; font-family: var(--mono); font-size: 9.5px;
  letter-spacing: .26em; color: var(--faint); text-transform: uppercase; white-space: nowrap;
}
body.rail .nav-label { opacity: 0; }

.wsn { display: flex; flex-direction: column; gap: 3px; }
.wsn button {
  display: flex; align-items: center; gap: 12px; padding: 11px 12px;
  font-family: var(--sans); font-size: 13.5px; color: var(--soft); white-space: nowrap;
  border-left: 2px solid transparent; transition: all .3s var(--spring);
}
.wsn button:hover { color: var(--ink); background: var(--acc-soft); transform: translateX(3px); }
.wsn button.on { color: var(--acc); font-weight: 700; border-left-color: var(--acc); background: var(--acc-soft); }
body.rail .wsn button { justify-content: center; padding: 12px 0; }
body.rail .wsn button span { display: none; }

.hist { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; min-height: 60px; margin-top: 4px; }
.hist button {
  display: flex; align-items: center; gap: 9px; padding: 11px 12px;
  font-family: var(--sans); font-size: 12.5px; color: var(--soft); text-align: left;
  white-space: nowrap; border-left: 2px solid transparent; transition: all .28s var(--spring);
  position: relative;
}
.hist button.on { color: var(--acc); border-left-color: var(--acc); background: var(--acc-soft); }
.hist button :deep(svg) { flex-shrink: 0; opacity: .65; }
.hist button span { overflow: hidden; text-overflow: ellipsis; flex: 1; }
.hist button em { font-family: var(--mono); font-style: normal; font-size: 9.5px; color: var(--faint); }
.hist button:hover { color: var(--ink); background: rgba(22, 25, 31, .04); transform: translateX(3px); }
.hist .del {
  font-style: normal; color: var(--faint); opacity: 0; padding: 0 2px; font-size: 14px;
  transition: opacity .2s, color .2s; position: absolute; right: 8px;
}
.hist button:hover .del { opacity: 1; }
.hist .del:hover { color: #B4432C; }
.hist button:hover em { opacity: 0; }
.hist-empty { font-family: var(--sans); font-size: 12px; color: var(--faint); padding: 10px 12px; }
body.rail .hist { display: none; }

.account {
  margin-top: 12px; display: flex; align-items: center; gap: 11px;
  border: 1px solid var(--line); padding: 12px; white-space: nowrap;
  transition: all .3s var(--spring); flex: 1; min-width: 0;
}
.account:hover { border-color: var(--line-ink); transform: translateY(-2px); }
.account-row { display: flex; gap: 6px; align-items: stretch; }
.account-row .account { margin-top: 12px; }
.gear-btn {
  margin-top: 12px; width: 42px; flex-shrink: 0; border: 1px solid var(--line);
  display: grid; place-items: center; color: var(--soft);
  transition: all .3s var(--spring);
}
.gear-btn:hover { border-color: var(--line-ink); color: var(--ink); transform: translateY(-2px); }
.account .av {
  width: 32px; height: 32px; border-radius: 50%; flex-shrink: 0;
  background: var(--acc-soft); color: var(--acc); display: grid; place-items: center;
  border: 1px solid rgba(30, 58, 138, .22);
}
.account .at { text-align: left; }
.account .at b { display: block; font-family: var(--sans); font-size: 12.5px; font-weight: 700; }
.account .at span { font-family: var(--sans); font-size: 11px; color: var(--acc); }
body.rail .account { justify-content: center; }
body.rail .account .at { display: none; }
body.rail .gear-btn { display: none; }

/* 设置对话框内容 */
.set-section { padding: 4px 0 12px; border-bottom: 1px solid var(--line); }
.set-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.set-txt b { display: block; font-size: 13.5px; font-weight: 700; color: var(--ink); }
.set-txt span { display: block; margin-top: 4px; font-size: 11.5px; color: var(--faint); }
.set-hint { margin-top: 8px; font-size: 11px; color: var(--faint); }
.sync-row { margin-top: 16px; }
.sync-row .sync-btns { display: flex; gap: 6px; flex-shrink: 0; }
/* 勾选同步对话框 */
.scope-bar {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 8px; font-size: 12px; color: var(--faint);
}
.set-footer { display: flex; justify-content: flex-end; padding-top: 10px; }

/* 头像选择 */
.avatar-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; margin-top: 10px; }
.avatar-opt {
  aspect-ratio: 1; border: 1.5px solid var(--line); border-radius: 8px;
  overflow: hidden; padding: 0; background: var(--sl-surface);
  display: grid; place-items: center; color: var(--soft);
}
.avatar-opt img { width: 100%; height: 100%; object-fit: cover; display: block; }
.avatar-opt.on { border-color: var(--acc); box-shadow: 0 0 0 2px var(--acc-soft); }
.account .av { overflow: hidden; }
.av-img { width: 100%; height: 100%; object-fit: cover; display: block; }

/* 头像预览确认 */
.avatar-preview { display: grid; place-items: center; padding: 8px 0; }
.avatar-preview img {
  width: min(240px, 60vw); aspect-ratio: 1; object-fit: cover;
  border: 1px solid var(--line-ink);
}
.avatar-preview-default {
  width: 160px; height: 160px; display: grid; place-items: center;
  border: 1px solid var(--line); color: var(--soft); background: var(--sl-surface);
}
</style>
