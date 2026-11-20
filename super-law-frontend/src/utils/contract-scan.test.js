import { test } from 'node:test'
import assert from 'node:assert/strict'
import { scanDimensions, scanFileSpec, scanSelectionIssue, uploadScanFile } from './contract-scan.js'

test('scans high resolution photos with proportional scaling and keeps small pages sharp', () => {
  assert.deepEqual(scanDimensions(8000, 6000, 3000, 50_000_000), { width: 3000, height: 2250 })
  assert.deepEqual(scanDimensions(1200, 1600, 3000, 50_000_000), { width: 1200, height: 1600 })
  assert.deepEqual(scanDimensions(8000, 8000, 9000, 9_000_000), { width: 3000, height: 3000 })
})

test('counts optimized bytes rather than original photo file sizes', () => {
  const policy = { maxPhotos: 50, maxBytes: 100 * 1024 ** 2 }
  const photos = Array.from({ length: 50 }, (_, i) => ({ name: `scan-${i}.jpg`, type: 'image/jpeg', size: 12 * 1024 ** 2 }))
  assert.equal(scanSelectionIssue(photos, policy), '')
  const copies = photos.map(file => ({ ...file, format: 'jpg', size: 2 * 1024 ** 2 }))
  assert.equal(scanSelectionIssue(copies, policy), '')
  assert.match(scanSelectionIssue(copies.map(file => ({ ...file, size: file.size + 1 })), policy), /总大小/)
  assert.match(scanSelectionIssue([...photos, photos[0]], policy), /50/)
  assert.match(scanSelectionIssue([photos[0], { name: 'synthetic.pdf', type: 'application/pdf', size: 1024 }], policy), /一份 PDF/)
})

test('resumes a large file from verified chunks and tolerates a lost acknowledgement', async () => {
  const contents = new Blob(['abcdefghij'])
  const attempts = []
  const progress = []
  let failedOnce = false
  await uploadScanFile(contents, 4, 4, async (offset, body) => {
    attempts.push([offset, await body.text()])
    if (offset === 4 && !failedOnce) { failedOnce = true; throw new Error('synthetic connection loss') }
    return { received: offset + body.size }
  }, value => progress.push(value))
  assert.deepEqual(attempts, [[0, 'abcd'], [4, 'efgh'], [4, 'efgh'], [8, 'ij']])
  assert.deepEqual(progress, [4, 8, 10])
})

test('hashes each upload chunk independently without changing the source file', async () => {
  const blob = new Blob(['abcdefgh'])
  const spec = await scanFileSpec({ blob, name: 'synthetic.pdf', format: 'pdf', size: blob.size, optimized: false }, 4)
  const { createHash } = await import('node:crypto')
  assert.deepEqual(spec.chunkHashes, ['abcd', 'efgh'].map(value => createHash('sha256').update(value).digest('hex')))
  assert.equal(await blob.text(), 'abcdefgh')
})
