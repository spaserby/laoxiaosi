import test from 'node:test'
import assert from 'node:assert/strict'
import { attachmentsReady, attachmentState } from './attachments.js'

const file = { status: 'READY', expiresAt: 2000, chars: 50000, totalPages: 31, processedPages: 31 }
test('ready and partial files can be sent together within their lifetime', () => {
  assert.equal(attachmentsReady([file, { ...file, status: 'PARTIAL' }], 1000), true)
  assert.equal(attachmentState(file, 1000), '已就绪 · 50000 字 · 31/31 页')
})
test('deferred files can be sent and failed parsing only blocks retrieval', () => {
  for (const status of ['STORED', 'QUEUED', 'PROCESSING']) {
    assert.equal(attachmentsReady([{ ...file, status }], 1000), true)
  }
  for (const status of ['FAILED', 'UNKNOWN']) {
    assert.equal(attachmentsReady([file, { ...file, status }], 1000), false)
  }
  assert.equal(attachmentsReady([file], 2000), false)
  assert.equal(attachmentsReady([{ ...file, status: 'FAILED' }], 1000, true), true)
  assert.equal(attachmentsReady([file], 2000, true), false)
  assert.match(attachmentState(file, 2000), /已过期/)
})
