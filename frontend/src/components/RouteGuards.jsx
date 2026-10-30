import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../context/useAuth.js'

function SessionMessage({ title, message, retry }) {
  return (
    <main className="session-state" aria-live="polite">
      <div className="session-state__card">
        <p className="eyebrow">SPLITSMART ACCOUNT</p>
        <h1>{title}</h1>
        <p>{message}</p>
        {retry && <button className="button button--primary" type="button" onClick={retry}>Try again</button>}
      </div>
    </main>
  )
}

export function ProtectedRoute() {
  const { status, error, retrySession } = useAuth()
  const location = useLocation()
  if (status === 'restoring') {
    return <SessionMessage title="Checking your session" message="Please wait while we verify your sign-in." />
  }
  if (status === 'unavailable') {
    return <SessionMessage title="We couldn’t verify your session" message={error ?? 'Check your connection and try again.'} retry={retrySession} />
  }
  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}${location.hash}` }} />
  }
  return <Outlet />
}

export function PublicOnlyRoute() {
  const { status, retrySession } = useAuth()
  const location = useLocation()
  if (status === 'restoring') {
    return <SessionMessage title="Checking your session" message="Please wait while we verify your sign-in." />
  }
  if (status === 'unavailable') {
    return <SessionMessage title="We couldn’t verify your session" message="Check your connection before signing in again." retry={retrySession} />
  }
  if (status === 'authenticated') {
    const destination = location.state?.from
    const safeDestination = typeof destination === 'string'
      && destination.startsWith('/')
      && !destination.startsWith('//')
      && !destination.startsWith('/\\')
      ? destination
      : '/dashboard'
    return <Navigate to={safeDestination} replace />
  }
  return <Outlet />
}
