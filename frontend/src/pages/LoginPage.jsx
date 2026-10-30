import { useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Button, FormField } from '../components/UI.jsx'
import { ApiError } from '../services/apiClient.js'
import { useAuth } from '../context/useAuth.js'

function isValidEmail(email) {
  return email.length <= 255 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

function safeDestination(candidate) {
  if (typeof candidate !== 'string' || !candidate.startsWith('/') || candidate.startsWith('//')) return '/dashboard'
  try {
    const target = new URL(candidate, window.location.origin)
    if (target.origin !== window.location.origin || target.pathname.startsWith('/\\')) return '/dashboard'
    return `${target.pathname}${target.search}${target.hash}`
  } catch {
    return '/dashboard'
  }
}

export default function LoginPage() {
  const { login } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const emailRef = useRef(null)
  const passwordRef = useRef(null)
  const [values, setValues] = useState({ email: '', password: '' })
  const [errors, setErrors] = useState({})
  const [message, setMessage] = useState(location.state?.registrationSuccess ?? location.state?.logoutWarning ?? '')
  const [messageRole, setMessageRole] = useState(location.state?.logoutWarning ? 'alert' : 'status')
  const [isSubmitting, setIsSubmitting] = useState(false)

  function updateField(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: value }))
    setErrors((current) => ({ ...current, [name]: '' }))
    setMessage('')
  }

  async function handleSubmit(event) {
    event.preventDefault()
    if (isSubmitting) return

    const nextErrors = {}
    const email = values.email.trim()
    if (!email) nextErrors.email = 'Enter your email address.'
    else if (!isValidEmail(email)) nextErrors.email = 'Enter a valid email address.'
    if (!values.password) nextErrors.password = 'Enter your password.'

    setErrors(nextErrors)
    setMessage('')
    if (Object.keys(nextErrors).length > 0) {
      if (nextErrors.email) emailRef.current?.focus()
      else passwordRef.current?.focus()
      return
    }

    setIsSubmitting(true)
    try {
      await login(email, values.password)
      navigate(safeDestination(location.state?.from), { replace: true })
    } catch (error) {
      const safeMessage = error instanceof ApiError
        ? error.message
        : 'Sign-in could not be completed. Please try again.'
      setMessage(safeMessage)
      setMessageRole('alert')
      passwordRef.current?.focus()
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <section className="auth-card" aria-labelledby="login-title">
      <p className="eyebrow">WELCOME BACK</p><h2 id="login-title">Sign in to SplitSmart</h2><p className="auth-card__description">Pick up where your group left off.</p>
      {message && <div className="auth-message" role={messageRole} tabIndex="-1">{message}</div>}
      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <FormField id="email" label="Email address" type="email" autoComplete="email" placeholder="you@example.com" value={values.email} onChange={updateField} error={errors.email} inputRef={emailRef} maxLength={255} />
        <FormField id="password" label="Password" type="password" autoComplete="current-password" placeholder="Enter your password" value={values.password} onChange={updateField} error={errors.password} inputRef={passwordRef} />
        <Button type="submit" disabled={isSubmitting} className="auth-submit" aria-busy={isSubmitting}>
          {isSubmitting ? 'Signing in…' : 'Sign in'} <span aria-hidden="true">→</span>
        </Button>
      </form>
      <p className="auth-switch">New to SplitSmart? <Link to="/register">Create an account</Link></p>
    </section>
  )
}
