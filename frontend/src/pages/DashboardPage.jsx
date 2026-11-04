import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button, Card, EmptyState, GroupCard, PageHeader } from '../components/UI.jsx'
import ExpenseMetadata from '../components/ExpenseMetadata.jsx'
import { useAuth } from '../context/useAuth.js'
import { ApiError, apiRequest } from '../services/apiClient.js'
import { formatFinancialInr, parseFinancialInrPaise } from '../services/financeService.js'
import { useRefreshOnReturn } from './useRefreshOnReturn.js'

export default function DashboardPage() {
  const { user } = useAuth()
  const [data, setData] = useState({ scope: '', status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  useRefreshOnReturn(setAttempt)
  const scope = `${user.id}:${attempt}`
  useEffect(() => {
    const controller = new AbortController()
    apiRequest('/api/dashboard', { signal: controller.signal }).then(value => {
      if (!value || !Array.isArray(value.groups) || !Array.isArray(value.activity)
        || value.groups.some(group => !group || typeof group.name !== 'string'
          || !Number.isSafeInteger(Number(group.id)) || Number(group.id) <= 0)
        || ['totalExpenses', 'owed', 'receivable'].some(key => parseFinancialInrPaise(value[key]) === null)
        || value.activity.some(item => !item || !['EXPENSE', 'SETTLEMENT'].includes(item.type)
          || typeof item.description !== 'string' || !Number.isSafeInteger(Number(item.id)) || Number(item.id) <= 0
          || !Number.isSafeInteger(Number(item.groupId)) || Number(item.groupId) <= 0
          || parseFinancialInrPaise(item.amount) === null
          || item.expenseDate && !Number.isFinite(Date.parse(`${item.expenseDate}T12:00:00Z`)))) {
        throw new ApiError('The service returned unexpected dashboard data. Please refresh and try again.', { code: 'invalid_response' })
      }
      if (!controller.signal.aborted) setData({ ...value, scope, status: 'success' })
    }).catch(error => {
      if (!controller.signal.aborted) setData({ scope, status: 'error', error: error.message })
    })
    return () => controller.abort()
  }, [scope])
  const current = data.scope === scope ? data : { status: 'loading' }
  const ready = current.status === 'success'
  const groups = ready ? current.groups : []
  const metrics = [
    ['Group expenses', ready ? formatFinancialInr(current.totalExpenses) : '—', 'All expenses across your active groups, counted once per group.'],
    ['You owe', ready ? formatFinancialInr(current.owed) : '—', 'Outstanding debt summed across groups.'],
    ['You are owed', ready ? formatFinancialInr(current.receivable) : '—', 'Outstanding receivables summed across groups.'],
    ['Active groups', ready ? String(groups.length) : '—', 'Current memberships, excluding archived groups.'],
  ]
  return <>
    <PageHeader eyebrow="YOUR ACCOUNT" title={`Welcome, ${user.name.trim().split(/\s+/)[0]}`}
      description="Your shared spending, with every balance accounted for." />
    <nav className="dashboard-actions" aria-label="Quick actions">
      <Button to="/groups">Manage groups</Button>
      {groups.length > 0 && <Button variant="secondary" to={`/groups/${groups[0].id}/expenses/new`}>Add expense</Button>}
    </nav>
    {current.status === 'loading' && <p role="status">Loading your financial overview…</p>}
    {current.status === 'error' && <Card><div role="alert"><p>{current.error}</p>
      <Button onClick={() => setAttempt(value => value + 1)}>Try again</Button></div></Card>}
    <dl className="dashboard-ledger">{metrics.map(([label, value, detail]) => <div key={label}>
      <dt>{label}</dt><dd>{value}</dd><p>{detail}</p>
    </div>)}</dl>
    <div className="dashboard-grid">
      <Card><div className="section-heading"><div><h2>Recent activity</h2><p>Latest recorded expenses and repayments in active groups</p></div></div>
        {ready && current.activity.length === 0 && <EmptyState icon="activity" title="No activity yet" description="Add a shared expense to start your group's ledger." />}
        {ready && <ol className="activity-ledger">{current.activity.map(item => <li key={`${item.type}:${item.id}`}>
          <div><span className="eyebrow">{item.type === 'SETTLEMENT' ? 'REPAYMENT' : item.groupName}</span>
            <Link to={item.type === 'EXPENSE' ? `/groups/${item.groupId}/expenses/${item.id}` : `/groups/${item.groupId}/settlements`}>{item.description}</Link>
            {item.type === 'EXPENSE' && <ExpenseMetadata expense={item} />}
            {item.type === 'SETTLEMENT' && <time dateTime={item.recordedAt}>{item.recordedAt?.slice(0, 10)}</time>}
          </div><strong>{formatFinancialInr(item.amount)}</strong>
        </li>)}</ol>}
      </Card>
      <Card><div className="section-heading"><h2>Your groups</h2><Link to="/groups">All groups</Link></div>
        {ready && groups.length === 0 && <EmptyState icon="group" title="No groups yet" description="Create your first group to share plans with people you know." action={<Link to="/groups">Create a group</Link>} />}
        <div className="group-list">{groups.slice(0, 4).map(group => <GroupCard key={group.id} group={group} />)}</div>
      </Card>
    </div>
  </>
}
