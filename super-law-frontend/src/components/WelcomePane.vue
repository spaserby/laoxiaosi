<script setup>
/**
 * 欢迎态（V9 公报问答台皮肤）：头版导语（首字下沉）+ 事实栏计数动画 + 案由双栏 + 我的能力三栏
 * 杂志主题词已法律化：Lead Story → 开栏陈词；本期主题 → 执业领域；技术附记 → 我的能力
 * 点击案由直接作为用户消息发送（chat store）
 */
import { computed, nextTick, onMounted, ref } from 'vue'
import { useChatStore } from '../stores/chat'
import { fetchHomeStats } from '../api/session'
import { ArrowRight, Search, Layers, ShieldCheck } from 'lucide-vue-next'

const store = useChatStore()

const items = [
  { no: '01', title: '离职补偿', abs: '经济补偿 N / N+1 / 违法解除 2N 的适用情形、年限折算与工资基数口径，仲裁时效提示。', ref: '依据 · 劳动合同法 第47、87条', q: '被公司辞退，离职补偿怎么算？' },
  { no: '02', title: '欠款起诉', abs: '借条与转账证据、三年诉讼时效、支付令与立案流程。', ref: '依据 · 民法典 第675条', q: '朋友欠钱不还，该怎么起诉？' },
  { no: '03', title: '离婚财产', abs: '共同财产认定、房产分割与过错方少分规则。', ref: '依据 · 民法典 第1087条', q: '离婚时房产和存款如何分割？' },
  { no: '04', title: '工伤认定', abs: '认定条件、30 日申报时限、劳动能力鉴定与工伤保险待遇的完整链路。', ref: '依据 · 工伤保险条例 第14、17条', q: '上班途中受伤，工伤认定流程是什么？' },
  { no: '05', title: '押金返还', abs: '返还义务、扣款的举证责任、催告留痕与起诉路径。', ref: '依据 · 民法典 第733条', q: '租房押金不退，我该怎么办？' },
  { no: '06', title: '竞业限制', abs: '适用人群、二年上限、按月补偿与解除条件。', ref: '依据 · 劳动合同法 第23、24条', q: '公司让我签竞业协议，不签可以吗？' }
]

const facets = [
  { rn: 'I.', title: '检索打底', icon: Search, p: '先取回法条与判例，再组织语言。检索为空时不做无据推断，而是提示你补充事实与证据。' },
  { rn: 'II.', title: '混合精排', icon: Layers, p: '向量召回与关键词召回双路执行，交由精排模型统一排序，避免「像但不对」的条文混入引用。' },
  { rn: 'III.', title: '安全兜底', icon: ShieldCheck, p: '规则层先行拦截越界诉求与高风险指令：拒答、紧急警示与免责声明全部由确定性规则执行，安全决策从不交给模型判断。' }
]

/* 事实栏 */
const nf = new Intl.NumberFormat('zh-CN')
const stats = ref({ articleCount: 0, lawCount: 0, reviewTotal: 0, reviewPassRate: -1 })
const rateText = computed(() =>
  stats.value.reviewPassRate < 0 ? '积累中' : stats.value.reviewPassRate + '%')
/** 统计已加载（控制"xx 部，xx 条"与占位符 — 的切换） */
const loaded = computed(() => stats.value.articleCount > 0)
const poolRef = ref(null)

function roll(el, target) {
  let start = null
  function run(ts) {
    if (!start) start = ts
    const t = Math.min(1, (ts - start) / 1400), e = 1 - Math.pow(1 - t, 3)
    el.textContent = target > 1000 ? nf.format(Math.floor(target * e)) : Math.floor(target * e)
    if (t < 1) requestAnimationFrame(run)
  }
  setTimeout(() => requestAnimationFrame(run), 420)
}

onMounted(async () => {
  try {
    const s = await fetchHomeStats()
    // 防御：代理缺失/异常时可能返回 HTML 字符串，避免 NaN/undefined 上屏
    if (s && typeof s === 'object' && 'articleCount' in s) {
      stats.value = s
      await nextTick()   // 等 v-if 渲染出数字节点再启动滚动动画
      if (poolRef.value) roll(poolRef.value, s.articleCount)
    }
  } catch { /* 统计失败时事实栏保持占位，不阻断首页 */ }
})
</script>

