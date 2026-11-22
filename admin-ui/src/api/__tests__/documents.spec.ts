import { describe, it, expect, vi, beforeEach } from 'vitest'

const post = vi.fn()
vi.mock('../client', () => ({ default: { post: (...args: unknown[]) => post(...args) } }))

import { uploadDocumentFile } from '../documents'

describe('uploadDocumentFile', () => {
  beforeEach(() => {
    post.mockReset()
    post.mockResolvedValue({ data: { documentId: 'doc-1', chunks: 2, message: 'ok' } })
  })

  it('posts the file as multipart form data to /documents/upload', async () => {
    const file = new File(['%PDF-1.4'], 'report.pdf', { type: 'application/pdf' })

    const result = await uploadDocumentFile(file, { collection: 'docs', metadata: { team: 'a' } })

    expect(result.documentId).toBe('doc-1')
    const [url, body, config] = post.mock.calls[0]
    expect(url).toBe('/documents/upload')
    expect(body).toBeInstanceOf(FormData)
    const form = body as FormData
    expect((form.get('file') as File).name).toBe('report.pdf')
    expect(form.get('collection')).toBe('docs')
    expect(JSON.parse(form.get('metadata') as string)).toEqual({ team: 'a' })
    expect(config.headers['Content-Type']).toBe('multipart/form-data')
  })

  it('omits empty optional fields', async () => {
    await uploadDocumentFile(new File(['hello'], 'notes.txt'))

    const form = post.mock.calls[0][1] as FormData
    expect(form.has('collection')).toBe(false)
    expect(form.has('metadata')).toBe(false)
  })
})
