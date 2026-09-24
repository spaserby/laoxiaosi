import axios from 'axios'

const http = axios.create({ timeout: 15000 })

// 法条分页搜索：keyword/category 空串不过滤；page 从 1 开始
export const pageLawArticles = (params) =>
  http.get('/law-article/page', { params }).then(r => r.data.data)

// 分类计数 [{category, count}]
export const listCategories = () =>
  http.get('/law-article/categories').then(r => r.data.data)
