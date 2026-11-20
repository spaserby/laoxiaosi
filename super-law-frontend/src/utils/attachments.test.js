import test from 'node:test'
import assert from 'node:assert/strict'
import { attachmentIssue } from './attachments.js'

const MiB = 1024 * 1024
const policy = {
  maxFiles: 3, maxTotalBytes: 20 * MiB, maxDocumentBytes: 20 * MiB, maxImageBytes: 8 * MiB,
  formats: ['pdf', 'doc', 'png'], imageFormats: ['png']
}
const pdf = { name: 'synthetic.pdf', sizeBytes: 8 * MiB, expiresAt: 2000 }
const doc = { name: 'synthetic.DOC', size: 8 * MiB }
const png = { name: 'synthetic.png', size: 4 * MiB }
test('mixed formats can use the exact total budget', () => {
  assert.equal(attachmentIssue([pdf, doc, png], policy, 1000), '')
  assert.match(attachmentIssue([pdf, doc, { ...png, size: 4.5 * MiB }], policy, 1000), /20\.5 MiB.*20 MiB/)
})
test('picker and reused files share format, size, count and expiry checks', () => {
  assert.match(attachmentIssue([pdf, doc, png, png], policy, 1000), /最多发送 3 个/)
  assert.match(attachmentIssue([{ ...png, size: 8 * MiB + 1 }], policy, 1000), /synthetic.png.*8 MiB/)
  assert.match(attachmentIssue([{ ...doc, name: 'synthetic.xls' }], policy, 1000), /格式暂不支持/)
  assert.match(attachmentIssue([pdf], policy, 2000), /已过期/)
  assert.match(attachmentIssue([doc], null), /暂不可用/)
  assert.equal(attachmentIssue([], null), '')
})
