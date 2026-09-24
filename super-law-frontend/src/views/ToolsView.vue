<script setup>
/**
 * 法律工具箱页：12 个计算器卡片（展开表单 + 结果区）
 * 计算走后端 POST /tools/*（与 LLM 工具 LegalTools 共用 CalculatorService，逻辑单份）
 */
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { calcCompensation, calcOvertime, calcStatuteLimit, calcCourtFee,
  calcSocialBackpay, calcFundBackpay, calcArbitrationLimitation, calcDoubleWage, calcProbation,
  calcAnnualLeave, calcUnemployment, calcWorkInjury } from '../api/tools'

/** 各工具表单与结果状态 */
const forms = reactive({
  compensation: { workYears: 3.5, monthlySalary: 12000, localAvgSalary: null },
  overtime: { baseSalary: 10000, weekdayHours: 10, weekendHours: 8, holidayHours: 0 },
  limitation: { knowDate: '', wageArrears: false, endDate: '' },
  courtFee: { claimAmount: 300000 },
  social: { actualSalary: 20000, contributionBase: 7000, months: 24 },
  fund: { actualSalary: 20000, contributionBase: 7000, months: 24, fundRate: 12 },
  doubleWage: { startDate: '', signDate: '', monthlySalary: 12000 },
  probation: { contractMonths: 36, probationMonths: 6, probationSalary: null, contractSalary: null },
  annualLeave: { totalWorkYears: 8, takenDays: 0, monthlySalary: 12000 },
  unemployment: { contributionYears: 6 },
  workInjury: { disabilityGrade: 10, monthlySalary: 12000 }
})
const results = reactive({ compensation: null, overtime: null, limitation: null, courtFee: null,
  social: null, fund: null, doubleWage: null, probation: null, annualLeave: null, unemployment: null, workInjury: null })
const openCard = ref('compensation')   // 默认展开第一个（对齐设计稿）
const loading = ref(false)

function toggle(key) {
  openCard.value = openCard.value === key ? null : key
}