<template>
  <div class="welcome">
    <!-- 头版导语 -->
    <section class="lead">
      <div class="lead-kicker">开栏陈词</div>
      <h2>你好，我是<em>劳小司</em>：<br>一位较真的AI法律助手</h2>
      <p class="lead-body">我读的是现行有效的法条与司法解释，而不是互联网上的转述。每当你提出一个具体处境，我先把它拆成法律要件，再取回对应条文，最后把结论、依据与不确定之处一并写给你——像一份写得克制的备忘录，而不是一段热情但含糊的安慰。</p>
      <p class="lead-body">如果检索结果不足以支撑结论，我会说明缺什么材料、下一步该补什么证据；如果问题涉及刑事、诉讼时效临界或人身安全，我会建议你尽快联系执业律师与相关机构。这不是推诿，而是我对边界的基本尊重。</p>
      <div class="byline">
        <div class="by-item"><span>执业领域</span><b>劳动 · 婚姻 · 合同 · 侵权</b></div>
        <div class="by-item hi by-stat">
          <span>现行法条：</span>
          <b v-if="loaded">
            <span class="stat-num">{{ stats.lawCount }}</span><em>部</em>
            <i class="sep">·</i>
            <span class="stat-num" ref="poolRef">0</span><em>条</em>
          </b>
          <b v-else><span class="stat-num">—</span></b>
        </div>
        <div class="by-item hi"><span>回答审校通过率</span><b>{{ rateText }}</b></div>
      </div>
    </section>

    <!-- 常见案由：报刊双栏 -->
    <section class="columns">
      <div class="col-head"><h3>常见案由</h3><em>快速咨询通道</em></div>
      <div class="q-columns">
        <button v-for="it in items" :key="it.no" class="q-item" @click="store.send(it.q)">
          <span class="q-num">{{ it.no }}</span>
          <span>
            <span class="q-headline">{{ it.title }}<ArrowRight :size="13" /></span>
            <span class="q-abstract">{{ it.abs }}</span>
            <span class="q-ref">{{ it.ref }}</span>
          </span>
        </button>
      </div>
    </section>

    <!-- 能力三栏（无标题，细分隔线维持报刊节奏） -->
    <section class="facets">
      <div class="facets-rule"></div>
      <div class="facet-grid">
        <div v-for="f in facets" :key="f.rn" class="facet">
          <span class="rn">{{ f.rn }}</span>
          <h4><component :is="f.icon" :size="15" />{{ f.title }}</h4>
          <p>{{ f.p }}</p>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.lead { padding: 40px 0 30px; border-bottom: 1px solid var(--line); }
.lead-kicker {
  display: flex; align-items: center; gap: 12px;
  font-family: var(--mono); font-size: 10.5px; letter-spacing: .3em;
  color: var(--acc); text-transform: uppercase;
}
.lead-kicker::after { content: ""; flex: 1; height: 1px; background: linear-gradient(90deg, var(--acc), transparent); }
.lead h2 { margin-top: 20px; font-family: var(--serif); font-weight: 900; font-size: clamp(30px, 4vw, 46px); line-height: 1.32; letter-spacing: .01em; }
.lead h2 em { font-family: var(--iserif); font-style: italic; font-weight: 400; color: var(--acc); }
.lead-body { margin-top: 20px; font-family: var(--serif); font-size: 14.5px; line-height: 2.1; color: var(--soft); text-align: justify; }
.lead-body:first-of-type::first-letter {
  float: left; font-family: var(--serif); font-weight: 900; font-size: 54px;
  line-height: .86; padding: 6px 12px 0 0; color: var(--ink);
}
.byline { margin-top: 28px; border-top: 1px solid var(--line-ink); display: grid; grid-template-columns: repeat(3, 1fr); }
.by-item { padding: 14px 18px 6px; border-left: 1px solid var(--line); }
.by-item:first-child { border-left: none; padding-left: 0; }
.by-item span { display: block; font-family: var(--sans); font-size: 10.5px; letter-spacing: .2em; color: var(--faint); }
.by-item b { display: block; margin-top: 7px; font-family: var(--itight); font-weight: 700; font-size: 17px; letter-spacing: -.01em; font-variant-numeric: tabular-nums; }
.by-item.hi b { color: var(--acc); }
/* 法条数据栏——衬线大数字 + 黑体小单位单行排布；
   旧版数字 span 被 .by-item span 的 block 小字样式命中导致三行碎排，
   此处用更高优先级选择器复位并升级视觉 */
