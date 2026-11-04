import { useEffect, useRef, useState } from 'react'
import { Button, Card, FormField } from '../components/UI.jsx'
import { apiRequest } from '../services/apiClient.js'
import { useRefreshOnReturn } from './useRefreshOnReturn.js'

export function InviteForm({ groupId }) {
  const [email, setEmail] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)

  async function invite(event) {
    event.preventDefault()
    if (lock.current) return
    lock.current = true
    setBusy(true)
    setMessage('')
    try {
      await apiRequest(`/api/groups/${groupId}/invitations`, {
        method: 'POST', body: { email: email.trim().toLowerCase() },
      })
      setMessage('Invitation created for seven days. No email was sent. Ask the recipient to sign in or register with this email, then open My groups to accept or decline.')
      setEmail('')
    } catch (error) {
      setMessage(error.message)
    } finally {
      lock.current = false
      setBusy(false)
    }
  }

  return <Card className="invite-card">
    <h2>Invite a member</h2>
    <p>Recipients can register later. They join only after accepting in My groups.</p>
    <form onSubmit={invite}>
      <FormField id="invite-email" label="Invitation email address" type="email" required
        maxLength={255} value={email} onChange={event => setEmail(event.target.value)} />
      <Button type="submit" disabled={busy}>Create invitation</Button>
    </form>
    {message && <p role="status">{message}</p>}
  </Card>
}

export function InvitationInbox({ userId, onAccepted }) {
  const [open, setOpen] = useState(false)
  const [invitations, setInvitations] = useState([])
  const [message, setMessage] = useState('')
  const [attempt, setAttempt] = useState(0)
  useRefreshOnReturn(setAttempt)
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)

  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    apiRequest('/api/invitations', { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) setInvitations(value)
    }).catch(error => {
      if (!controller.signal.aborted) setMessage(error.message)
    })
    return () => controller.abort()
  }, [userId, attempt, open])

  async function respond(id, decision) {
    if (lock.current) return
    lock.current = true
    setBusy(true)
    setMessage('')
    try {
      await apiRequest(`/api/invitations/${id}/${decision}`, { method: 'POST' })
      setInvitations(previous => previous.filter(invitation => invitation.id !== id))
      if (decision === 'accept') onAccepted()
      setMessage(decision === 'accept' ? 'Invitation accepted.' : 'Invitation declined.')
    } catch (error) {
      setMessage(error.message)
      setAttempt(value => value + 1)
    } finally {
      lock.current = false
      setBusy(false)
    }
  }

  return <Card className="invitation-inbox-card">
    <h2>Group invitations</h2>
    <Button type="button" variant="secondary" onClick={() => { setOpen(true); setAttempt(value => value + 1) }}>Check invitations</Button>
    {open && invitations.length === 0 && <p>No pending invitations.</p>}
    {invitations.map(invitation => <article key={invitation.id}>
      <h3>{invitation.groupName}</h3>
      <p>Expires {new Date(invitation.expiresAt).toLocaleString()}</p>
      <Button type="button" disabled={busy} onClick={() => respond(invitation.id, 'accept')}>Accept invitation</Button>
      <Button type="button" variant="secondary" disabled={busy} onClick={() => respond(invitation.id, 'decline')}>Decline invitation</Button>
    </article>)}
    {message && <p role="status">{message}</p>}
  </Card>
}
