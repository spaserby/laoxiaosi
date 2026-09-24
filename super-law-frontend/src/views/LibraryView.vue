<script setup>
/**
 * 法条知识库页：三字段联合检索栏 + 分类 chips（带计数角标）+ 法条卡片列表 + 分页
 * 数据源：PostgreSQL 底账 law_article（GET /law-article/page、/categories）
 * 单框关键词检索升级为 法名/条号/正文 三输入 AND 联合检索（空字段不作为约束）
 */
import { onMounted, ref, computed } from 'vue'
import { pageLawArticles, listCategories } from '../api/library'

const lawName = ref('')
const articleNo = ref('')
const keyword = ref('')
const category = ref('')          // '' = 全部
const page = ref(1)
const size = 10
const total = ref(0)
const list = ref([])
const categories = ref([])        // [{category, count}]
const loading = ref(false)

const totalCount = computed(() => categories.value.reduce((s, c) => s + c.count, 0))

/** 分类标签配色：民事蓝 / 劳动绿 / 刑事红 / 其他蓝 */
function catClass(cat) {
  if (cat === '劳动') return 'green'
  if (cat === '刑事') return 'red'
  return ''
}

async function load() {
  loading.value = true
  try {
    const data = await pageLawArticles({
      lawName: lawName.value, articleNo: articleNo.value,
      keyword: keyword.value, category: category.value, page: page.value, size
    })
    total.value = data.total
    list.value = data.list
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function pickCategory(cat) {
  category.value = cat
  search()
}

onMounted(async () => {
  categories.value = await listCategories()
  load()
})
</script>

<template>
  <div class="lib-page">
    <div>
      <div class="page-title">法条知识库</div>
      <div class="page-sub">基于 PostgreSQL 底账 · 法名/条号/正文联合检索 · 共 {{ totalCount }} 条法条</div>
    </div>

    <!-- 联合检索栏：三输入 AND 组合，空字段不作为约束；Enter 任一门触发检索 -->
    <div class="searchbar combo">
      <input v-model="lawName" class="combo-input" placeholder="法律名称，如 劳动合同法"
             aria-label="法律名称" @keyup.enter="search" />
      <span class="combo-sep"></span>
      <input v-model="articleNo" class="combo-input" placeholder="条文编号，如 第四十七条"
             aria-label="条文编号" @keyup.enter="search" />
      <span class="combo-sep"></span>
      <input v-model="keyword" class="combo-input wide" placeholder="正文关键词，如 经济补偿"
             aria-label="正文关键词" @keyup.enter="search" />
      <div class="search-btn" @click="search">
        <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
          <path d="M2 7h9M8 3.5 11.5 7 8 10.5" stroke="#fff" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
    </div>

    <!-- 分类 chips -->
    <div class="chips">
      <div class="chip" :class="{ on: category === '' }" @click="pickCategory('')">
        全部<span class="num">{{ totalCount }}</span>
      </div>
      <div v-for="c in categories" :key="c.category" class="chip"
           :class="{ on: category === c.category }" @click="pickCategory(c.category)">
        {{ c.category }}<span class="num">{{ c.count }}</span>
      </div>
    </div>

    <!-- 法条卡片列表 -->
    <div v-loading="loading" class="art-list">
      <div v-for="a in list" :key="a.id" class="art-card">
        <div class="art-head">
          <span class="art-cat" :class="catClass(a.category)">{{ a.category || '未分类' }}</span>
          <div class="art-law">{{ a.lawName }}</div>
        </div>
        <div class="art-no">{{ a.articleNo }}<template v-if="a.title"> · {{ a.title }}</template></div>
        <div class="art-body">{{ a.content }}</div>
        <div class="art-foot">
          <span class="art-tag">来源：知识库底账</span>
        </div>
      </div>
      <div v-if="!loading && !list.length" class="empty">未检索到相关法条</div>
    </div>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination small background layout="prev, pager, next"
                     :total="total" :page-size="size" v-model:current-page="page"
                     @current-change="load" />
      <span class="pg-info">共 {{ total }} 条 · 每页 {{ size }} 条</span>
    </div>
  </div>
</template>

<style scoped>
/* 布局：页壳不滚动，只有法条列表区滚动；搜索栏/chips 置顶、分页栏钉底 */
.lib-page {
  flex: 1; min-height: 0; overflow: hidden;
  padding: 18px 16px; display: flex; flex-direction: column; gap: 12px;
}
.page-title { font-size: 16px; font-weight: 700; color: var(--sl-ink); font-family: var(--serif); }
.page-sub { font-size: 12px; color: var(--sl-sub); margin-top: 2px; }
.searchbar {
  display: flex; align-items: center; gap: 8px; background: #fff;
  border: 0.5px solid rgba(74, 155, 245, 0.25); border-radius: 24px;
  padding: 6px 6px 6px 14px; box-shadow: 0 6px 16px rgba(74, 155, 245, 0.08);
}
.search-input { flex: 1; border: none; outline: none; font: inherit; font-size: 13.5px; color: var(--sl-text); background: transparent; }
.search-input::placeholder { color: var(--sl-faint); }
/* 分隔线切分三字段，正文框加宽 */
.searchbar.combo { border-radius: 12px; padding: 6px 6px 6px 14px; gap: 10px; }
.combo-input {
  flex: 1; min-width: 0; border: none; outline: none; background: transparent;
  font: inherit; font-size: 13px; color: var(--sl-text);
}
.combo-input.wide { flex: 2; }
.combo-input::placeholder { color: var(--sl-faint); }
.combo-sep { width: 1px; align-self: stretch; background: var(--sl-line); margin: 5px 0; }
.search-btn {
  width: 30px; height: 30px; border-radius: 50%; background: var(--sl-grad);
  display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0;
}
.chips { display: flex; gap: 8px; flex-wrap: wrap; }
.chip {
  font-size: 13px; font-weight: 500; background: var(--sl-surface); color: var(--sl-accent);
  border-radius: 3px; padding: 6px 14px; cursor: pointer; user-select: none;
  border: 1px solid transparent; transition: all .25s var(--spring);
}
.chip:hover { border-color: var(--sl-accent); }
.chip.on { background: var(--ink); color: #fff; }
.chip .num { font-size: 11px; margin-left: 4px; opacity: .8; font-family: var(--mono); }
.art-list {
  flex: 1; min-height: 0; overflow-y: auto;
  display: flex; flex-direction: column; gap: 10px;
}
.art-card {
  background: #fff; border: 0.5px solid var(--sl-card-line); border-radius: 14px;
  padding: 12px 14px; display: flex; flex-direction: column; gap: 6px;
}
.art-head { display: flex; align-items: center; gap: 10px; }
.art-cat { font-size: 11px; background: var(--sl-surface); color: var(--sl-accent); border-radius: 3px; padding: 2px 8px; }
.art-cat.red { background: #FCEBEB; color: #E24B4A; }
.art-cat.green { background: #E6F6EE; color: #0F6E56; }
/* 法条名：衬线加粗加深，卡片视觉锚点 */
.art-law { font-size: 14.5px; font-weight: 700; color: var(--sl-ink); font-family: var(--serif); }
.art-no { font-size: 12.5px; color: var(--sl-sub); }
/* 条文正文：调大调深，阅读主体 */
.art-body { font-size: 14px; color: var(--sl-text); line-height: 1.9; text-align: justify; }
.art-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 4px; }
.art-tag { font-size: 11px; color: var(--sl-faint); }
.empty { text-align: center; font-size: 12px; color: var(--sl-faint); padding: 32px 0; }
.pager {
  flex-shrink: 0; display: flex; align-items: center; justify-content: center; gap: 10px;
  padding-top: 10px; border-top: 0.5px solid var(--sl-line); background: var(--sl-canvas);
}
.pg-info { font-size: 12px; color: var(--sl-faint); }
</style>
