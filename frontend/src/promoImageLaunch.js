export function buildPromoImageEntry(ticketResponse, toolEntryUrl) {
  const entryUrl = ticketResponse?.entryUrl
  if (typeof entryUrl !== 'string' || !entryUrl.startsWith('/tools/cloud-ai/')) {
    throw new Error('平台返回了无效的推广图工具入口')
  }
  const internalEntry = new URL(entryUrl, 'https://workbench.invalid')
  const ticket = internalEntry.searchParams.get('ticket')
  if (internalEntry.pathname !== '/tools/cloud-ai/platform-entry' || !ticket || !toolEntryUrl) {
    throw new Error('平台返回了无效的推广图工具入口')
  }
  const externalEntry = new URL('platform-entry', `${toolEntryUrl.replace(/\/+$/, '')}/`)
  externalEntry.searchParams.set('ticket', ticket)
  return externalEntry.toString()
}

export async function openPromoImageWindow(openWindow, issueTicket, toolEntryUrl) {
  const toolWindow = openWindow('', '_blank')
  if (!toolWindow) throw new Error('浏览器阻止了新页面，请允许本站打开弹出式窗口')
  toolWindow.opener = null
  try {
    toolWindow.location.href = buildPromoImageEntry(await issueTicket(), toolEntryUrl)
    return toolWindow
  } catch (error) {
    toolWindow.close()
    throw error
  }
}