.by-stat b { display: flex; align-items: baseline; gap: 4px; white-space: nowrap; }
.by-stat b span.stat-num {
  display: inline; font-family: var(--serif); font-weight: 900; font-size: 23px;
  line-height: 1; letter-spacing: 0; color: var(--acc); font-variant-numeric: tabular-nums;
}
.by-stat b em { font-style: normal; font-family: var(--sans); font-size: 11px; color: var(--soft); }
.by-stat b i.sep { font-style: normal; color: var(--line); margin: 0 2px; }

.columns { padding: 34px 0 6px; }
.col-head { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; border-bottom: 2px solid var(--line-ink); padding-bottom: 10px; }
.col-head h3 { font-family: var(--serif); font-weight: 900; font-size: 23px; }
.col-head em { font-family: var(--mono); font-style: normal; font-size: 10px; letter-spacing: .22em; color: var(--faint); text-transform: uppercase; }
.q-columns { display: grid; grid-template-columns: 1fr 1fr; column-gap: 34px; }
.q-item {
  display: flex; gap: 14px; padding: 19px 4px; border-bottom: 1px solid var(--line);
  width: 100%; text-align: left; font-family: var(--serif); position: relative;
  transition: background .3s, padding .4s var(--spring);
}
.q-item:nth-child(odd) { border-right: 1px solid var(--line); padding-right: 22px; }
.q-item:hover { background: var(--acc-soft); padding-left: 14px; }
.q-num { font-family: var(--iserif); font-style: italic; font-size: 25px; color: var(--acc); line-height: 1.1; flex-shrink: 0; }
.q-headline { font-weight: 900; font-size: 18px; letter-spacing: .02em; display: flex; align-items: center; gap: 9px; transition: color .3s; }
.q-item:hover .q-headline { color: var(--acc); }
.q-headline :deep(svg) { color: var(--acc); opacity: 0; transform: translateX(-5px); transition: all .35s var(--spring); }
.q-item:hover .q-headline :deep(svg) { opacity: 1; transform: none; }
.q-abstract { display: block; font-family: var(--sans); font-size: 12.5px; color: var(--soft); line-height: 1.8; margin-top: 7px; }
.q-ref { display: block; font-family: var(--mono); font-size: 10px; color: var(--faint); letter-spacing: .1em; margin-top: 9px; }

.facets { padding: 34px 0 0; }
.facets-rule { border-top: 1px solid var(--line-ink); }
.facet-grid { display: grid; grid-template-columns: repeat(3, 1fr); }
.facet { padding: 22px 24px 6px; border-left: 1px solid var(--line); }
.facet:first-child { border-left: none; padding-left: 0; }
.facet .rn { font-family: var(--iserif); font-style: italic; font-size: 13px; color: var(--acc); }
.facet h4 { margin-top: 10px; font-size: 15.5px; font-weight: 600; display: flex; align-items: center; gap: 9px; }
.facet h4 :deep(svg) { color: var(--acc); }
.facet p { margin-top: 11px; font-size: 12.5px; line-height: 2; color: var(--soft); font-family: var(--sans); }

@media (max-width: 1180px) {
  .facet-grid { grid-template-columns: 1fr; }
  .facet { border-left: none; padding-left: 0; border-top: 1px solid var(--line); }
  .facet:first-child { border-top: none; }
}
@media (max-width: 860px) {
  .q-columns { grid-template-columns: 1fr; }
  .q-item:nth-child(odd) { border-right: none; padding-right: 4px; }
  .byline { grid-template-columns: 1fr; }
  .by-item { border-left: none; padding-left: 0; }
}
</style>
