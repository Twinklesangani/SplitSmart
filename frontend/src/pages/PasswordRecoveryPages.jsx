import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Button, FormField } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { ApiError, apiRequest } from '../services/apiClient.js'
import { clearRecoveryToken, getRecoveryToken } from '../services/recoveryLink.js'
import '../styles/recovery.css'
import { emailActionsEnabled } from '../services/emailAvailability.js'

function RecoveryCard({ title, description, children }) {
  const heading = useRef(null)
  useEffect(() => { heading.current?.focus() }, [title])
  return (
    <section className="auth-card recovery-card" aria-labelledby="recovery-title">
      <p className="eyebrow">ACCOUNT RECOVERY</p>
      <h2 id="recovery-title" tabIndex={-1} ref={heading}>{title}</h2>
      <p className="auth-card__description">{description}</p>
      {children}
      <p className="auth-switch"><Link to="/login">Back to sign in</Link></p>
    </section>
  )
}

function RecoveryUnavailable() {
  return <RecoveryCard title="Password recovery unavailable" description="Email recovery is temporarily unavailable. Please try again later.">
    <p>If you remember your password, you can still sign in below.</p>
  </RecoveryCard>
}

export function ForgotPasswordPage() {
  const navigate = useNavigate()
  const input = useRef(null)
  const lock = useRef(false)
  const [email, setEmail] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  if (!emailActionsEnabled()) return <RecoveryUnavailable />

  async function submit(event) {
    event.preventDefault()
    if (lock.current) return
    const normalized = email.trim().toLowerCase()
    if (!normalized || normalized.length > 255 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)) {
      setError('Enter a valid email address.')
      input.current?.focus()
      return
    }
    lock.current = true
    setBusy(true)
    setMessage('')
    try {
      await apiRequest('/api/auth/forgot-password', { method: 'POST', body: { email: normalized }, expectedStatus: 202 })
      navigate('/check-email', { replace: true, state: { requested: true } })
    } catch (failure) {
      setMessage(failure instanceof ApiError ? failure.message : 'Recovery could not be requested. Please try again.')
    } finally {
      lock.current = false
      setBusy(false)
    }
  }

  return (
    <RecoveryCard title="Forgot your password?" description="Enter the email you use for SplitSmart. We’ll help you get back to your groups.">
      {message && <p className="auth-message" role="alert">{message}</p>}
      <form className="auth-form" onSubmit={submit} noValidate aria-label="Request password reset">
        <FormField id="email" label="Email address" type="email" autoComplete="email" value={email} maxLength={255}
          inputRef={input} error={error} onChange={(event) => { setEmail(event.target.value); setError('') }} />
        <Button type="submit" className="auth-submit" disabled={busy} aria-busy={busy}>{busy ? 'Requesting…' : 'Request reset link'}</Button>
      </form>
      <p className="recovery-note">For your privacy, we won’t confirm whether an email has an account.</p>
    </RecoveryCard>
  )
}

export function CheckEmailPage() {
  const { state } = useLocation()
  if (!emailActionsEnabled()) return <RecoveryUnavailable />
  return (
    <RecoveryCard title={state?.requested ? 'Check your email' : 'Need a reset link?'}
      description={state?.requested ? 'Your request was received. Email delivery has not been confirmed.' : 'Start with your account email to request a password reset.'}>
      {state?.requested && <div className="recovery-guidance">
        <p>If an account matches and sending is available, you may receive a reset link. Check your inbox and spam folder.</p>
        <p>Links expire after 30 minutes and work once. If a message doesn’t arrive, wait a minute before requesting another.</p>
      </div>}
      <Link className="button button--secondary recovery-action" to="/forgot-password">{state?.requested ? 'Request another link' : 'Request a reset link'}</Link>
    </RecoveryCard>
  )
}

