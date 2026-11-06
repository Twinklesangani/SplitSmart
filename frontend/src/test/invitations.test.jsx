import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { InviteForm, InvitationInbox } from '../pages/Invitations.jsx'
import { apiRequest } from '../services/apiClient.js'

vi.mock('../services/apiClient.js', () => ({ apiRequest: vi.fn() }))

beforeEach(() => { vi.resetAllMocks(); vi.stubEnv('VITE_EMAIL_ACTIONS_ENABLED', 'true') })
afterEach(() => vi.restoreAllMocks())

it('invites an email without adding membership or claiming email delivery', async () => {
  const user = userEvent.setup()
  apiRequest.mockResolvedValue({ id: 1 })
  render(<InviteForm groupId={42} />)
  await user.type(screen.getByLabelText('Invitation email address'), 'New@Example.test')
  await user.click(screen.getByRole('button', { name: 'Create invitation' }))
  expect(apiRequest).toHaveBeenCalledWith('/api/groups/42/invitations', {
    method: 'POST', body: { email: 'new@example.test' },
  })
  expect(await screen.findByRole('status')).toHaveTextContent('Email delivery is not confirmed')
})

it.each(['accept', 'decline'])('resolves an inbox invitation with %s and refreshes groups only on acceptance', async decision => {
  const user = userEvent.setup()
  const onAccepted = vi.fn()
  apiRequest.mockResolvedValueOnce([{ id: 3, groupName: 'Shared trip', expiresAt: '2030-01-01T00:00:00Z' }])
    .mockResolvedValueOnce(null)
  render(<InvitationInbox userId={7} onAccepted={onAccepted} />)
  await user.click(screen.getByRole('button', { name: 'Check invitations' }))
  await screen.findByText('Shared trip')
  await user.click(screen.getByRole('button', { name: decision === 'accept' ? 'Accept invitation' : 'Decline invitation' }))
  await waitFor(() => expect(screen.queryByText('Shared trip')).not.toBeInTheDocument())
  expect(apiRequest).toHaveBeenLastCalledWith(`/api/invitations/3/${decision}`, { method: 'POST' })
  expect(onAccepted).toHaveBeenCalledTimes(decision === 'accept' ? 1 : 0)
})

it('reloads invitations when returning to the tab and removes event listeners on unmount', async () => {
  const user = userEvent.setup()
  apiRequest.mockResolvedValue([])
  const view = render(<InvitationInbox userId={7} onAccepted={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: 'Check invitations' }))
  const initial = apiRequest.mock.calls.length
  window.dispatchEvent(new Event('blur'))
  window.dispatchEvent(new Event('focus'))
  await waitFor(() => expect(apiRequest.mock.calls.length).toBe(initial + 1))
  view.unmount()
  window.dispatchEvent(new Event('focus'))
  expect(apiRequest.mock.calls.length).toBe(initial + 1)
})
