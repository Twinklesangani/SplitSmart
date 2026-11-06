import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import { ForgotPasswordPage, CheckEmailPage } from '../pages/PasswordRecoveryPages.jsx'
import { InviteForm, InvitationInbox } from '../pages/Invitations.jsx'
import { apiRequest } from '../services/apiClient.js'

beforeEach(() => {
  vi.stubEnv('VITE_EMAIL_ACTIONS_ENABLED', 'false')
  vi.stubGlobal('fetch', vi.fn())
})

it('explains unavailable recovery before collecting email or making a request', () => {
  render(<MemoryRouter><ForgotPasswordPage /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: 'Password recovery unavailable' })).toHaveFocus()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login')
  expect(fetch).not.toHaveBeenCalled()
})

it('does not show a check-email success screen from stale navigation state', () => {
  render(<MemoryRouter initialEntries={[{ pathname: '/check-email', state: { requested: true } }]}><CheckEmailPage /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: 'Password recovery unavailable' })).toBeInTheDocument()
  expect(screen.queryByText('Your request was received. Email delivery has not been confirmed.')).not.toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})

it('keeps private invitation creation and copy available while email creation is hidden', async () => {
  const user = userEvent.setup()
  fetch.mockImplementation(async url => new Response(JSON.stringify(
    url.endsWith('/invite-links') && fetch.mock.calls.length === 1 ? { token: 'Z'.repeat(43) } : [],
  ), { status: fetch.mock.calls.length === 1 ? 201 : 200 }))
  render(<InviteForm groupId={42} />)
  expect(screen.queryByLabelText('Invitation email address')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Create private invite link' }))
  expect(await screen.findByLabelText('Private invitation link')).toHaveValue(`${window.location.origin}/join#token=${'Z'.repeat(43)}`)
  expect(screen.getByRole('button', { name: 'Copy link' })).toBeEnabled()
  expect(fetch.mock.calls.some(([url, options]) => url.endsWith('/invitations') && options.method === 'POST')).toBe(false)
})

it('disables mailbox verification without disabling existing authorized invitation checks', async () => {
  render(<InvitationInbox userId={7} onAccepted={vi.fn()} />)
  expect(screen.getByRole('button', { name: 'Verify my email' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Check invitations' })).toBeEnabled()
  expect(fetch).not.toHaveBeenCalled()
})

it.each([502, 503, 504])('explains an HTTP %s cold start without retrying mutations', async status => {
  fetch.mockResolvedValue(new Response('{}', { status }))
  await expect(apiRequest('/api/groups/42/expenses', { method: 'POST', body: {} })).rejects.toThrow('Check history before repeating')
  expect(fetch).toHaveBeenCalledTimes(1)
})

it('explains timeout without clearing authentication or replaying a mutation', async () => {
  window.sessionStorage.setItem('splitsmart.accessToken', 'disposable-token')
  fetch.mockRejectedValue(new DOMException('Aborted', 'AbortError'))
  await expect(apiRequest('/api/groups/42/settlements', { method: 'POST', body: {} })).rejects.toThrow('may be starting up')
  expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBe('disposable-token')
  expect(fetch).toHaveBeenCalledTimes(1)
})
