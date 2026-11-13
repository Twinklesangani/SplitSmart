import { Link } from 'react-router-dom'
import { Card, PageHeader } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { recoveryEmailEnabled } from '../services/emailAvailability.js'

export default function SettingsPage() {
  const { user } = useAuth()
  const recoveryAvailable = recoveryEmailEnabled()
  return <>
    <PageHeader eyebrow="YOUR ACCOUNT" title="Settings" description="Your profile and account security." />
    <Card>
      <h2>Profile</h2>
      <dl>
        <div className="meta-line"><dt>Name</dt><dd>{user.name}</dd></div>
        <div className="meta-line"><dt>Email address</dt><dd className="settings-email">{user.email}</dd></div>
      </dl>
      <p>Profile editing is not available yet.</p>
    </Card>
    <Card>
      <h2>Password and security</h2>
      <p>{recoveryAvailable ? 'Request a secure email link to reset your password.' : 'Email password recovery is temporarily unavailable. You can continue using your current password.'}</p>
      <Link className="text-link" to="/forgot-password">{recoveryAvailable ? 'Reset password' : 'View recovery availability'}</Link>
      <p>After a successful password reset, existing sign-in sessions are invalidated.</p>
      <p>On a shared device, use Sign out in the navigation when you finish. Never share your password or a recovery link.</p>
    </Card>
  </>
}
