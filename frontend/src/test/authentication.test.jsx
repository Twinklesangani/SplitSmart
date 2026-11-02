import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.jsx'
import { AuthProvider } from '../context/AuthContext.jsx'

const profile = { id: 8, name: 'Samira Patel', email: 'samira@example.com' }
const placeholderToken = 'mock-token-not-a-real-jwt'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

function renderApp(initialPath = '/login', state) {
  const entry = state ? { pathname: initialPath, state } : initialPath
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

async function fillAndSubmitLogin(user, email = profile.email, password = 'correct-horse-battery') {
  await user.type(await screen.findByLabelText('Email address'), email)
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: /sign in/i }))
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('registration', () => {
  it('registers with normalized name and email and redirects to sign in', async () => {
    const user = userEvent.setup()
    fetch.mockResolvedValueOnce(jsonResponse(profile, 201))
    renderApp('/register')

    await user.type(await screen.findByLabelText('Full name'), '  Samira Patel  ')
    await user.type(screen.getByLabelText('Email address'), ' SAMIRA@EXAMPLE.COM ')
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByRole('status')).toHaveTextContent('Your account is ready.')
    expect(fetch).toHaveBeenCalledWith(
      'http://localhost:8081/api/auth/register',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ name: 'Samira Patel', email: 'samira@example.com', password: 'correct-horse-battery' }),
      }),
    )
    expect(window.sessionStorage.length).toBe(0)
  })

  it('shows field validation without sending a request', async () => {
    const user = userEvent.setup()
    renderApp('/register')

    await user.type(await screen.findByLabelText('Full name'), ' ')
    await user.type(screen.getByLabelText('Email address'), 'not-an-email')
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('Enter your full name.')).toBeInTheDocument()
    expect(screen.getByText('Enter a valid email address.')).toBeInTheDocument()
    expect(screen.getByText('Use at least 8 characters.')).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each([
    ['72-byte ASCII', 'A'.repeat(72)],
    ['72-byte Unicode', '€'.repeat(24)],
  ])('accepts a password at the %s limit', async (_label, password) => {
    const user = userEvent.setup()
    fetch.mockResolvedValueOnce(jsonResponse(profile, 201))
    renderApp('/register')

    await user.type(await screen.findByLabelText('Full name'), 'Samira Patel')
    await user.type(screen.getByLabelText('Email address'), profile.email)
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByRole('status')).toHaveTextContent('Your account is ready.')
    expect(fetch).toHaveBeenCalledWith(
      'http://localhost:8081/api/auth/register',
      expect.objectContaining({ body: JSON.stringify({ name: 'Samira Patel', email: profile.email, password }) }),
    )
  })

  it('rejects passwords above 72 UTF-8 bytes and describes the real limit', async () => {
    const user = userEvent.setup()
    renderApp('/register')

    await user.type(await screen.findByLabelText('Full name'), 'Samira Patel')
    await user.type(screen.getByLabelText('Email address'), profile.email)
    const passwordInput = screen.getByLabelText('Password')
    expect(passwordInput).toHaveAccessibleDescription('At least 8 characters and no more than 72 UTF-8 bytes.')
    fireEvent.change(passwordInput, { target: { value: `${'€'.repeat(24)}a` } })
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Password must be no more than 72 UTF-8 bytes.')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('shows a safe duplicate-email response', async () => {
    const user = userEvent.setup()
    fetch.mockResolvedValueOnce(jsonResponse({ code: 'conflict', message: 'Sensitive server detail' }, 409))
    renderApp('/register')

    await user.type(await screen.findByLabelText('Full name'), 'Samira Patel')
    await user.type(screen.getByLabelText('Email address'), profile.email)
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('An account with this email already exists.')
    expect(screen.queryByText('Sensitive server detail')).not.toBeInTheDocument()
  })
})

