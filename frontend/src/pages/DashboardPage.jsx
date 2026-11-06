import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button, Card, EmptyState, GroupCard, PageHeader } from '../components/UI.jsx'
import ExpenseMetadata from '../components/ExpenseMetadata.jsx'
import { useAuth } from '../context/useAuth.js'
import { ApiError, apiRequest } from '../services/apiClient.js'
import { formatFinancialInr, parseFinancialInrPaise } from '../services/financeService.js'
import { useRefreshOnReturn } from './useRefreshOnReturn.js'

function decimalFromPaise(paise) {
  const absolute = paise < 0n ? -paise : paise
  return `${paise < 0n ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`
}

export default function DashboardPage() {
  const { user } = useAuth()
  const [data, setData] = useState({ scope: '', status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [choice, setChoice] = useState('')
  useRefreshOnReturn(setAttempt)
  const scope = `${user.id}:${attempt}`
  useEffect(() => {
    const controller = new AbortController()
    apiRequest('/api/dashboard', { signal: controller.signal }).then(value => {
      if (!value || !Array.isArray(value.groups) || !Array.isArray(value.activity)
        || value.groups.some(group => !group || typeof group.name !== 'string'
          || !Number.isSafeInteger(Number(group.id)) || Number(group.id) <= 0)
        || ['totalExpenses', 'owed', 'receivable'].some(key => parseFinancialInrPaise(value[key]) === null || parseFinancialInrPaise(value[key]) < 0n)
        || value.netPosition !== undefined && parseFinancialInrPaise(value.netPosition) !== parseFinancialInrPaise(value.receivable) - parseFinancialInrPaise(value.owed)
        || value.groupBalances !== undefined && (!Array.isArray(value.groupBalances)
          || value.groupBalances.length !== value.groups.length
          || new Set(value.groupBalances.map(group => String(group?.groupId))).size !== value.groups.length
          || value.groupBalances.some(group => !group || !value.groups.some(item => String(item.id) === String(group.groupId)) || parseFinancialInrPaise(group.netBalance) === null))
        || value.activity.some(item => !item || !['EXPENSE', 'SETTLEMENT'].includes(item.type)
          || typeof item.description !== 'string' || !Number.isSafeInteger(Number(item.id)) || Number(item.id) <= 0
          || !Number.isSafeInteger(Number(item.groupId)) || Number(item.groupId) <= 0
          || parseFinancialInrPaise(item.amount) === null
          || item.expenseDate && !Number.isFinite(Date.parse(`${item.expenseDate}T12:00:00Z`)))) {
        throw new ApiError('The service returned unexpected dashboard data. Please refresh and try again.', { code: 'invalid_response' })
      }
      if (!controller.signal.aborted) setData({ ...value, scope, status: 'success' })
    }).catch(error => {
      if (!controller.signal.aborted) setData({ scope, status: 'error', error: error instanceof ApiError ? error.message : 'Your financial overview could not be loaded. Please try again.' })
    })
    return () => controller.abort()
  }, [scope])
  const current = data.scope === scope ? data : { status: 'loading' }
  const ready = current.status === 'success'
  const groups = ready ? current.groups : []
  const selected = groups.find(group => String(group.id) === choice) || groups[0]
  const net = ready ? parseFinancialInrPaise(current.receivable) - parseFinancialInrPaise(current.owed) : null
  const metrics = [
    ['Group expenses', ready ? formatFinancialInr(current.totalExpenses) : '—', 'All expenses across your active groups, counted once per group.'],
    ['You owe', ready ? formatFinancialInr(current.owed) : '—', 'Outstanding debt summed across groups.'],
    ['You are owed', ready ? formatFinancialInr(current.receivable) : '—', 'Outstanding receivables summed across groups.'],
    ['Net position', ready ? formatFinancialInr(decimalFromPaise(net)) : '—', 'Receivable minus owed. Settle each group separately.'],
  ]
  return <>
    <PageHeader eyebrow="YOUR ACCOUNT" title={`Welcome, ${user.name.trim().split(/\s+/)[0]}`}
      description="Your shared spending, with every balance accounted for." />
    <nav className="dashboard-actions" aria-label="Quick actions">
      <Button to="/groups?create=1">Create group</Button>
      {groups.length > 0 && <div className="dashboard-expense-action">
        <label htmlFor="expense-group">Add an expense in</label>
        <select id="expense-group" value={String(selected.id)} onChange={event => setChoice(event.target.value)}>{groups.map(group => <option key={group.id} value={group.id}>{group.name}</option>)}</select>
        <Button variant="secondary" to={`/groups/${selected.id}/expenses/new`}>Add expense</Button>
      </div>}
    </nav>
    {current.status === 'loading' && <p role="status">Loading your financial overview…</p>}
    {current.status === 'error' && <Card><div role="alert"><p>{current.error}</p>
      <Button onClick={() => setAttempt(value => value + 1)}>Try again</Button></div></Card>}
    <dl className="dashboard-ledger">{metrics.map(([label, value, detail]) => <div key={label}>
      <dt>{label}</dt><dd>{value}</dd><p>{detail}</p>
    </div>)}</dl>
    {ready && groups.length === 0 && <Card className="onboarding-card"><h2>Start sharing, one group at a time</h2>
      <ol><li><strong>Create your group.</strong> Give your trip, household or plan a name.</li><li><strong>Invite your people.</strong> Share a private link; they choose whether to join.</li><li><strong>Add the first expense.</strong> Choose who paid and how to split it.</li></ol>
      <Link className="text-link" to="/groups?create=1">Create your first group</Link><p>Already have archived groups? <Link to="/groups">Review all groups</Link>.</p>
    </Card>}
    <div className="dashboard-grid">
      <Card><div className="section-heading"><div><h2>Recent activity</h2><p>Latest recorded expenses and repayments in active groups</p></div></div>
        {ready && current.activity.length === 0 && <EmptyState icon="activity" title="No activity yet" description="Add a shared expense to start your group's ledger." />}
        {ready && <ol className="activity-ledger">{current.activity.map(item => <li key={`${item.type}:${item.id}`}>
          <div><span className="eyebrow">{item.type === 'SETTLEMENT' ? 'REPAYMENT' : item.groupName}</span>
            <Link to={item.type === 'EXPENSE' ? `/groups/${item.groupId}/expenses/${item.id}` : `/groups/${item.groupId}/settlements`}>{item.description}</Link>
            {item.type === 'EXPENSE' && <ExpenseMetadata expense={item} />}
            {item.type === 'SETTLEMENT' && <time dateTime={item.recordedAt}>{item.recordedAt?.slice(0, 10)}</time>}
            {item.type === 'EXPENSE' && item.recordedAt && <time dateTime={item.recordedAt}>Added {new Date(item.recordedAt.endsWith('Z') ? item.recordedAt : `${item.recordedAt}Z`).toLocaleString()}</time>}
          </div><strong>{formatFinancialInr(item.amount)}</strong>
        </li>)}</ol>}
      </Card>
      <Card><div className="section-heading"><h2>Your groups {ready && <span className="dashboard-group-count">({groups.length} active)</span>}</h2><Link to="/groups">All groups</Link></div>
        {ready && groups.length === 0 && <EmptyState icon="group" title="No groups yet" description="Create your first group to share plans with people you know." action={<Link to="/groups">Create a group</Link>} />}
        <div className="group-list">{groups.slice(0, 4).map(group => {
          const position = current.groupBalances?.find(item => String(item.groupId) === String(group.id))
          const amount = position ? parseFinancialInrPaise(position.netBalance) : null
          return <div className="dashboard-group-position" key={group.id}><GroupCard group={group} />
            {amount !== null && <p>{amount === 0n ? 'Settled in this group' : `${amount < 0n ? 'You owe' : 'You are owed'} ${formatFinancialInr(decimalFromPaise(amount < 0n ? -amount : amount))}`}</p>}
            <Link className="text-link" to={`/groups/${group.id}/balances`}>Balances & settlement suggestions</Link></div>
        })}</div>
      </Card>
    </div>
  </>
}
