import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import App from '../App.jsx'
import { AuthProvider } from '../context/AuthContext.jsx'
import { clearRecoveryToken, prepareRecoveryLink } from '../services/recoveryLink.js'

const token = 'A'.repeat(43)
const password = 'NewPassword123!'
const response = (status, body = {}) => ({ status, ok: status >= 200 && status < 300, json: async () => body })

function renderApp(path = '/forgot-password') {
  return render(<MemoryRouter initialEntries={[path]}><AuthProvider><App /></AuthProvider></MemoryRouter>)
}

function resetLink() {
  window.history.replaceState(null, '', `/reset-password#token=${token}`)
  prepareRecoveryLink()
}

async function fillReset(user) {
  await user.type(await screen.findByLabelText('New password'), password)
  await user.type(screen.getByLabelText('Confirm new password'), password)
}

beforeEach(() => {
  vi.stubEnv('VITE_EMAIL_ACTIONS_ENABLED', 'true')
  window.history.replaceState(null, '', '/')
  clearRecoveryToken()
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => { clearRecoveryToken(); vi.restoreAllMocks() })

it('links from login to accessible recovery validation without sending invalid email', async () => {
  const user = userEvent.setup()
  renderApp('/login')
  await user.click(await screen.findByRole('link', { name: 'Forgot your password?' }))
  await user.click(screen.getByRole('button', { name: 'Request reset link' }))
  expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid email address.')
  expect(screen.getByLabelText('Email address')).toHaveFocus()
  expect(fetch).not.toHaveBeenCalled()
})

it('normalizes email and describes a request without claiming delivery', async () => {
  const user = userEvent.setup()
  fetch.mockResolvedValue(response(202))
  renderApp()
  await user.type(await screen.findByLabelText('Email address'), ' PERSON@EXAMPLE.COM ')
  await user.click(screen.getByRole('button', { name: 'Request reset link' }))
  expect(await screen.findByRole('heading', { name: 'Check your email' })).toHaveFocus()
  expect(screen.getByText('Your request was received. Email delivery has not been confirmed.')).toBeInTheDocument()
  expect(fetch).toHaveBeenCalledWith('http://localhost:8081/api/auth/forgot-password', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ email: 'person@example.com' }),
  }))
  expect(window.sessionStorage.length).toBe(0)
})

it.each([503, 429, 500])('shows a safe request error for HTTP %s without a delivery success screen', async (status) => {
  const user = userEvent.setup()
  fetch.mockResolvedValue(response(status, { message: 'private-provider-body' }))
  renderApp()
  await user.type(await screen.findByLabelText('Email address'), 'person@example.com')
  await user.click(screen.getByRole('button', { name: 'Request reset link' }))
  const error = await screen.findByRole('alert')
  expect(error).not.toHaveTextContent('private-provider-body')
  if (status === 503) expect(error).toHaveTextContent('Email recovery is currently unavailable')
  expect(screen.queryByRole('heading', { name: 'Check your email' })).not.toBeInTheDocument()
})

it('prevents duplicate requests and shows a pending state', async () => {
  const user = userEvent.setup()
  let finish
  fetch.mockImplementation(() => new Promise((resolve) => { finish = resolve }))
  renderApp()
  await user.type(await screen.findByLabelText('Email address'), 'person@example.com')
  const form = screen.getByRole('form', { name: 'Request password reset' })
  fireEvent.submit(form)
  fireEvent.submit(form)
  expect(screen.getByRole('button', { name: 'Requesting…' })).toBeDisabled()
  expect(fetch).toHaveBeenCalledTimes(1)
  finish(response(202))
  await screen.findByRole('heading', { name: 'Check your email' })
})

