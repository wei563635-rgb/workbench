import test from 'node:test'
import assert from 'node:assert/strict'
import { workbenchFileUrl } from '../workbenchFileUrl.js'

test('旧服务器 IP 上的工作台文件改用同域路径', () => {
  const path = '/api/files/demo-download/file_59044131537144be8274d6b08df64a7e'
  assert.equal(workbenchFileUrl(`http://120.26.221.135:18081${path}`), path)
  assert.equal(workbenchFileUrl(`https://www.sharpseek.cn${path}`), path)
  assert.equal(workbenchFileUrl(path), path)
})

test('其他外部文件地址保持原样', () => {
  assert.equal(workbenchFileUrl('https://example.com/image.png'), 'https://example.com/image.png')
})
