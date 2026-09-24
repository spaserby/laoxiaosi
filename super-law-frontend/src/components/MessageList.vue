<script setup>
/**
 * 咨询记录（V9 公报问答台皮肤）：咨询请求（墨块右对齐）/ AI 答复（公报体平铺）
 * 滚动容器是 ChatView 的 .page-scroll（整刊滚动），内容变化自动滚底
 */
import { watch, nextTick, onMounted } from 'vue'
import { FileText } from 'lucide-vue-next'
import { useChatStore } from '../stores/chat'
import AssistantBubble from './AssistantBubble.vue'

const store = useChatStore()
let scroller = null

onMounted(() => {
  scroller = document.querySelector('.page-scroll')
  scrollBottom()
})

watch(
  () => [store.currentMessages.length, store.currentMessages.at(-1)?.content],
  () => nextTick(scrollBottom)
)

function scrollBottom() {
  // 桌面：.page-scroll 是滚动容器；移动端（≤860px）它被内容擑开、实际滚动发生在 document——
  // 不区分则移动端流式回答不跟滚
  if (scroller && scroller.scrollHeight > scroller.clientHeight + 4) {
    scroller.scrollTop = scroller.scrollHeight
  } else {
    window.scrollTo({ top: document.documentElement.scrollHeight })
  }
}
</script>

<template>
  <section class="chat visible" aria-live="polite">
    <div class="chat-label">— 法律咨询 —</div>
    <template v-for="(m, i) in store.currentMessages" :key="i">
      <AssistantBubble v-if="m.role === 'assistant'" :message="m" />
      <div v-else class="msg-user">
        <span class="mu-l">咨询请求</span>
        <!-- 附件卡行（文件名+字数，与输入侧 chips 同源） -->
        <div v-if="m.attachments && m.attachments.length" class="mu-files">
          <span v-for="a in m.attachments" :key="a.fileId" class="mu-file">
            <img v-if="a.url" :src="a.url" class="mu-thumb" alt="附件缩略图" />
            <FileText v-else :size="11" />
            {{ a.name }}<template v-if="a.imageCount"> · {{ a.imageCount }} 图</template><template v-else-if="a.chars"> · {{ a.chars }} 字</template>{{ a.truncated ? '（已截取）' : '' }}
          </span>
        </div>
        {{ m.content }}
      </div>
    </template>
  </section>
</template>

<style scoped>
.chat { padding: 34px 0 0; border-top: 3px double var(--line-ink); margin-top: 36px; }
.chat-label {
  font-family: var(--mono); font-size: 10px; letter-spacing: .32em;
  color: var(--faint); text-transform: uppercase; text-align: center;
}
.msg-user {
  margin: 24px 0 0 auto; max-width: 74%; width: fit-content;
  background: var(--ink); color: #F2F3F5; padding: 14px 20px;
  font-family: var(--sans); font-size: 13.5px; line-height: 1.8;
  white-space: pre-wrap; animation: up .5s var(--soft-ease) both;
}
.mu-l {
  display: block; font-family: var(--mono); font-size: 9.5px; letter-spacing: .24em;
  color: rgba(242, 243, 245, .55); text-transform: uppercase; margin-bottom: 7px;
}
.mu-files { display: flex; flex-direction: column; gap: 4px; margin-bottom: 8px; }
.mu-file {
  display: inline-flex; align-items: center; gap: 5px;
  font-family: var(--mono); font-size: 10.5px;
  border: 1px solid rgba(242, 243, 245, .35); padding: 3px 8px; color: rgba(242, 243, 245, .85);
}
.mu-thumb { width: 22px; height: 22px; object-fit: cover; border: 1px solid rgba(242,243,245,.4); }
@keyframes up { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: none; } }
</style>
