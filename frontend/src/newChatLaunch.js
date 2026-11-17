export async function openNewChatWindow(openWindow, issueEntry) {
  const target = openWindow('', '_blank')
  if (!target) throw new Error('请允许弹出窗口后重试')
  target.opener = null
  target.document.title = 'new chat'
  target.document.body.textContent = '正在进入 new chat…'
  try {
    const result = await issueEntry()
    const entry = new URL(result.entryUrl, window.location.origin)
    const local = entry.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(entry.hostname)
    if ((!local && entry.protocol !== 'https:') || entry.username || entry.password || entry.hash
      || !entry.pathname.endsWith('/platform-entry') || !entry.searchParams.get('ticket')?.startsWith('new_chat_ticket_')) {
      throw new Error('new chat 登录入口无效')
    }
    if (!target.closed) target.location.href = entry.href
  } catch (error) {
    if (!target.closed) target.close()
    throw error
  }
}