it('scrubs link secrets before render and resets via POST body without bearer token', async () => {
  const user = userEvent.setup()
  resetLink()
  expect(window.location.href).not.toContain(token)
  fetch.mockResolvedValue(response(204))
  renderApp('/reset-password')
  await fillReset(user)
  await user.click(screen.getByRole('button', { name: 'Update password' }))
  expect(await screen.findByRole('heading', { name: 'Your password is updated' })).toHaveFocus()
  expect(fetch).toHaveBeenCalledWith('http://localhost:8081/api/auth/reset-password', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ token, password, confirmPassword: password }),
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
  }))
  expect(window.sessionStorage.length).toBe(0)
  await user.click(screen.getByRole('link', { name: 'Sign in to SplitSmart' }))
  expect(await screen.findByRole('heading', { name: 'Sign in to SplitSmart' })).toBeInTheDocument()
})

it('validates passwords and confirmation and supports keyboard-accessible visibility', async () => {
  const user = userEvent.setup()
  resetLink()
  renderApp('/reset-password')
  await user.click(await screen.findByRole('button', { name: 'Update password' }))
  expect(screen.getByLabelText('New password')).toHaveFocus()
  expect(fetch).not.toHaveBeenCalled()
  await user.type(screen.getByLabelText('New password'), 'é'.repeat(40))
  await user.type(screen.getByLabelText('Confirm new password'), 'different-password')
  await user.click(screen.getByRole('button', { name: 'Update password' }))
  expect(screen.getByText('Use no more than 72 UTF-8 bytes.')).toBeInTheDocument()
  expect(screen.getByText('Passwords must match.')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Show passwords' }))
  expect(screen.getByLabelText('New password')).toHaveAttribute('type', 'text')
  expect(screen.getByRole('button', { name: 'Hide passwords' })).toHaveAttribute('aria-pressed', 'true')
  expect(fetch).not.toHaveBeenCalled()
})

it('handles expired or reused links without showing the token or retaining passwords', async () => {
  const user = userEvent.setup()
  resetLink()
  fetch.mockResolvedValue(response(400))
  renderApp('/reset-password')
  await fillReset(user)
  await user.click(screen.getByRole('button', { name: 'Update password' }))
  expect(await screen.findByRole('heading', { name: 'Request a new reset link' })).toHaveFocus()
  expect(screen.queryByLabelText('New password')).not.toBeInTheDocument()
  expect(document.body.textContent).not.toContain(token)
  expect(window.location.href).not.toContain(token)
})

it('keeps a valid link after a network failure and permits an explicit retry', async () => {
  const user = userEvent.setup()
  resetLink()
  fetch.mockRejectedValueOnce(new TypeError('synthetic-network-error')).mockResolvedValueOnce(response(204))
  renderApp('/reset-password')
  await fillReset(user)
  await user.click(screen.getByRole('button', { name: 'Update password' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('If the update may have completed')
  expect(fetch).toHaveBeenCalledTimes(1)
  await user.click(screen.getByRole('button', { name: 'Update password' }))
  await screen.findByRole('heading', { name: 'Your password is updated' })
})

it('permits reset while signed in and clears the old local session after success', async () => {
  const user = userEvent.setup()
  resetLink()
  window.sessionStorage.setItem('splitsmart.accessToken', 'synthetic-old-jwt')
  fetch.mockResolvedValueOnce(response(200, { id: 1, name: 'Person', email: 'person@example.com' }))
    .mockResolvedValueOnce(response(204))
  renderApp('/reset-password')
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
  await fillReset(user)
  await user.click(screen.getByRole('button', { name: 'Update password' }))
  await screen.findByRole('heading', { name: 'Your password is updated' })
  expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBeNull()
})

it('does not accept query-string secrets or invent success on directly opened status pages', async () => {
  window.history.replaceState(null, '', `/reset-password?token=${token}`)
  prepareRecoveryLink()
  expect(window.location.search).toBe('')
  renderApp('/reset-password')
  expect(await screen.findByRole('heading', { name: 'Request a new reset link' })).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})

it('shows guidance rather than delivery confirmation when check-email is opened directly', async () => {
  renderApp('/check-email')
  expect(await screen.findByRole('heading', { name: 'Need a reset link?' })).toBeInTheDocument()
})
