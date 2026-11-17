export function windowAverageValue(window) {
  const value = Number(window?.averageRemainingPercent)
  return window?.accountCount > 0 && Number.isFinite(value) && value >= 0 && value <= 100
    ? `${value.toFixed(1)}%`
    : '—'
}

export function windowAverageNote(window) {
  const count = Number(window?.accountCount) || 0
  return count > 0
    ? `${count} 个正常账号有窗口读数；剩余百分比算术平均，仅供参考，不代表可用 Token 总量`
    : '暂无正常账号的有效窗口读数；平均百分比仅供参考'
}