async function run(key, apiFn) {
  loading.value = true
  try {
    const body = { ...forms[key] }
    // 空值清理：可选字段空串转 null，数字字段转 Number
    Object.keys(body).forEach(k => {
      if (body[k] === '' || body[k] === undefined) body[k] = null
      else if (typeof body[k] === 'string' && !/\d{4}-\d{2}-\d{2}/.test(body[k]) && !isNaN(Number(body[k]))) body[k] = Number(body[k])
    })
    const resp = await apiFn(body)
    if (resp.code === 200) {
      results[key] = resp.data
    } else {
      ElMessage.error(resp.message || '计算失败')
    }
  } catch (e) {
    ElMessage.error('计算请求失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
}

const fmt = v => '¥' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
</script>

<template>
  <div class="tools-page">
    <div class="page-head">
      <div>
        <div class="page-title">法律工具箱</div>
        <div class="page-sub">常用法律计算器，输入参数一键算出结果</div>
      </div>
      <div class="badge">12 个工具</div>
    </div>

    <!-- 经济补偿金 -->
    <div class="tool-card">
      <div class="tool-ic">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M4 1.5h5.2L12.5 4.8V14a.5.5 0 0 1-.5.5H4a.5.5 0 0 1-.5-.5V2a.5.5 0 0 1 .5-.5Z" stroke="#fff" stroke-width="1.2"/><path d="M9 1.8v3.2h3.2" stroke="#fff" stroke-width="1.2" stroke-linejoin="round"/><path d="M5.5 8h5M5.5 10.5h3.4" stroke="#fff" stroke-width="1.2" stroke-linecap="round"/></svg>
      </div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">经济补偿金计算器</div>
            <div class="tool-law">按工作年限折算月数，满一年 1 个月、满半年按 1 年、不满半年 0.5 个月；月工资超当地月均 3 倍按 3 倍封顶</div>
          </div>
          <div class="tag">劳动合同法第47条</div>
        </div>
        <template v-if="openCard === 'compensation'">
          <div class="field-row">
            <div class="field"><div class="field-l">工作年限（年）</div><input v-model="forms.compensation.workYears" class="field-v" /></div>
            <div class="field"><div class="field-l">前 12 个月平均月薪（元）</div><input v-model="forms.compensation.monthlySalary" class="field-v" /></div>
            <div class="field"><div class="field-l">当地月均工资（元，可选）</div><input v-model="forms.compensation.localAvgSalary" placeholder="—" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.compensation">
            <div style="flex:1;">
              <div class="result-l">计算结果</div>
              <div class="result-d">{{ results.compensation.summary }}</div>
            </div>
            <div style="text-align:right;">
              <div class="result-v">{{ fmt(results.compensation.value) }}</div>
              <div class="calc-btn" @click="run('compensation', calcCompensation)">重新计算</div>
            </div>
          </div>
          <div v-else class="calc-btn solo" @click="run('compensation', calcCompensation)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('compensation')">开始计算</div>
      </div>
    </div>

    <!-- 社保补缴 -->
    <div class="tool-card">
      <div class="tool-ic"><svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M8 1.8 13 3.6v4.2c0 3.4-2.2 5.6-5 6.4-2.8-.8-5-3-5-6.4V3.6L8 1.8Z" stroke="#fff" stroke-width="1.2" stroke-linejoin="round"/><path d="M5.6 7.6h4.8M8 5.2v4.8" stroke="#fff" stroke-width="1.2" stroke-linecap="round"/></svg></div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">社保补缴计算</div>
            <div class="tool-law">养老单位 16% / 个人 8%（全国统一费率）；补缴 =（实际工资 − 缴存基数）× 费率 × 欠缴月数；医疗/失业/工伤按北京费率示例估算（各统筹地区制定，详见结果温馨提示）</div>
          </div>
          <div class="tag">社会保险法第60、63条</div>
        </div>
        <template v-if="openCard === 'social'">
          <div class="field-row">
            <div class="field"><div class="field-l">实际月工资（元）</div><input v-model="forms.social.actualSalary" class="field-v" /></div>
            <div class="field"><div class="field-l">公司缴存基数（元）</div><input v-model="forms.social.contributionBase" class="field-v" /></div>
            <div class="field"><div class="field-l">欠缴月数</div><input v-model="forms.social.months" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.social">
            <div style="flex:1;"><div class="result-l">补缴拆分</div><div class="result-d">{{ results.social.summary }}</div></div>
            <div style="text-align:right;">
              <div class="result-v">单位应补 {{ fmt(results.social.companyPart) }}</div>
              <div class="result-v personal">个人应补 {{ fmt(results.social.personalPart) }}</div>
              <div class="calc-btn" @click="run('social', calcSocialBackpay)">重新计算</div>
            </div>
          </div>
          <div v-else class="calc-btn solo" @click="run('social', calcSocialBackpay)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('social')">开始计算</div>
      </div>
    </div>

    <!-- 公积金补缴 -->
    <div class="tool-card">
      <div class="tool-ic"><svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M2.5 7.5 8 2.8l5.5 4.7" stroke="#fff" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M4 6.8V13h8V6.8" stroke="#fff" stroke-width="1.2" stroke-linejoin="round"/><path d="M6.6 13V9.4h2.8V13" stroke="#fff" stroke-width="1.2" stroke-linejoin="round"/></svg></div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">公积金补缴计算</div>
            <div class="tool-law">单位与个人同比例缴存（5%-12%）；两侧补缴额全部进入你的公积金个人账户，相当于双倍入账</div>
          </div>
          <div class="tag">住房公积金管理条例第18、20条</div>
        </div>
        <template v-if="openCard === 'fund'">
          <div class="field-row">
            <div class="field"><div class="field-l">实际月工资（元）</div><input v-model="forms.fund.actualSalary" class="field-v" /></div>
            <div class="field"><div class="field-l">公司缴存基数（元）</div><input v-model="forms.fund.contributionBase" class="field-v" /></div>
            <div class="field"><div class="field-l">欠缴月数</div><input v-model="forms.fund.months" class="field-v" /></div>
            <div class="field"><div class="field-l">缴存比例 %</div><input v-model="forms.fund.fundRate" placeholder="12" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.fund">
            <div style="flex:1;"><div class="result-l">补缴拆分</div><div class="result-d">{{ results.fund.summary }}</div></div>
            <div style="text-align:right;">
              <div class="result-v">单位应补 {{ fmt(results.fund.companyPart) }}</div>
              <div class="result-v personal">个人应补 {{ fmt(results.fund.personalPart) }}</div>
              <div class="calc-btn" @click="run('fund', calcFundBackpay)">重新计算</div>
            </div>
          </div>
          <div v-else class="calc-btn solo" @click="run('fund', calcFundBackpay)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('fund')">开始计算</div>
      </div>
    </div>

    <!-- 加班费 -->
    <div class="tool-card">
      <div class="tool-ic">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6.5" stroke="#fff" stroke-width="1.2"/><path d="M8 5v3.5l2.2 1.3" stroke="#fff" stroke-width="1.2" stroke-linecap="round"/></svg>
      </div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">加班费计算器</div>
            <div class="tool-law">工作日 1.5 倍 / 休息日 2 倍 / 法定节假日 3 倍，时薪 = 月薪 ÷ 21.75 ÷ 8</div>
          </div>
          <div class="tag">劳动法第44条</div>
        </div>
        <template v-if="openCard === 'overtime'">
          <div class="field-row">
            <div class="field"><div class="field-l">月基本工资（元）</div><input v-model="forms.overtime.baseSalary" class="field-v" /></div>
            <div class="field"><div class="field-l">工作日延时（小时）</div><input v-model="forms.overtime.weekdayHours" class="field-v" /></div>
            <div class="field"><div class="field-l">休息日（小时）</div><input v-model="forms.overtime.weekendHours" class="field-v" /></div>
            <div class="field"><div class="field-l">法定节假日（小时）</div><input v-model="forms.overtime.holidayHours" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.overtime">
            <div style="flex:1;">
              <div class="result-l">计算结果</div>
              <div class="result-d">{{ results.overtime.summary }}</div>
            </div>
            <div style="text-align:right;">
              <div class="result-v">{{ fmt(results.overtime.value) }}</div>
              <div class="calc-btn" @click="run('overtime', calcOvertime)">重新计算</div>
            </div>
          </div>
          <div v-else class="calc-btn solo" @click="run('overtime', calcOvertime)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('overtime')">开始计算</div>
      </div>
    </div>

    <!-- 劳动仲裁时效 -->
    <div class="tool-card">
      <div class="tool-ic">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M8 1.8 13 3.6v4.2c0 3.4-2.2 5.6-5 6.4-2.8-.8-5-3-5-6.4V3.6L8 1.8Z" stroke="#fff" stroke-width="1.2" stroke-linejoin="round"/><path d="M6 8.2 7.6 9.8 10.2 6.8" stroke="#fff" stroke-width="1.2" stroke-linecap="round"/></svg>
      </div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">劳动仲裁时效计算</div>
            <div class="tool-law">劳动争议仲裁时效 1 年；拖欠劳动报酬争议存续期间不受限（普通民事纠纷 3 年见民法典 188 条）</div>
          </div>
          <div class="tag">劳动争议调解仲裁法第27条</div>
        </div>
        <template v-if="openCard === 'limitation'">
          <div class="field-row">
            <div class="field"><div class="field-l">知道权利受侵害之日</div><input v-model="forms.limitation.knowDate" placeholder="2025-01-01" class="field-v" /></div>
            <div class="field"><div class="field-l">是否拖欠劳动报酬争议</div>
              <select v-model="forms.limitation.wageArrears" class="field-v">
                <option :value="false">否</option><option :value="true">是</option>
              </select></div>
            <div class="field"><div class="field-l">劳动关系终止日（欠薪必填）</div><input v-model="forms.limitation.endDate" placeholder="存续中留空" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.limitation">
            <div style="flex:1;">
              <div class="result-l">仲裁时效截止</div>
              <div class="result-d">{{ results.limitation.summary }}</div>
            </div>
            <div style="text-align:right;">
              <div class="result-v">{{ results.limitation.value }}</div>
              <div class="calc-btn" @click="run('limitation', calcArbitrationLimitation)">重新计算</div>
            </div>
          </div>
          <div v-else class="calc-btn solo" @click="run('limitation', calcArbitrationLimitation)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('limitation')">开始计算</div>
      </div>
    </div>

    <!-- 案件受理费 -->
    <div class="tool-card">
      <div class="tool-ic">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><rect x="1.5" y="3.2" width="13" height="9.6" rx="2.6" stroke="#fff" stroke-width="1.2"/><path d="M5.6 8a2.4 1.7 0 1 0 4.8 0 2.4 1.7 0 0 0-4.8 0Z" stroke="#fff" stroke-width="1.2"/></svg>
      </div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">案件受理费计算</div>
            <div class="tool-law">财产案件按标的额分段累计；劳动争议案件法院受理费 10 元/件，劳动仲裁免费（调解仲裁法第53条）</div>
          </div>
          <div class="tag">诉讼费用交纳办法第13条</div>
        </div>
        <template v-if="openCard === 'courtFee'">
          <div class="field-row">
            <div class="field"><div class="field-l">诉讼请求标的额（元）</div><input v-model="forms.courtFee.claimAmount" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.courtFee">
            <div style="flex:1;">
              <div class="result-l">计算结果</div>
              <div class="result-d">{{ results.courtFee.summary }}</div>
            </div>
            <div style="text-align:right;">
              <div class="result-v">{{ fmt(results.courtFee.value) }}</div>
              <div class="calc-btn" @click="run('courtFee', calcCourtFee)">重新计算</div>
            </div>
          </div>
          <div v-else class="calc-btn solo" @click="run('courtFee', calcCourtFee)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('courtFee')">开始计算</div>
      </div>
    </div>

    <!-- 劳动法专精工具集 -->
    <div class="tool-card">
      <div class="tool-ic"><svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M4 2h8M4 14h8M6 2v12M10 2v12" stroke="#fff" stroke-width="1.2"/></svg></div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">未签合同二倍工资</div>
            <div class="tool-law">用工满一个月次日起至补签前一日，最长 11 个月</div>
          </div>
          <div class="tag">劳动合同法第82条</div>
        </div>
        <template v-if="openCard === 'doubleWage'">
          <div class="field-row">
            <div class="field"><div class="field-l">入职日期</div><input v-model="forms.doubleWage.startDate" placeholder="2025-03-01" class="field-v" /></div>
            <div class="field"><div class="field-l">补签日期（未签留空）</div><input v-model="forms.doubleWage.signDate" placeholder="—" class="field-v" /></div>
            <div class="field"><div class="field-l">月工资（元）</div><input v-model="forms.doubleWage.monthlySalary" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.doubleWage">
            <div style="flex:1;"><div class="result-l">二倍工资差额</div><div class="result-d">{{ results.doubleWage.summary }}</div></div>
            <div style="text-align:right;"><div class="result-v">{{ fmt(results.doubleWage.value) }}</div>
              <div class="calc-btn" @click="run('doubleWage', calcDoubleWage)">重新计算</div></div>
          </div>
          <div v-else class="calc-btn solo" @click="run('doubleWage', calcDoubleWage)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('doubleWage')">开始计算</div>
      </div>
    </div>

    <div class="tool-card">
      <div class="tool-ic"><svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="#fff" stroke-width="1.2"/><path d="M8 5v3M8 10.5v.5" stroke="#fff" stroke-width="1.2" stroke-linecap="round"/></svg></div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">试用期合规检查</div>
            <div class="tool-law">期限上限按合同档（1/2/6 个月）+ 工资不低于约定 80% + 违法赔偿金</div>
          </div>
          <div class="tag">劳动合同法第19、20、83条</div>
        </div>
        <template v-if="openCard === 'probation'">
          <div class="field-row">
            <div class="field"><div class="field-l">合同期限（月）</div><input v-model="forms.probation.contractMonths" class="field-v" /></div>
            <div class="field"><div class="field-l">约定试用期（月）</div><input v-model="forms.probation.probationMonths" class="field-v" /></div>
            <div class="field"><div class="field-l">试用期工资（可选）</div><input v-model="forms.probation.probationSalary" placeholder="—" class="field-v" /></div>
            <div class="field"><div class="field-l">转正工资（可选）</div><input v-model="forms.probation.contractSalary" placeholder="—" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.probation">
            <div style="flex:1;"><div class="result-l">检查结论：{{ results.probation.value }}</div><div class="result-d">{{ results.probation.summary }}</div></div>
            <div style="text-align:right;"><div class="calc-btn" @click="run('probation', calcProbation)">重新检查</div></div>
          </div>
          <div v-else class="calc-btn solo" @click="run('probation', calcProbation)">开始检查</div>
        </template>
        <div v-else class="open-btn" @click="toggle('probation')">开始检查</div>
      </div>
    </div>

    <div class="tool-card">
      <div class="tool-ic"><svg width="16" height="16" viewBox="0 0 16 16" fill="none"><rect x="2" y="3" width="12" height="11" rx="2" stroke="#fff" stroke-width="1.2"/><path d="M2 6.5h12M5.5 1.5v3M10.5 1.5v3" stroke="#fff" stroke-width="1.2"/></svg></div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">未休年休假补偿</div>
            <div class="tool-law">应休 5/10/15 天档；未休部分日工资 ×300%（含已发 100%，另补 200%）</div>
          </div>
          <div class="tag">带薪年休假条例第3、5条</div>
        </div>
        <template v-if="openCard === 'annualLeave'">
          <div class="field-row">
            <div class="field"><div class="field-l">累计工龄（年）</div><input v-model="forms.annualLeave.totalWorkYears" class="field-v" /></div>
            <div class="field"><div class="field-l">本年已休天数</div><input v-model="forms.annualLeave.takenDays" class="field-v" /></div>
            <div class="field"><div class="field-l">月工资（元）</div><input v-model="forms.annualLeave.monthlySalary" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.annualLeave">
            <div style="flex:1;"><div class="result-l">另补 200% 部分</div><div class="result-d">{{ results.annualLeave.summary }}</div></div>
            <div style="text-align:right;"><div class="result-v">{{ fmt(results.annualLeave.value) }}</div>
              <div class="calc-btn" @click="run('annualLeave', calcAnnualLeave)">重新计算</div></div>
          </div>
          <div v-else class="calc-btn solo" @click="run('annualLeave', calcAnnualLeave)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('annualLeave')">开始计算</div>
      </div>
    </div>

    <div class="tool-card">
      <div class="tool-ic"><svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M3 13V7M8 13V3M13 13V9" stroke="#fff" stroke-width="1.2" stroke-linecap="round"/></svg></div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">失业金领取月数</div>
            <div class="tool-law">缴费 1-5 年→12 个月；5-10 年→18 个月；10 年以上→24 个月；月金额按当地标准</div>
          </div>
          <div class="tag">社会保险法第46条</div>
        </div>
        <template v-if="openCard === 'unemployment'">
          <div class="field-row">
            <div class="field"><div class="field-l">累计缴费年限</div><input v-model="forms.unemployment.contributionYears" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.unemployment">
            <div style="flex:1;"><div class="result-l">最长领取月数</div><div class="result-d">{{ results.unemployment.summary }}</div></div>
            <div style="text-align:right;"><div class="result-v">{{ results.unemployment.value }} 个月</div>
              <div class="calc-btn" @click="run('unemployment', calcUnemployment)">重新计算</div></div>
          </div>
          <div v-else class="calc-btn solo" @click="run('unemployment', calcUnemployment)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('unemployment')">开始计算</div>
      </div>
    </div>

    <div class="tool-card">
      <div class="tool-ic"><svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M8 2v5M8 7l4 6H4l4-6Z" stroke="#fff" stroke-width="1.2" stroke-linejoin="round"/></svg></div>
      <div class="tool-body">
        <div class="tool-title-row">
          <div>
            <div class="tool-name">工伤一次性伤残补助金</div>
            <div class="tool-law">一级 27 个月…十级 7 个月 × 本人工资；医疗/就业补助金按地方标准另计</div>
          </div>
          <div class="tag">工伤保险条例第35-37条</div>
        </div>
        <template v-if="openCard === 'workInjury'">
          <div class="field-row">
            <div class="field"><div class="field-l">伤残等级（1-10）</div><input v-model="forms.workInjury.disabilityGrade" class="field-v" /></div>
            <div class="field"><div class="field-l">本人月工资（元）</div><input v-model="forms.workInjury.monthlySalary" class="field-v" /></div>
          </div>
          <div class="result" v-if="results.workInjury">
            <div style="flex:1;"><div class="result-l">一次性伤残补助金</div><div class="result-d">{{ results.workInjury.summary }}</div></div>
            <div style="text-align:right;"><div class="result-v">{{ fmt(results.workInjury.value) }}</div>
              <div class="calc-btn" @click="run('workInjury', calcWorkInjury)">重新计算</div></div>
          </div>
          <div v-else class="calc-btn solo" @click="run('workInjury', calcWorkInjury)">开始计算</div>
        </template>
        <div v-else class="open-btn" @click="toggle('workInjury')">开始计算</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tools-page { flex: 1; overflow-y: auto; padding: 18px 16px; display: flex; flex-direction: column; gap: 12px; }
.page-head { display: flex; align-items: center; justify-content: space-between; }
.page-title { font-size: 15px; font-weight: 500; color: var(--sl-ink); }
.page-sub { font-size: 11px; color: #8CA0B5; margin-top: 2px; }
.badge { font-size: 11px; background: var(--sl-surface); color: var(--sl-accent); border-radius: 10px; padding: 3px 10px; }
.tool-card {
  background: #fff; border: 0.5px solid var(--sl-card-line); border-radius: 14px;
  padding: 12px 14px; display: flex; gap: 10px;
}
.tool-ic {
  width: 32px; height: 32px; border-radius: 10px; background: var(--sl-grad);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  box-shadow: 0 4px 10px rgba(74,155,245,.25);
}
.tool-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.tool-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.tool-name { font-size: 12.5px; font-weight: 500; color: #2A3B50; }
.tool-law { font-size: 11px; color: #8CA0B5; margin-top: 3px; line-height: 1.5; }
.tag { font-size: 10px; background: var(--sl-surface); color: var(--sl-accent); border-radius: 8px; padding: 2px 8px; flex-shrink: 0; }
.field-row { display: flex; gap: 10px; flex-wrap: wrap; }
.field { flex: 1; min-width: 120px; }
.field-l { font-size: 10.5px; color: #8CA0B5; margin-bottom: 4px; }
.field-v {
  width: 100%; border: 0.5px solid rgba(27,43,63,.12); border-radius: 8px; padding: 6px 9px;
  font: inherit; font-size: 12px; color: var(--sl-text); outline: none; background: #FAFCFF;
}
.field-v:focus { border-color: rgba(74,155,245,.5); }
.result {
  display: flex; align-items: center; gap: 10px; background: #F0F7FF;
  border: 0.5px solid rgba(74,155,245,.18); border-radius: 10px; padding: 9px 12px;
}
.result-l { font-size: 10.5px; color: #8CA0B5; }
.result-d { font-size: 11px; color: var(--sl-text); margin-top: 3px; line-height: 1.5; }
.result-v { font-size: 15px; font-weight: 600; color: var(--sl-accent); }
.result-v.personal { font-size: 12px; font-weight: 500; color: #5A6B80; margin-top: 2px; }
.calc-btn {
  display: inline-block; margin-top: 4px; font-size: 11px; color: var(--sl-accent);
  cursor: pointer; user-select: none;
}
.calc-btn.solo { align-self: flex-start; background: var(--sl-surface); border-radius: 10px; padding: 5px 12px; }
.open-btn {
  align-self: flex-start; font-size: 11px; color: var(--sl-accent); background: var(--sl-surface);
  border-radius: 10px; padding: 5px 12px; cursor: pointer; user-select: none;
}
</style>
