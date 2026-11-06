import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import { InviteForm, InvitationInbox } from '../pages/Invitations.jsx'
import InvitationLinkPage from '../pages/InvitationLinkPage.jsx'
import { prepareRecoveryLink } from '../services/recoveryLink.js'
import { apiRequest } from '../services/apiClient.js'

vi.mock('../services/apiClient.js', () => ({ apiRequest: vi.fn() }))
vi.mock('../context/useAuth.js', () => ({ useAuth: () => ({ status: 'authenticated' }) }))
beforeEach(() => { vi.resetAllMocks(); window.history.replaceState(null, '', '/'); prepareRecoveryLink() })

it('creates a private link and shows expiry/revocation controls without enrolling anyone', async () => {
  const user = userEvent.setup()
  apiRequest.mockResolvedValueOnce({ token: 'B'.repeat(43) })
    .mockResolvedValueOnce([]).mockResolvedValueOnce([{ id: 2, status: 'PENDING', expiresAt: '2030-01-01' }])
  render(<InviteForm groupId={42} />)
  await user.click(screen.getByRole('button', { name: 'Create private invite link' }))
  expect(await screen.findByLabelText('Private invitation link')).toHaveValue(`${window.location.origin}/join#token=${'B'.repeat(43)}`)
  expect(screen.getByRole('button', { name: 'Revoke invitation' })).toBeInTheDocument()
  expect(apiRequest.mock.calls.some(([url]) => url.endsWith('/members'))).toBe(false)
})

it('reports verification unavailable without suggesting mailbox ownership is established', async () => {
  vi.stubEnv('VITE_EMAIL_ACTIONS_ENABLED', 'true')
  const user = userEvent.setup()
  apiRequest.mockRejectedValue(new Error('Email verification is currently unavailable. Ask the group owner for a private invitation link.'))
  render(<InvitationInbox userId={1} onAccepted={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: 'Verify my email' }))
  expect(await screen.findByRole('status')).toHaveTextContent('currently unavailable')
})

it('requires explicit acceptance of a fragment link and then opens the joined group', async () => {
  const user = userEvent.setup()
  const token = 'C'.repeat(43)
  window.history.replaceState(null, '', `/join#token=${token}`); prepareRecoveryLink()
  expect(window.location.hash).toBe('')
  apiRequest.mockResolvedValue({ groupId: 42 })
  render(<MemoryRouter initialEntries={['/join']}><Routes><Route path="/join" element={<InvitationLinkPage />} /><Route path="/groups/42" element={<p>Joined group</p>} /></Routes></MemoryRouter>)
  expect(apiRequest).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Accept and join group' }))
  expect(await screen.findByText('Joined group')).toBeInTheDocument()
  expect(apiRequest).toHaveBeenCalledWith('/api/invitations/link-accept', { method: 'POST', body: { token } })
})

it('handles an expired private link without exposing its token in the UI', async () => {
  const user = userEvent.setup()
  const token = 'D'.repeat(43)
  window.history.replaceState(null, '', `/join#token=${token}`); prepareRecoveryLink()
  apiRequest.mockRejectedValue({ status: 404 })
  render(<MemoryRouter><InvitationLinkPage /></MemoryRouter>)
  await user.click(screen.getByRole('button', { name: 'Accept and join group' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('expired, revoked, used')
  expect(document.body.textContent).not.toContain(token)
})
