import axios from 'axios'

const http = axios.create({ timeout: 15000 })

// 四个法律计算器（与后端 LegalTools 共用 CalculatorService 计算逻辑）
export const calcCompensation = (body) => http.post('/tools/compensation', body).then(r => r.data)
export const calcOvertime = (body) => http.post('/tools/overtime', body).then(r => r.data)
export const calcStatuteLimit = (body) => http.post('/tools/statute-limit', body).then(r => r.data)
export const calcCourtFee = (body) => http.post('/tools/court-fee', body).then(r => r.data)

// 劳动法专精工具集
export const calcSocialBackpay = (body) => http.post('/tools/social-insurance-backpay', body).then(r => r.data)
export const calcFundBackpay = (body) => http.post('/tools/fund-backpay', body).then(r => r.data)
export const calcArbitrationLimitation = (body) => http.post('/tools/arbitration-limitation', body).then(r => r.data)
export const calcDoubleWage = (body) => http.post('/tools/double-wage', body).then(r => r.data)
export const calcProbation = (body) => http.post('/tools/probation-check', body).then(r => r.data)
export const calcAnnualLeave = (body) => http.post('/tools/annual-leave', body).then(r => r.data)
export const calcUnemployment = (body) => http.post('/tools/unemployment-months', body).then(r => r.data)
export const calcWorkInjury = (body) => http.post('/tools/work-injury-grant', body).then(r => r.data)
