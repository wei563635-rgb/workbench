export function tokens(value) {
  if (value === null || value === undefined) return '未配置'
  const n = Number(value)
  if (!Number.isFinite(n)) return '暂不可用'
  return n >= 10000 ? `${(n / 10000).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}万` : n.toLocaleString('zh-CN')
}
export function percent(used, limit) {
  if (limit === null || limit === undefined) return 0
  return limit === 0 ? 100 : Math.min(100, Math.max(0, Number(used) / Number(limit) * 100))
}
// Consumption shares have no denominator when the whole group has no usage.
export function sharePercent(part, total) {
  const numerator = Number(part)
  const denominator = Number(total)
  if (!Number.isFinite(numerator) || !Number.isFinite(denominator) || denominator <= 0) return 0
  return Math.min(100, Math.max(0, numerator / denominator * 100))
}
// Department aggregates are already returned in full; paginate only positive usage rows.
export function consumptionDepartmentPage(rows, requestedPage, pageSize = 5) {
  const sorted = (rows || []).filter(row => Number.isFinite(Number(row.tokens)) && Number(row.tokens) > 0)
    .sort((a, b) => Number(b.tokens) - Number(a.tokens) || String(a.key).localeCompare(String(b.key)))
  const total = sorted.length
  const pages = Math.max(1, Math.ceil(total / pageSize))
  const page = Math.min(pages, Math.max(1, Math.floor(Number(requestedPage) || 1)))
  return { items: sorted.slice((page - 1) * pageSize, page * pageSize), total, pages, page }
}
export function chinaDate(date = new Date()) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(date)
}
export function rangeFor(preset, now = new Date()) {
  const end = chinaDate(now)
  const date = new Date(`${end}T00:00:00+08:00`)
  if (preset === 'week') date.setUTCDate(date.getUTCDate() - 6)
  if (preset === 'month') return { from: end.slice(0, 7) + '-01', to: end }
  if (preset === 'year') date.setUTCDate(date.getUTCDate() - 364)
  return { from: chinaDate(date), to: end }
}
export function heatmap(daily, now = new Date()) {
  const values = new Map((daily || []).map(d => [d.key, Number(d.tokens)]))
  const end = new Date(`${chinaDate(now)}T00:00:00+08:00`)
  return Array.from({ length: 365 }, (_, i) => {
    const date = new Date(end); date.setUTCDate(end.getUTCDate() - 364 + i)
    const day = chinaDate(date), count = values.get(day) || 0
    return { day, count, level: count === 0 ? 0 : count < 10000 ? 1 : count < 50000 ? 2 : count < 100000 ? 3 : 4 }
  })
}
export function readableRelayError(error) {
  try { return JSON.parse(error.message).message || '请求失败，请稍后重试' } catch { return '请求失败，请检查连接后重试' }
}

// Align the first date to Monday; padding is not fabricated zero-usage history.
export function calendarLayout(days) {
  if (!days.length) return { cells: [], months: [], weeks: 53 }
  const offset=(new Date(`${days[0].day}T00:00:00Z`).getUTCDay()+6)%7
  const cells=[...Array(offset).fill(null),...days]
  const weeks=Math.ceil(cells.length/7)
  while(cells.length<weeks*7)cells.push(null)
  const months=[]
  cells.forEach((day,i)=>{
    if(day && (i===offset || day.day.endsWith('-01'))){
      const column=Math.floor(i/7)+1
      if(column<weeks && !(i===offset && Number(day.day.slice(8))>15)) months.push({key:day.day,label:`${Number(day.day.slice(5,7))}月`,column})
    }
  })
  return {cells,months,weeks}
}
export function longestWorkdayStreak(days) {
  let current=0,longest=0
  for(const day of days){
    const weekday=new Date(`${day.day}T00:00:00Z`).getUTCDay()
    if(weekday===0||weekday===6)continue
    current=day.count>0?current+1:0
    longest=Math.max(longest,current)
  }
  return longest
}