export function ResetPasswordPage() {
  const navigate = useNavigate()
  const { logout } = useAuth()
  const [token, setToken] = useState(getRecoveryToken)
  const [values, setValues] = useState({ password: '', confirmPassword: '' })
  const [errors, setErrors] = useState({})
  const [message, setMessage] = useState('')
  const [expired, setExpired] = useState(false)
  const [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const password = useRef(null)
  const confirmation = useRef(null)
  const lock = useRef(false)
  // The component keeps the only active copy after mounting; refresh requires a new/opened link.
  useEffect(() => { clearRecoveryToken() }, [])

  async function submit(event) {
    event.preventDefault()
    if (lock.current) return
    const next = {}
    if (!values.password.trim() || values.password.length < 8) next.password = 'Use at least 8 characters.'
    else if (new TextEncoder().encode(values.password).length > 72) next.password = 'Use no more than 72 UTF-8 bytes.'
    if (!values.confirmPassword) next.confirmPassword = 'Confirm your new password.'
    else if (values.password !== values.confirmPassword) next.confirmPassword = 'Passwords must match.'
    setErrors(next)
    if (Object.keys(next).length) {
      (next.password ? password : confirmation).current?.focus()
      return
    }
    lock.current = true
    setBusy(true)
    setMessage('')
    try {
      await apiRequest('/api/auth/reset-password', { method: 'POST', body: { token, ...values }, expectedStatus: 204 })
      setToken('')
      setValues({ password: '', confirmPassword: '' })
      try { logout() } catch { /* Server-side revocation still protects an uncleared browser token. */ }
      navigate('/reset-success', { replace: true, state: { resetComplete: true } })
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 400) {
        setToken('')
        setValues({ password: '', confirmPassword: '' })
        setExpired(true)
      } else {
        setMessage(failure instanceof ApiError ? failure.message : 'Your password could not be updated. Please try again.')
      }
    } finally {
      lock.current = false
      setBusy(false)
    }
  }

  if (!token || expired) return (
    <RecoveryCard title="Request a new reset link" description="This link is invalid, expired, already used, or no longer available in this tab.">
      <Link className="button button--primary recovery-action" to="/forgot-password">Request a new link</Link>
    </RecoveryCard>
  )

  return (
    <RecoveryCard title="Choose a new password" description="Use a password you don’t use elsewhere. Updating it will sign you out of existing sessions.">
      {message && <p className="auth-message" role="alert">{message} If the update may have completed, try signing in or request a new link.</p>}
      <form className="auth-form" onSubmit={submit} noValidate aria-label="Reset password">
        <FormField id="password" label="New password" type={visible ? 'text' : 'password'} autoComplete="new-password"
          value={values.password} inputRef={password} error={errors.password} hint="At least 8 characters; up to 72 UTF-8 bytes."
          onChange={(event) => { setValues((old) => ({ ...old, password: event.target.value })); setErrors({}) }} />
        <FormField id="confirmPassword" label="Confirm new password" type={visible ? 'text' : 'password'} autoComplete="new-password"
          value={values.confirmPassword} inputRef={confirmation} error={errors.confirmPassword}
          onChange={(event) => { setValues((old) => ({ ...old, confirmPassword: event.target.value })); setErrors({}) }} />
        <button className="recovery-visibility" type="button" aria-pressed={visible} aria-controls="password confirmPassword" onClick={() => setVisible((old) => !old)}>{visible ? 'Hide passwords' : 'Show passwords'}</button>
        <Button type="submit" className="auth-submit" disabled={busy} aria-busy={busy}>{busy ? 'Updating password…' : 'Update password'}</Button>
      </form>
    </RecoveryCard>
  )
}

export function ResetSuccessPage() {
  const { state } = useLocation()
  return (
    <RecoveryCard title={state?.resetComplete ? 'Your password is updated' : 'Sign in to continue'}
      description={state?.resetComplete ? 'Your previous sessions have ended. Sign in with your new password to return to your groups.' : 'Use your current password, or request a new reset link.'}>
      <Link className="button button--primary recovery-action" to="/login">Sign in to SplitSmart</Link>
    </RecoveryCard>
  )
}
