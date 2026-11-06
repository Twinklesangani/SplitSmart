
import { Link, NavLink, useParams } from 'react-router-dom'
import Icon from '../components/Icon.jsx'
import { Button, Card, EmptyState, PageHeader } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { useGroupData } from './groupData.js'
import { InviteForm } from './Invitations.jsx'
import GroupManagement from './GroupManagement.jsx'

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
      {group.archived && <p className="inline-notice" role="status">Archived group · Financial history is read-only.</p>}
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
  const { group, members } = data
  const isCreator = String(group.createdByUserId) === String(user.id)

  return (
    <div className="group-overview-grid">
      <GroupManagement group={group} members={members} user={user} reload={data.reload} />
      {isCreator && !group.archived && <InviteForm groupId={group.id} />}
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
        </div>
        {members.length > 0
          ? <ul className="member-list">{members.map((member) => <MemberCard key={member.userId} member={member} isCreator={String(member.userId) === String(group.createdByUserId)} />)}</ul>
          : <EmptyState icon="group" title="No members are listed" description="The member list is currently empty." />}
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
