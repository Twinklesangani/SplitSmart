import { afterEach, expect, it, vi } from 'vitest'
import { apiRequest } from '../services/apiClient.js'

afterEach(() => {
  vi.unstubAllGlobals()
  window.sessionStorage.clear()
})

it('authenticates inbox and acceptance using the existing session', async () => {
  window.sessionStorage.setItem('splitsmart.accessToken', 'test-session-token')
  const fetch = vi.fn().mockResolvedValue({ ok: true, status: 204 })
  vi.stubGlobal('fetch', fetch)
  await apiRequest('/api/invitations/3/accept', { method: 'POST' })
  expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer test-session-token')
})

it('notifies the session when the invitation API rejects an expired token', async () => {
  const expired = vi.fn()
  window.addEventListener('splitsmart:session-expired', expired)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: false, status: 401, json: async () => ({ code: 'unauthorized' }),
  }))
  try {
    await expect(apiRequest('/api/invitations')).rejects.toMatchObject({ status: 401 })
    expect(expired).toHaveBeenCalledOnce()
  } finally {
    window.removeEventListener('splitsmart:session-expired', expired)
  }
})
