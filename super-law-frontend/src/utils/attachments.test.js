import test from 'node:test'
import assert from 'node:assert/strict'
import { attachmentsReady, attachmentState } from './attachments.js'

const file = { status: 'READY', expiresAt: 2000, chars: 50000, totalPages: 31, processedPages: 31 }
test('ready and partial files can be sent together within their lifetime', () => {
  assert.equal(attachmentsReady([file, { ...file, status: 'PARTIAL' }], 1000), true)
  assert.equal(attachmentState(file, 1000), '已就绪 · 50000 字 · 31/31 页')
})
test('processing, failed, unknown and expired files block sending', () => {
  for (const status of ['QUEUED', 'PROCESSING', 'FAILED', 'UNKNOWN']) {
    assert.equal(attachmentsReady([file, { ...file, status }], 1000), false)
  }
  assert.equal(attachmentsReady([file], 2000), false)
  assert.match(attachmentState(file, 2000), /已过期/)
})
