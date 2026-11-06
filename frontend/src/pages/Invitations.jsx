import { useEffect, useRef, useState } from 'react'
import { Button, Card, FormField } from '../components/UI.jsx'
import { apiRequest } from '../services/apiClient.js'
import { useRefreshOnReturn } from './useRefreshOnReturn.js'

export function InviteForm({ groupId }) {
  const [email, setEmail] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const [link, setLink] = useState('')
  const [sent, setSent] = useState([])
  const [links, setLinks] = useState([])
  async function manage() {
    try {
      const [emailInvites, privateLinks] = await Promise.all([
        apiRequest(`/api/groups/${groupId}/invitations`), apiRequest(`/api/groups/${groupId}/invite-links`),
      ])
      setSent(emailInvites); setLinks(privateLinks)
    } catch (error) { setMessage(error.message) }
  }
  async function createLink() {
    if (lock.current) return
    lock.current = true; setBusy(true); setMessage('')
    try {
      const created = await apiRequest(`/api/groups/${groupId}/invite-links`, { method: 'POST', expectedStatus: 201 })
      setLink(`${window.location.origin}/join#token=${created.token}`)
      setMessage('Private link created. It expires in seven days and can be accepted once. Share only with the person you want to join.')
      await manage()
    } catch (error) { setMessage(error.message) }
    finally { lock.current = false; setBusy(false) }
  }
  async function revoke(id, type) {
    if (lock.current) return
    lock.current = true; setBusy(true)
    try { await apiRequest(`/api/groups/${groupId}/${type}/${id}`, { method: 'DELETE' }); setLink(''); await manage() }
    catch (error) { setMessage(error.message) }
    finally { lock.current = false; setBusy(false) }
  }

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
      setMessage('Invitation created for seven days. Email delivery is not confirmed. The recipient must verify mailbox ownership and accept in My groups. Use a private link if email is unavailable.')
      setEmail('')
    } catch (error) {
      setMessage(error.message)
    } finally {
      lock.current = false
      setBusy(false)
    }
  }

  return <Card className="invite-card" id="invite-members">
    <h2>Invite a member</h2>
    <p>Recipients can register later. They join only after accepting in My groups.</p>
    <Button type="button" disabled={busy} onClick={createLink}>Create private invite link</Button>
    {link && <div className="invite-link-copy"><label htmlFor="private-invite-link">Private invitation link</label>
      <input id="private-invite-link" readOnly value={link} onFocus={event => event.target.select()} />
      <Button type="button" variant="secondary" onClick={async () => { try { await navigator.clipboard.writeText(link); setMessage('Link copied. Share privately.') } catch { setMessage('Copy is unavailable. Select and copy the link above.') } }}>Copy link</Button></div>}
    <form onSubmit={invite}>
      <FormField id="invite-email" label="Invitation email address" type="email" required
        maxLength={255} value={email} onChange={event => setEmail(event.target.value)} />
      <Button type="submit" disabled={busy}>Create invitation</Button>
    </form>
    <Button type="button" variant="secondary" disabled={busy} onClick={manage}>Manage invitations</Button>
    {[...sent.map(item => ({ ...item, type: 'invitations' })), ...links.map(item => ({ ...item, type: 'invite-links' }))].map(item => <article key={`${item.type}-${item.id}`}>
      <p>{item.email || 'Private link'} · {item.status} · Expires {new Date(item.expiresAt).toLocaleString()}</p>
      {item.status === 'PENDING' && <Button type="button" disabled={busy} variant="secondary" onClick={() => revoke(item.id, item.type)}>Revoke invitation</Button>}
    </article>)}
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
  const [loadedScope, setLoadedScope] = useState('')
  const scope = `${userId}:${attempt}`
  const loading = open && loadedScope !== scope
  async function verifyEmail() {
    if (lock.current) return
    lock.current = true; setBusy(true)
    try { await apiRequest('/api/auth/email-verification', { method: 'POST' }); setMessage('Verification requested. Delivery is not confirmed; if a message arrives, open it in this signed-in tab.') }
    catch (error) { setMessage(error.message) }
    finally { lock.current = false; setBusy(false) }
  }

  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    apiRequest('/api/invitations', { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) setInvitations(value)
    }).catch(error => {
      if (!controller.signal.aborted) setMessage(error.message)
    }).finally(() => { if (!controller.signal.aborted) setLoadedScope(scope) })
    return () => controller.abort()
  }, [scope, open])

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
    <p>Email invitations require mailbox verification. Private group links do not require email delivery.</p>
    <Button type="button" variant="secondary" disabled={busy} onClick={verifyEmail}>Verify my email</Button>
    {loading && <p role="status">Loading invitations…</p>}
    {open && !loading && !message && invitations.length === 0 && <p>No pending invitations.</p>}
    {invitations.map(invitation => <article key={invitation.id}>
      <h3>{invitation.groupName}</h3>
      <p>Expires {new Date(invitation.expiresAt).toLocaleString()}</p>
      <Button type="button" disabled={busy} onClick={() => respond(invitation.id, 'accept')}>Accept invitation</Button>
      <Button type="button" variant="secondary" disabled={busy} onClick={() => respond(invitation.id, 'decline')}>Decline invitation</Button>
    </article>)}
    {message && <p role="status">{message}</p>}
  </Card>
}
