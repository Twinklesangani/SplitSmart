import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useParams } from 'react-router-dom'
import Icon from '../components/Icon.jsx'
import { Button, Card, EmptyState, FormField, PageHeader } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { ApiError } from '../services/apiClient.js'
import { addGroupMember, listGroupMembers } from '../services/groupService.js'
import { useGroupData } from './groupData.js'

const tabs = [
  { path: '', label: 'Overview', icon: 'home', end: true },
  { path: '/expenses', label: 'Expenses', icon: 'receipt' },
  { path: '/balances', label: 'Balances', icon: 'wallet' },
  { path: '/settlements', label: 'Settlements', icon: 'arrowLeft' },
]

export function GroupPageLayout({ group, activeTitle, eyebrow, description, children }) {
  const { groupId } = useParams()
  const base = `/groups/${groupId}`
  return (
    <>
      <div className="breadcrumbs">
        <Link to="/groups">My groups</Link>
        <Icon name="arrowRight" size={13} />
        <span>{group.name}</span>
      </div>
      <div className="group-detail-heading">
        <span className="group-avatar group-avatar--large group-avatar--lavender"><Icon name="group" size={22} /></span>
        <PageHeader
          eyebrow={eyebrow ?? 'GROUP SPACE'}
          title={activeTitle ?? group.name}
          description={description ?? `Group ID ${group.id}`}
        />
      </div>
      <nav className="group-tabs" aria-label="Group sections">
        {tabs.map((tab) => (
          <NavLink
            key={tab.path || 'overview'}
            to={`${base}${tab.path}`}
            end={tab.end}
            className={({ isActive }) => `group-tab${isActive ? ' is-active' : ''}`}
          >
            <Icon name={tab.icon} size={17} /><span>{tab.label}</span>
          </NavLink>
        ))}
      </nav>
      <div className="group-page-content">{children}</div>
    </>
  )
}

export function GroupUnavailable({ data }) {
  if (data.status === 'loading') {
    return <Card><p className="loading-state" role="status">Loading group details…</p></Card>
  }

  if (data.status === 'not-found') {
    return (
      <>
        <PageHeader eyebrow="GROUP SPACE" title="Group unavailable" description="This group may not exist, or you may not have access to it." />
        <Card><EmptyState icon="group" title="Group not found or unavailable" description="Only people who belong to a group can view its details and members." action={<Link className="text-link" to="/groups">Back to My groups <Icon name="arrowRight" size={16} /></Link>} /></Card>
      </>
    )
  }

  return (
    <>
      <PageHeader eyebrow="GROUP SPACE" title="Group details unavailable" description="We could not load this group right now." />
      <Card>
        <div className="request-error" role="alert">
          <p>{data.error}</p>
          <Button type="button" variant="secondary" onClick={data.reload}>Try again</Button>
          <Link className="text-link" to="/groups">Back to My groups <Icon name="arrowRight" size={16} /></Link>
        </div>
      </Card>
    </>
  )
}

function MemberCard({ member, isCreator }) {
  const initials = member.name.trim().split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase()
  return (
    <li className="member-card">
      <span className="member-avatar" aria-hidden="true">{initials || '?'}</span>
      <span className="member-card__identity">
        <strong>{member.name}</strong>
        <span>{member.email}</span>
      </span>
      {isCreator && <span className="member-role">Creator</span>}
    </li>
  )
}

