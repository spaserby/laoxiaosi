import axios from 'axios'

const http = axios.create({ timeout: 15000 })

// 法条分页搜索：keyword/category 空串不过滤；page 从 1 开始
export const pageLawArticles = (params) =>
  http.get('/law-article/page', { params }).then(r => r.data.data)

// 分类计数 [{category, count}]
export const listCategories = () =>
  http.get('/law-article/categories').then(r => r.data.data)

// 单条法条详情（引用卡按需展开正文/章节属：SSE 引用载荷不带正文时据此补齐）
export const fetchArticleDetail = (id) =>
  http.get('/law-article/detail', { params: { id } }).then(r => r.data.data)
