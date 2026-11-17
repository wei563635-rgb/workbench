import test from 'node:test'
import assert from 'node:assert/strict'
import { openNewChatWindow } from '../newChatLaunch.js'

globalThis.window = { location: { origin: 'https://workbench.example' } }
const entryUrl = 'https://chat.example/tools/new-chat/platform-entry?ticket=new_chat_ticket_example'
function popup() {
  return { opener: {}, document: { title: '', body: { textContent: '' } }, location: { href: '' }, closed: false, close() { this.closed = true } }
}

test('new chat opens a detached tab before issuing a project-free login ticket', async () => {
  const target = popup(), calls = []
  await openNewChatWindow(() => { calls.push('open'); return target }, async () => { calls.push('issue'); return { entryUrl } })
  assert.deepEqual(calls, ['open', 'issue'])
  assert.equal(target.opener, null)
  assert.equal(target.location.href, entryUrl)
})

test('blocked popup consumes no ticket; failed or unsafe entry closes the blank tab', async () => {
  let issued = false
  await assert.rejects(openNewChatWindow(() => null, async () => { issued = true }), /弹出窗口/)
  assert.equal(issued, false)
  for (const url of ['javascript:alert(1)', 'http://public.example/platform-entry?ticket=new_chat_ticket_x', 'https://chat.example/platform-entry?ticket=tool_ticket_x']) {
    const target = popup()
    await assert.rejects(openNewChatWindow(() => target, async () => ({ entryUrl: url })), /无效/)
    assert.equal(target.closed, true)
  }
  const target = popup()
  await assert.rejects(openNewChatWindow(() => target, async () => { throw new Error('not configured') }), /not configured/)
  assert.equal(target.closed, true)
})