function GroupOverviewContent({ data, user }) {
  const { group, members, updateMembers } = data
  const [memberFormOpen, setMemberFormOpen] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [memberEmail, setMemberEmail] = useState('')
  const [memberError, setMemberError] = useState('')
  const [memberNotice, setMemberNotice] = useState('')
  const [refreshWarning, setRefreshWarning] = useState('')
  const addLock = useRef(false)
  const addRequest = useRef(null)
  const emailInput = useRef(null)
  const isCreator = String(group.createdByUserId) === String(user.id)
  const scope = `${user.id}:${group.id}`

  useEffect(() => () => addRequest.current?.abort(), [scope])

  function openMemberForm() {
    setMemberError('')
    setMemberNotice('')
    setRefreshWarning('')
    setMemberEmail('')
    setMemberFormOpen(true)
    window.setTimeout(() => emailInput.current?.focus(), 0)
  }

  async function handleAddMember(event) {
    event.preventDefault()
    if (addLock.current) return
    const email = memberEmail.trim().toLowerCase()
    if (!email || email.length > 255 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      setMemberError('Enter a valid email address for an existing SplitSmart account.')
      emailInput.current?.focus()
      return
    }

    const controller = new AbortController()
    addRequest.current?.abort()
    addRequest.current = controller
    addLock.current = true
    setIsSubmitting(true)
    setMemberError('')
    setMemberNotice('')
    setRefreshWarning('')
    try {
      const addedMember = await addGroupMember(group.id, email, { signal: controller.signal })
      if (controller.signal.aborted) return
      updateMembers([...members, addedMember])
      setMemberNotice(`${addedMember.name} was added to the group.`)
      setMemberEmail('')
      setMemberFormOpen(false)
      try {
        updateMembers(await listGroupMembers(group.id, { signal: controller.signal }))
      } catch (error) {
        if (controller.signal.aborted) return
        setRefreshWarning(error instanceof ApiError ? error.message : 'The member was added, but the list could not be refreshed.')
      }
    } catch (error) {
      if (controller.signal.aborted) return
      setMemberError(error instanceof ApiError
        ? error.message
        : 'The member could not be added. Please try again.')
    } finally {
      if (addRequest.current === controller) addRequest.current = null
      addLock.current = false
      if (!controller.signal.aborted) setIsSubmitting(false)
    }
  }

  return (
    <div className="group-overview-grid">
      <Card className="group-overview-welcome">
        <span className="welcome-icon"><Icon name="spark" size={21} /></span>
        <p className="eyebrow">GROUP SPACE</p>
        <h2>{group.name}</h2>
        <p>Group ID {group.id}. Group membership and shared information are available to members only.</p>
      </Card>
      <Card className="group-meta-card">
        <div className="section-heading">
          <div><h2>Group information</h2><p>Verified group details</p></div>
        </div>
        <div className="meta-line"><span>Group ID</span><strong>{group.id}</strong></div>
        <div className="meta-line"><span>Members</span><strong>{members.length}</strong></div>
        <div className="meta-line"><span>Creator</span><strong>{members.find((member) => String(member.userId) === String(group.createdByUserId))?.name ?? 'Group creator'}</strong></div>
      </Card>
      <Card className="group-members-card">
        <div className="section-heading">
          <div><h2>Members</h2><p>{members.length} {members.length === 1 ? 'person' : 'people'} in this group</p></div>
          {isCreator && <Button type="button" icon="plus" onClick={openMemberForm}>Add member</Button>}
        </div>
        {members.length > 0
          ? <ul className="member-list">{members.map((member) => <MemberCard key={member.userId} member={member} isCreator={String(member.userId) === String(group.createdByUserId)} />)}</ul>
          : <EmptyState icon="group" title="No members are listed" description="The member list is currently empty." />}
        {memberNotice && <p className="inline-notice" role="status">{memberNotice}</p>}
        {refreshWarning && <p className="request-warning" role="status">{refreshWarning}</p>}
        {memberFormOpen && (
          <form className="member-form" aria-labelledby="add-member-title" onSubmit={handleAddMember} noValidate>
            <h3 id="add-member-title">Add an existing member</h3>
            <p>They must already have a SplitSmart account. We use their registered email; no user search or invitation is performed.</p>
            <FormField
              id="member-email"
              label="Account email address"
              type="email"
              autoComplete="email"
              placeholder="person@example.com"
              value={memberEmail}
              onChange={(event) => { setMemberEmail(event.target.value); setMemberError('') }}
              error={memberError}
              inputRef={emailInput}
              maxLength={255}
            />
            <div className="group-form__actions">
              <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting}>
                {isSubmitting ? 'Adding…' : 'Add member'}
              </Button>
              <Button type="button" variant="secondary" disabled={isSubmitting} onClick={() => { setMemberFormOpen(false); setMemberError('') }}>Cancel</Button>
            </div>
          </form>
        )}
      </Card>
      <Card className="group-overview-activity">
        <EmptyState icon="activity" title="Ready to review group expenses?" description="Open this group's real expense list or add a new shared expense." action={<Link className="text-link" to={`/groups/${group.id}/expenses`}>View expenses <Icon name="arrowRight" size={16} /></Link>} />
      </Card>
    </div>
  )
}

export function GroupOverviewPage() {
  const data = useGroupData()
  const { user } = useAuth()
  if (data.status !== 'success') return <GroupUnavailable data={data} />

  return (
    <GroupPageLayout group={data.group}>
      <GroupOverviewContent key={`${user.id}:${data.group.id}`} data={data} user={user} />
    </GroupPageLayout>
  )
}
