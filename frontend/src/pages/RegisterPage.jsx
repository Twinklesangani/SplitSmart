import { useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Button, FormField } from '../components/UI.jsx'
import { ApiError, apiRequest } from '../services/apiClient.js'

function isValidEmail(email) {
  return email.length <= 255 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

function validate(values) {
  const errors = {}
  const name = values.name.trim()
  const email = values.email.trim()
  if (!name) errors.name = 'Enter your full name.'
  else if (name.length > 120) errors.name = 'Name must be 120 characters or fewer.'
  if (!email) errors.email = 'Enter your email address.'
  else if (!isValidEmail(email)) errors.email = 'Enter a valid email address.'
  if (!values.password) errors.password = 'Enter a password.'
  else if (values.password.length < 8) errors.password = 'Use at least 8 characters.'
  else if (new TextEncoder().encode(values.password).length > 72) errors.password = 'Password must be no more than 72 UTF-8 bytes.'
  return errors
}

export default function RegisterPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const nameRef = useRef(null)
  const emailRef = useRef(null)
  const passwordRef = useRef(null)
  const inputRefs = { name: nameRef, email: emailRef, password: passwordRef }
  const [values, setValues] = useState({ name: '', email: '', password: '' })
  const [errors, setErrors] = useState({})
  const [message, setMessage] = useState('')
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

    const nextErrors = validate(values)
    setErrors(nextErrors)
    setMessage('')
    if (Object.keys(nextErrors).length > 0) {
      const firstInvalidField = ['name', 'email', 'password'].find((field) => nextErrors[field])
      inputRefs[firstInvalidField]?.current?.focus()
      return
    }

    setIsSubmitting(true)
    try {
      const response = await apiRequest('/api/auth/register', {
        method: 'POST',
        body: {
          name: values.name.trim(),
          email: values.email.trim().toLowerCase(),
          password: values.password,
        },
      })
      if (!response
        || !Number.isFinite(Number(response.id))
        || Number(response.id) <= 0
        || typeof response.name !== 'string'
        || typeof response.email !== 'string') {
        throw new ApiError('The service returned an unexpected registration response. Please try again.', { code: 'invalid_response' })
      }
      navigate('/login', {
        replace: true,
        state: { registrationSuccess: 'Your account is ready. Sign in to continue.', from: location.state?.from },
      })
    } catch (error) {
      setMessage(error instanceof ApiError
        ? error.message
        : 'Registration could not be completed. Please try again.')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <section className="auth-card" aria-labelledby="register-title">
      <p className="eyebrow">MAKE ROOM FOR MORE</p><h2 id="register-title">Create your account</h2><p className="auth-card__description">A little more clarity for every shared plan.</p>
      {message && <div className="auth-message" role="alert" tabIndex="-1">{message}</div>}
      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <FormField id="name" label="Full name" autoComplete="name" placeholder="Your name" value={values.name} onChange={updateField} error={errors.name} inputRef={nameRef} maxLength={120} />
        <FormField id="email" label="Email address" type="email" autoComplete="email" placeholder="you@example.com" value={values.email} onChange={updateField} error={errors.email} inputRef={emailRef} maxLength={255} />
        <FormField
          id="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          placeholder="Create a password"
          value={values.password}
          onChange={updateField}
          error={errors.password}
          hint="At least 8 characters and no more than 72 UTF-8 bytes."
          inputRef={passwordRef}
        />
        <Button type="submit" disabled={isSubmitting} className="auth-submit" aria-busy={isSubmitting}>
          {isSubmitting ? 'Creating account…' : 'Create account'} <span aria-hidden="true">→</span>
        </Button>
      </form>
      <p className="auth-switch">Already have an account? <Link to="/login">Sign in</Link></p>
    </section>
  )
}
