import { extractRunAttachments } from './runAttachments.js'

export function filterRunRecords(records, searchQuery, filters) {
  const keyword = String(searchQuery || '').trim().toLocaleLowerCase()
  const selected = filters || {}

  return (records || []).filter((record) => {
    const searchable = [
      record.id,
      record.tool,
      record.user,
      record.dept,
      record.project,
      record.input,
      record.output
    ].map((value) => String(value || '').toLocaleLowerCase())

    return (!keyword || searchable.some((value) => value.includes(keyword)))
      && (!selected.tool || selected.tool === 'all' || record.toolKey === selected.tool)
      && (!selected.employee || selected.employee === 'all' || record.user === selected.employee || record.userId === selected.employee)
      && (!selected.department || selected.department === 'all' || record.deptKey === selected.department)
      && (!selected.status || selected.status === 'all' || record.statusKey === selected.status)
  })
}

export function paginateRecords(records, page, pageSize) {
  const normalizedSize = Math.max(1, Number(pageSize) || 10)
  const normalizedPage = Math.max(1, Number(page) || 1)
  const start = (normalizedPage - 1) * normalizedSize
  return (records || []).slice(start, start + normalizedSize)
}

export function sortRunRecordsNewestFirst(records) {
  return [...(records || [])].sort((left, right) => {
    const rightTime = new Date(right.finishedAt || right.startedAt || 0).getTime()
    const leftTime = new Date(left.finishedAt || left.startedAt || 0).getTime()
    return rightTime - leftTime
  })
}

export function splitRunTimestamp(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return { date: '-', clock: '-' }
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).formatToParts(date)
  const valueOf = (type) => parts.find((part) => part.type === type)?.value || '--'
  return {
    date: `${valueOf('year')}-${valueOf('month')}-${valueOf('day')}`,
    clock: `${valueOf('hour')}:${valueOf('minute')}:${valueOf('second')}`
  }
}

export function formatRunDuration(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) return '-'
  const milliseconds = new Date(finishedAt).getTime() - new Date(startedAt).getTime()
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '-'
  if (milliseconds < 1000) return `${milliseconds}ms`
  return `${(milliseconds / 1000).toFixed(1)}s`
}

export function hasCopyableRunResult(run) {
  if (String(run?.output || '').trim() && run.output !== '-') return true
  if (run?.hasOutputData) return true
  return Boolean(run?.outputData && Object.keys(run.outputData).length)
}

export function hasDownloadableRunAttachment(run) {
  return Boolean(run?.hasAttachments) || extractRunAttachments(run).length > 0
}

export function buildRunDetailPayload(run) {
  const raw = run?.raw && typeof run.raw === 'object' ? run.raw : run || {}
  const inputPrompt = String(run?.input || '').trim()
  if (!inputPrompt || inputPrompt === '-') return raw
  return { ...raw, inputPrompt }
}
