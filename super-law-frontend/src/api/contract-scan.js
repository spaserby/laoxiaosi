import http from './auth'

const root = '/tools/contract-scans'
const jobs = `${root}/jobs`
const job = id => `${jobs}/${encodeURIComponent(id)}`
const page = (id, number) => `${job(id)}/pages/${number}`
const data = request => request.then(response => response.data)

export const scanPolicy = signal => data(http.get(`${root}/policy`, { signal }))
export const scanJobs = signal => data(http.get(jobs, { signal }))
export const scanJob = (id, signal) => data(http.get(job(id), { signal }))
export const createScan = (form, signal) => data(http.post(jobs, form, { signal }))
export const uploadScanChunk = (id, asset, offset, bytes, signal) => data(http.put(`${job(id)}/uploads/${asset}`, bytes, {
  signal, params: { offset }, headers: { 'Content-Type': 'application/octet-stream' }, timeout: 60000
}))
export const startScan = (id, revision, signal) => data(http.post(`${job(id)}/start`, { revision }, { signal }))
export const prepareReplacement = (id, number, file, revision, signal) =>
  data(http.post(`${page(id, number)}/replacement`, { file, revision }, { signal }))
export const retryScanPage = (id, number, revision, signal) => data(http.post(`${page(id, number)}/retry`, { revision }, { signal }))
export const deleteScan = (id, signal) => http.delete(job(id), { signal })
export const scanPreview = (id, number, signal) => data(http.get(`${page(id, number)}/preview`, { signal, responseType: 'blob', timeout: 45000 }))
export const downloadScanFile = (id, asset, signal) => data(http.get(`${job(id)}/files/${asset}`, { signal, responseType: 'blob', timeout: 120000 }))