describe('login and session', () => {
  it('logs in, verifies the profile, saves the real response token and opens the dashboard', async () => {
    const user = userEvent.setup()
    fetch
      .mockResolvedValueOnce(jsonResponse({ accessToken: placeholderToken, tokenType: 'Bearer', expiresIn: 3600 }))
      .mockResolvedValueOnce(jsonResponse(profile))
    renderApp()

    await fillAndSubmitLogin(user)

    expect(await screen.findByRole('heading', { name: 'Welcome, Samira' })).toBeInTheDocument()
    expect(fetch).toHaveBeenNthCalledWith(
      2,
      'http://localhost:8081/api/auth/me',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: `Bearer ${placeholderToken}` }) }),
    )
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBe(placeholderToken)
    expect(screen.queryByText(placeholderToken)).not.toBeInTheDocument()
  })

  it('preserves a safe requested route after login', async () => {
    const user = userEvent.setup()
    fetch
      .mockResolvedValueOnce(jsonResponse({ accessToken: placeholderToken, tokenType: 'Bearer', expiresIn: 3600 }))
      .mockResolvedValueOnce(jsonResponse(profile))
      .mockResolvedValueOnce(jsonResponse([{ id: 42, name: 'Group 42', createdByUserId: profile.id }]))
      .mockResolvedValueOnce(jsonResponse([{ userId: profile.id, name: profile.name, email: profile.email }]))
    renderApp('/groups/42/balances')

    await fillAndSubmitLogin(user)

    expect(await screen.findByRole('heading', { name: 'Balances' })).toBeInTheDocument()
  })

  it('rejects external intended destinations', async () => {
    const user = userEvent.setup()
    fetch
      .mockResolvedValueOnce(jsonResponse({ accessToken: placeholderToken, tokenType: 'Bearer', expiresIn: 3600 }))
      .mockResolvedValueOnce(jsonResponse(profile))
    renderApp('/login', { from: '//example.invalid/steal' })

    await fillAndSubmitLogin(user)

    expect(await screen.findByRole('heading', { name: 'Welcome, Samira' })).toBeInTheDocument()
  })

  it('shows a generic invalid-credentials message and does not expose response details', async () => {
    const user = userEvent.setup()
    fetch.mockResolvedValueOnce(jsonResponse({ code: 'unauthorized', message: placeholderToken }, 401))
    renderApp()

    await fillAndSubmitLogin(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.')
    expect(screen.queryByText(placeholderToken)).not.toBeInTheDocument()
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBeNull()
  })

  it('renders a useful message when the backend cannot be reached', async () => {
    const user = userEvent.setup()
    fetch.mockRejectedValueOnce(new TypeError('connection refused'))
    renderApp()

    await fillAndSubmitLogin(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach SplitSmart.')
    expect(screen.queryByText('connection refused')).not.toBeInTheDocument()
  })

  it('does not send a second login request while the first is pending', async () => {
    const user = userEvent.setup()
    let finishLogin
    fetch
      .mockImplementationOnce(() => new Promise((resolve) => { finishLogin = resolve }))
      .mockResolvedValueOnce(jsonResponse(profile))
      .mockResolvedValueOnce(jsonResponse([]))
    renderApp()

    await user.type(await screen.findByLabelText('Email address'), profile.email)
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery')
    const submit = screen.getByRole('button', { name: /sign in/i })
    await user.click(submit)
    expect(submit).toBeDisabled()
    await user.click(submit)
    expect(fetch).toHaveBeenCalledTimes(1)

    finishLogin(jsonResponse({ accessToken: placeholderToken, tokenType: 'Bearer', expiresIn: 3600 }))
    expect(await screen.findByRole('heading', { name: 'Welcome, Samira' })).toBeInTheDocument()
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3))
  })

  it('redirects unauthenticated users away from protected routes', async () => {
    renderApp('/groups/7/expenses')
    expect(await screen.findByRole('heading', { name: 'Sign in to SplitSmart' })).toBeInTheDocument()
  })

  it('restores a stored session only after the backend profile check succeeds', async () => {
    window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
    window.sessionStorage.setItem('splitsmart.accessTokenExpiresAt', String(Date.now() + 3600000))
    fetch.mockResolvedValueOnce(jsonResponse(profile))
    renderApp('/dashboard')

    expect(await screen.findByRole('heading', { name: 'Welcome, Samira' })).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(
      'http://localhost:8081/api/auth/me',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: `Bearer ${placeholderToken}` }) }),
    )
  })

  it('clears an invalid stored token after the backend rejects it', async () => {
    window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
    fetch.mockResolvedValueOnce(jsonResponse({ code: 'unauthorized', message: 'expired' }, 401))
    renderApp('/dashboard')

    expect(await screen.findByRole('heading', { name: 'Sign in to SplitSmart' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBeNull()
  })

  it('keeps a token during backend outages and offers a session-verification retry', async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
    fetch.mockRejectedValueOnce(new TypeError('connection refused'))
    renderApp('/dashboard')

    expect(await screen.findByRole('heading', { name: 'We couldn’t verify your session' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBe(placeholderToken)
    fetch.mockResolvedValueOnce(jsonResponse(profile))
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Welcome, Samira' })).toBeInTheDocument()
  })

  it('clears partial session state when the login response is malformed', async () => {
    const user = userEvent.setup()
    fetch.mockResolvedValueOnce(jsonResponse({ token: placeholderToken, tokenType: 'Bearer' }))
    renderApp()

    await fillAndSubmitLogin(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('unexpected sign-in response')
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBeNull()
    expect(screen.queryByText(placeholderToken)).not.toBeInTheDocument()
  })

  it('allows an authenticated user to access the dashboard and existing groups navigation', async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
    fetch
      .mockResolvedValueOnce(jsonResponse(profile))
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(jsonResponse([]))
    renderApp('/dashboard')

    await user.click(await screen.findByRole('link', { name: 'My groups' }))
    expect(await screen.findByRole('heading', { name: 'My groups' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'No groups yet' })).toBeInTheDocument()
  })

  it('logs out, clears session storage and protects subsequent history navigation', async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
    window.sessionStorage.setItem('splitsmart.accessTokenExpiresAt', String(Date.now() + 3600000))
    fetch.mockResolvedValueOnce(jsonResponse(profile))
    renderApp('/dashboard')

    await user.click(await screen.findByRole('button', { name: 'Sign out' }))
    expect(await screen.findByRole('heading', { name: 'Sign in to SplitSmart' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Welcome, Samira' })).not.toBeInTheDocument()
  })

  it('redirects signed-in visitors from login to the dashboard', async () => {
    window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
    fetch.mockResolvedValueOnce(jsonResponse(profile))
    renderApp('/login')

    expect(await screen.findByRole('heading', { name: 'Welcome, Samira' })).toBeInTheDocument()
  })
})

describe('authentication throttling feedback', () => {
  it('shows a generic 429 message without automatically retrying login', async () => {
    const user = userEvent.setup()
    fetch.mockResolvedValueOnce(jsonResponse({ code: 'rate_limited' }, 429))
    renderApp('/login')
    await fillAndSubmitLogin(user)
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many requests. Please wait before trying again.')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('shows the same generic message for registration throttling', async () => {
    const user = userEvent.setup()
    fetch.mockResolvedValueOnce(jsonResponse({ code: 'rate_limited' }, 429))
    renderApp('/register')
    await user.type(await screen.findByLabelText('Full name'), 'Synthetic User')
    await user.type(screen.getByLabelText('Email address'), 'synthetic@example.test')
    await user.type(screen.getByLabelText('Password'), 'SecurePass123!')
    await user.click(screen.getByRole('button', { name: /create account/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many requests. Please wait before trying again.')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
