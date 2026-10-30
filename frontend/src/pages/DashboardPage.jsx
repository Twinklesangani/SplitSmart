import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from '../components/Icon.jsx'
import { Button, Card, EmptyState, GroupCard, PageHeader, SummaryCard } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { ApiError } from '../services/apiClient.js'
import { listGroups } from '../services/groupService.js'

export default function DashboardPage() {
  const { user } = useAuth()
  const [data, setData] = useState({ scope: '', status: 'loading', groups: [], error: '' })
  const [loadAttempt, setLoadAttempt] = useState(0)
  const scope = `${user.id}:${loadAttempt}`
  const firstName = user.name.trim().split(/\s+/)[0]

  useEffect(() => {
    const controller = new AbortController()
    listGroups({ signal: controller.signal }).then((groups) => {
      if (!controller.signal.aborted) setData({ scope, status: 'success', groups, error: '' })
    }).catch((error) => {
      if (controller.signal.aborted) return
      setData({
        scope,
        status: 'error',
        groups: [],
        error: error instanceof ApiError ? error.message : 'Group information could not be loaded. Please try again.',
      })
    })
    return () => controller.abort()
  }, [scope, loadAttempt])

  const visibleData = data.scope === scope
    ? data
    : { status: 'loading', groups: [], error: '' }
  const summaries = [
    { label: 'Total expenses', value: '—', detail: 'Available after expense integration', icon: 'receipt', tone: 'indigo' },
    { label: 'You owe', value: '—', detail: 'Available after balance integration', icon: 'arrowUp', tone: 'red' },
    { label: 'You are owed', value: '—', detail: 'Available after balance integration', icon: 'arrowDown', tone: 'green' },
    {
      label: 'Active groups',
      value: visibleData.status === 'success' ? String(visibleData.groups.length) : '—',
      detail: visibleData.status === 'success' ? 'Groups you belong to' : 'Loading group membership',
      icon: 'group',
      tone: 'teal',
    },
  ]

  return (
    <>
      <PageHeader eyebrow="YOUR ACCOUNT" title={`Welcome, ${firstName}`} description="Your personal expense overview will appear here as features become available." />
      <div className="summary-grid">{summaries.map((summary) => <SummaryCard key={summary.label} {...summary} />)}</div>
      <div className="dashboard-grid">
        <Card className="group-preview-card">
          <div className="section-heading"><div><h2>Recent activity</h2><p>Your shared expense activity</p></div></div>
          <EmptyState icon="activity" title="Activity will appear here" description="Recent expenses and updates will be available after expense integration." />
        </Card>
        <Card className="group-preview-card">
          <div className="section-heading">
            <div><h2>Your groups</h2><p>Shared spaces with your people</p></div>
            <Link className="text-link" to="/groups">All groups <Icon name="arrowRight" size={16} /></Link>
          </div>
          {visibleData.status === 'loading' && <p className="loading-state" role="status">Loading your groups…</p>}
          {visibleData.status === 'error' && (
            <div className="request-error request-error--compact" role="alert">
              <p>{visibleData.error}</p>
              <Button type="button" variant="secondary" onClick={() => setLoadAttempt((attempt) => attempt + 1)}>Try again</Button>
            </div>
          )}
          {visibleData.status === 'success' && visibleData.groups.length === 0 && (
            <EmptyState
              icon="group"
              title="No groups yet"
              description="Create your first group to share plans with people you know."
              action={<Link className="text-link" to="/groups">Create a group <Icon name="arrowRight" size={16} /></Link>}
            />
          )}
          {visibleData.status === 'success' && visibleData.groups.length > 0 && (
            <>
              <div className="group-list">
                {visibleData.groups.slice(0, 3).map((group) => <GroupCard key={group.id} group={group} />)}
              </div>
              {visibleData.groups.length > 3 && <Link className="text-link group-preview__more" to="/groups">View all {visibleData.groups.length} groups <Icon name="arrowRight" size={16} /></Link>}
            </>
          )}
        </Card>
      </div>
    </>
  )
}
