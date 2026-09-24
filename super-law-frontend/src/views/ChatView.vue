<script setup>
/**
 * 智能问答页（V9 公报问答台皮肤）：白纸正刊 sheet（裁切标记 + 报头 + 竖排法谚）
 * 欢迎态 = 接待大厅（头版）；对话态 = 法律咨询（Consultation）
 * 杂志主题词已全部法律化：Law Review / Legal Desk / 法律咨询 / Powered by
 */
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { FileText, MessageSquare } from 'lucide-vue-next'
import WelcomePane from '../components/WelcomePane.vue'
import MessageList from '../components/MessageList.vue'
import BlankPane from '../components/BlankPane.vue'
import ChatInput from '../components/ChatInput.vue'

const store = useChatStore()
const userStore = useUserStore()
const router = useRouter()

const inChat = computed(() => store.currentMessages.length > 0 || store.isStreaming)
/** 新建咨询干净页：blankMode 且尚无消息 */
const showBlank = computed(() => store.blankMode && !inChat.value)

// 配额拒绝引导——游客强引导登录，登录用户告知明日重置；弹后刷新余量
watch(() => store.quotaDeny, async (q) => {
  if (!q) return
  store.quotaDeny = null
  userStore.loadQuota()
  const isGuest = q.tier === 'guest'
  try {
    await ElMessageBox.alert(q.message, '额度提示', {
      confirmButtonText: isGuest ? '立即登录' : '知道了',
      type: 'warning'
    })
    if (isGuest) router.push('/auth')
  } catch { /* 用户关闭弹窗 */ }
})

/** 报头日期：2026 年 9 月 17 日 · 星期四 */
const today = (() => {
  const d = new Date(), week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 · 星期${week}`
})()
</script>

<template>
  <div class="chat-page">
    <!-- 新建咨询干净页：无报纸容器，居中问候 + 居中输入框 -->
    <BlankPane v-if="showBlank" />
    <template v-else>
      <div class="page-scroll">
        <div class="sheet">
        <!-- 裁切标记 -->
        <span class="crop tl"></span><span class="crop tr"></span>
        <span class="crop bl"></span><span class="crop br"></span>
        <span class="vertical" aria-hidden="true">法律是最高理性</span>

        <!-- 报头 -->
        <header class="runhead">
          <div class="rh-top">
            <span>法律工作台</span>
            <span>{{ today }}</span>
            <span>在线</span>
          </div>
          <div class="rh-title">
            <span class="rh-name"><b>劳小司</b></span>
            <span class="rh-mode">
              <component :is="inChat ? MessageSquare : FileText" :size="13" />
              {{ inChat ? '法律咨询' : '咨询接待' }}
            </span>
          </div>
        </header>

        <!-- 欢迎态（接待大厅）/ 咨询记录 -->
        <WelcomePane v-if="!inChat" />
        <MessageList v-else />

        <!-- 页码 + 版权行 -->
        <div class="pageno">— {{ inChat ? 2 : 1 }} —</div>
        <footer class="colophon">
          <span>劳小司·AI法律助手</span>
        </footer>
        </div>
      </div>

      <!-- 投递口（fixed dock） -->
      <ChatInput />
    </template>
  </div>
</template>

<style scoped>
.chat-page { flex: 1; display: flex; flex-direction: column; min-height: 0; position: relative; }
/* 底部留白 = 固定投递口占位（dock+免责声明≈110px）+ 呼吸位 */
.page-scroll { flex: 1; overflow-y: auto; min-height: 0; padding: 26px 32px 150px; scroll-behavior: smooth; }

.sheet {
  position: relative; max-width: var(--sheet); margin: 0 auto;
  background: var(--paper); border: 1px solid var(--line);
  box-shadow: 0 1px 2px rgba(22, 25, 31, .05), 0 30px 70px rgba(22, 25, 31, .09);
  padding: 0 66px 66px;
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

.vertical {
  position: absolute; right: -2px; top: 210px; writing-mode: vertical-rl;
  font-size: 12.5px; font-weight: 600; letter-spacing: .55em; color: var(--faint); user-select: none;
}

.runhead { border-bottom: 3px double var(--line-ink); padding: 24px 0 12px; }
.rh-top {
  display: flex; justify-content: space-between; gap: 14px;
  font-family: var(--mono); font-size: 10px; letter-spacing: .18em;
  color: var(--faint);
}
.rh-title { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-top: 14px; }
.rh-name { display: flex; align-items: baseline; gap: 14px; }
.rh-name b { font-family: var(--serif); font-weight: 900; font-size: clamp(28px, 3.6vw, 40px); letter-spacing: .06em; line-height: 1; }
.rh-name em { font-family: var(--iserif); font-style: italic; font-size: clamp(13px, 1.5vw, 17px); color: var(--acc); }
.rh-mode {
  font-family: var(--sans); font-size: 12px; font-weight: 700; color: var(--acc);
  display: flex; align-items: center; gap: 7px; padding-bottom: 4px; white-space: nowrap;
}

.pageno {
  margin-top: 34px; padding-top: 14px; border-top: 1px solid var(--line);
  text-align: center; font-family: var(--mono); font-size: 10px; letter-spacing: .4em; color: var(--faint);
}
.colophon {
  margin-top: 34px; border-top: 3px double var(--line-ink); padding-top: 13px;
  display: flex; justify-content: center;
  font-family: var(--mono); font-size: 9.5px; letter-spacing: .2em; color: var(--faint);
}

@media (max-width: 1180px) {
  .sheet { padding: 0 34px 46px; }
  .crop { display: none; }
}
@media (max-width: 860px) {
  .page-scroll { padding: 14px 0 160px; }
  .sheet { border-left: none; border-right: none; }
  .vertical { display: none; }
  .rh-top span:nth-child(2) { display: none; }
}
</style>
