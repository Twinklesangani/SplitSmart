import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { apiRequest } from '../services/apiClient.js'
import { getInvitationToken, getVerificationToken, clearInvitationToken, clearVerificationToken } from '../services/recoveryLink.js'

export default function InvitationLinkPage({ verify = false }) {
  const { status } = useAuth()
  const navigate = useNavigate()
  const [token] = useState(verify ? getVerificationToken : getInvitationToken)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const path = verify ? '/verify-email' : '/join'
  async function accept() {
    if (busy) return
    setBusy(true)
    try {
      const result = await apiRequest(verify ? '/api/auth/verify-email' : '/api/invitations/link-accept', { method: 'POST', body: { token } })
      if (verify) { clearVerificationToken(); setDone(true) }
      else { clearInvitationToken(); navigate(`/groups/${result.groupId}`, { replace: true }) }
    } catch (error) { setMessage(error.status === 404 || error.status === 409 || error.status === 400 ? 'This link is expired, revoked, used, or unavailable for this account.' : error.message) }
    finally { setBusy(false) }
  }
  return <section className="auth-card recovery-card">
    <h2>{done ? 'Email verified' : verify ? 'Verify your email' : 'Join a shared group'}</h2>
    <p>{verify ? 'Confirm mailbox ownership to accept email invitations.' : 'Only accept a private link from someone you trust. Joining shares your name and email with group members.'}</p>
    {!token && <p role="alert">Open the complete link from your message. If it has expired or been used, request a new one.</p>}
    {message && <p role="alert">{message}</p>}
    {token && !done && (status === 'authenticated'
      ? <Button disabled={busy} aria-busy={busy} onClick={accept}>{busy ? 'Confirming…' : verify ? 'Confirm email ownership' : 'Accept and join group'}</Button>
      : <><Link className="button button--primary" to="/login" state={{ from: path }}>Sign in to continue</Link><p>New here? <Link to="/register" state={{ from: path }}>Create an account</Link>, then return to this link. Keep this tab open.</p></>)}
    <p><Link to="/groups">Back to My groups</Link></p>
  </section>
}
