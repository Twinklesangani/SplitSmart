import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Card } from '../components/UI.jsx'
import { apiRequest } from '../services/apiClient.js'

export default function GroupManagement({ group, members, user, reload }) {
  const [pending, setPending] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const dialog = useRef(null)
  const lock = useRef(false)
  const navigate = useNavigate()
  const owner = String(group.createdByUserId) === String(user.id)
  function requestChange(change) { setError(''); setPending(change) }
  useEffect(() => {
    const element = dialog.current
    if (pending) element?.showModal()
    return () => { if (element?.open) element.close?.() }
  }, [pending])
  async function confirm() {
    if (lock.current) return
    lock.current = true
    setBusy(true)
    setError('')
    try {
      await apiRequest(pending.path, { method: pending.method })
      setPending(null)
      if (pending.leave) navigate('/groups', { replace: true })
      else reload()
    } catch (failure) {
      setError(failure.status === 409
        ? 'This change is blocked by group history or unsettled balances. Members in financial records are retained. The creator can archive once everyone is settled.'
        : failure.message)
    } finally { lock.current = false; setBusy(false) }
  }
  if (group.archived) return <Card><h2>Archived group</h2><p>This settled group is read-only. Your expense and repayment history is preserved.</p></Card>
  return <Card className="group-management">
    <h2>Manage group</h2>
    <p>Members can leave only with a zero balance and no financial history. The creator remains responsible for the group.</p>
    {!owner && <Button variant="secondary" onClick={() => requestChange({ label: 'Leave group', path: `/api/groups/${group.id}/leave`, method: 'POST', leave: true })}>Leave group</Button>}
    {owner && <>
      <ul className="management-member-list">{members.filter(member => String(member.userId) !== String(user.id)).map(member => <li key={member.userId}>
        <span>{member.name}</span><Button variant="secondary" aria-label={`Remove ${member.name}`} onClick={() => requestChange({ label: `Remove ${member.name}`, path: `/api/groups/${group.id}/members/${member.userId}`, method: 'DELETE' })}>Remove member</Button>
      </li>)}</ul>
      <p>Archive a settled group to keep its history available without adding new expenses.</p>
      <Button variant="secondary" onClick={() => requestChange({ label: 'Archive group', path: `/api/groups/${group.id}`, method: 'DELETE' })}>Archive group…</Button>
    </>}
    {pending && <dialog ref={dialog} className="management-dialog" aria-labelledby="management-title" onCancel={event => { if (busy) event.preventDefault(); else setPending(null) }}>
      <h2 id="management-title">{pending.label}?</h2><p>Confirm this change to {group.name}. The server will check permissions and financial obligations.</p>
      {error && <p role="alert">{error}</p>}
      <div className="group-form__actions"><Button variant="secondary" autoFocus disabled={busy} onClick={() => setPending(null)}>Cancel</Button>
        <Button variant="danger" disabled={busy} onClick={confirm}>{busy ? 'Checking…' : 'Confirm'}</Button></div>
    </dialog>}
  </Card>
}
