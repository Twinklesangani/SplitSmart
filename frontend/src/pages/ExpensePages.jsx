import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { Button, Card, EmptyState, PageHeader } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { ApiError } from '../services/apiClient.js'
import {
  createExpense,
  deleteExpense,
  formatInr,
  getExpense,
  listExpenses,
  normalizeMoney,
  parseMoneyToPaise,
  updateExpense,
} from '../services/expenseService.js'
import { GroupPageLayout, GroupUnavailable } from './GroupPages.jsx'
import { useGroupData } from './groupData.js'

function isValidRecordId(value) {
  return /^\d+$/.test(value ?? '') && Number.isSafeInteger(Number(value)) && Number(value) > 0
}

function displayMemberName(members, userId) {
  return members.find((member) => String(member.userId) === String(userId))?.name ?? 'Group member'
}

function formatPaise(paise) {
  const amount = paise < 0n ? -paise : paise
  const value = `₹${amount / 100n}.${String(amount % 100n).padStart(2, '0')}`
  return paise < 0n ? `−${value}` : value
}

function formatTimestamp(value) {
  if (typeof value !== 'string') return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString()
}

function RequestError({ error, onRetry }) {
  return (
    <div className="request-error" role="alert">
      <p>{error}</p>
      <Button type="button" variant="secondary" onClick={onRetry}>Try again</Button>
    </div>
  )
}

function useExpenseList(groupId, userId, groupStatus) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ scope: '', status: 'loading', expenses: [], error: '' })
  const scope = `${userId}:${groupId}:${attempt}`

  useEffect(() => {
    if (groupStatus !== 'success') return undefined

    const controller = new AbortController()
    async function load() {
      try {
        const expenses = await listExpenses(groupId, { signal: controller.signal })
        if (!controller.signal.aborted) setResult({ scope, status: 'success', expenses, error: '' })
      } catch (error) {
        if (controller.signal.aborted) return
        setResult({
          scope,
          status: 'error',
          expenses: [],
          error: error instanceof ApiError
            ? error.message
            : 'Expenses could not be loaded. Please try again.',
        })
      }
    }

    load().catch(() => {
      if (!controller.signal.aborted) {
        setResult({ scope, status: 'error', expenses: [], error: 'Expenses could not be loaded. Please try again.' })
      }
    })
    return () => controller.abort()
  }, [groupId, groupStatus, scope])

  const current = result.scope === scope
    ? result
    : { status: groupStatus === 'success' ? 'loading' : 'error', expenses: [], error: '' }
  return { ...current, reload: () => setAttempt((value) => value + 1) }
}

function useExpenseRecord(groupId, expenseId, userId, groupStatus) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ scope: '', status: 'loading', expense: null, error: '' })
  const scope = `${userId}:${groupId}:${expenseId}:${attempt}`

  useEffect(() => {
    if (groupStatus !== 'success' || !isValidRecordId(expenseId)) return undefined

    const controller = new AbortController()
    async function load() {
      try {
        const expense = await getExpense(groupId, expenseId, { signal: controller.signal })
        if (!controller.signal.aborted) setResult({ scope, status: 'success', expense, error: '' })
      } catch (error) {
        if (controller.signal.aborted) return
        setResult({
          scope,
          status: error instanceof ApiError && error.status === 404 ? 'not-found' : 'error',
          expense: null,
          error: error instanceof ApiError
            ? error.message
            : 'Expense details could not be loaded. Please try again.',
        })
      }
    }

    load().catch(() => {
      if (!controller.signal.aborted) {
        setResult({ scope, status: 'error', expense: null, error: 'Expense details could not be loaded. Please try again.' })
      }
    })
    return () => controller.abort()
  }, [expenseId, groupId, groupStatus, scope])

  const current = !isValidRecordId(expenseId)
    ? { status: 'not-found', expense: null, error: '' }
    : result.scope === scope
      ? result
      : { status: groupStatus === 'success' ? 'loading' : 'error', expense: null, error: '' }
  return { ...current, reload: () => setAttempt((value) => value + 1) }
}

function ExpenseListContent({ group, members, userId }) {
  const result = useExpenseList(group.id, userId, 'success')
  const location = useLocation()
  const navigate = useNavigate()
  const notice = location.state?.expenseNotice

  function clearNotice() {
    if (notice) navigate(location.pathname, { replace: true, state: null })
  }

  if (result.status === 'loading') {
    return <Card><p className="loading-state" role="status">Loading group expenses…</p></Card>
  }

  if (result.status === 'error') {
    return <Card><RequestError error={result.error} onRetry={result.reload} /></Card>
  }

  if (result.expenses.length === 0) {
    return (
      <>
        {notice && <p className="inline-notice" role="status">{notice}</p>}
        <Card>
          <EmptyState
            icon="receipt"
            title="No expenses yet"
            description="Create the first expense to start keeping this group's spending clear."
            action={<Button to={`/groups/${group.id}/expenses/new`} icon="plus">Create expense</Button>}
          />
        </Card>
      </>
    )
  }

  return (
    <>
      {notice && <p className="inline-notice" role="status">{notice}<button type="button" className="notice-dismiss" onClick={clearNotice} aria-label="Dismiss confirmation">Dismiss</button></p>}
      <div className="expense-list" aria-label="Group expenses">
        {result.expenses.map((expense) => (
          <Card className="expense-list-card" key={expense.id}>
            <div className="expense-list-card__main">
              <div className="expense-list-card__title">
                <h2><Link to={`/groups/${group.id}/expenses/${expense.id}`}>{expense.description}</Link></h2>
                <strong>{formatInr(expense.amount)}</strong>
              </div>
              <p>Paid by {displayMemberName(members, expense.paidByUserId)}</p>
              <div className="expense-list-card__meta">
                <span>Added by {displayMemberName(members, expense.createdByUserId)}</span>
                <span>{expense.splits.length} {expense.splits.length === 1 ? 'participant' : 'participants'}</span>
                {formatTimestamp(expense.createdAt) && <time dateTime={expense.createdAt}>{formatTimestamp(expense.createdAt)}</time>}
              </div>
            </div>
            <Link className="expense-detail-link" to={`/groups/${group.id}/expenses/${expense.id}`}>View details <span aria-hidden="true">→</span></Link>
          </Card>
        ))}
      </div>
    </>
  )
}

export function GroupExpensesPage() {
  const data = useGroupData()
  const { user } = useAuth()
  if (data.status !== 'success') return <GroupUnavailable data={data} />

  return (
    <GroupPageLayout
      group={data.group}
      activeTitle="Expenses"
      description={`Shared spending for ${data.group.name}.`}
      eyebrow="GROUP ACTIVITY"
    >
      <div className="expense-page-heading">
        <div>
          <p>Real expenses recorded for this group.</p>
        </div>
        <Button to={`/groups/${data.group.id}/expenses/new`} icon="plus">Create expense</Button>
      </div>
      <ExpenseListContent group={data.group} members={data.members} userId={user.id} />
    </GroupPageLayout>
  )
}

function ParticipantPicker({ members, selectedIds, onChange, exactAmounts, onAmountChange, errors }) {
  return (
    <fieldset className="expense-participants">
      <legend id="participants-heading" tabIndex="-1">Participants <span>(choose at least one)</span></legend>
      {members.map((member) => {
        const id = String(member.userId)
        const selected = selectedIds.includes(id)
        const amountError = errors[`share-${id}`]
        return (
          <div className="expense-participant" key={id}>
            <label className="expense-participant__choice">
              <input
                type="checkbox"
                checked={selected}
                onChange={(event) => onChange(id, event.target.checked)}
              />
              <span>{member.name}</span>
            </label>
            {exactAmounts && selected && (
              <div className="expense-share-input">
                <label htmlFor={`share-${id}`}>Share for {member.name}</label>
                <div className="expense-money-input">
                  <span aria-hidden="true">₹</span>
                  <input
                    id={`share-${id}`}
                    type="text"
                    inputMode="decimal"
                    autoComplete="off"
                    value={exactAmounts[id] ?? ''}
                    onChange={(event) => onAmountChange(id, event.target.value)}
                    aria-invalid={Boolean(amountError)}
                    aria-describedby={amountError ? `share-${id}-error` : undefined}
                  />
                </div>
                {amountError && <span className="field-error" id={`share-${id}-error`} role="alert">{amountError}</span>}
              </div>
            )}
          </div>
        )
      })}
    </fieldset>
  )
}

function ExpenseForm({ group, members, initialExpense, onSubmit, submitLabel }) {
  const { user } = useAuth()
  const isEditing = Boolean(initialExpense)
  const [description, setDescription] = useState(initialExpense?.description ?? '')
  const [amount, setAmount] = useState(initialExpense ? normalizeMoney(initialExpense.amount) ?? '' : '')
  const [payerId, setPayerId] = useState(String(initialExpense?.paidByUserId ?? user.id))
  const [splitType, setSplitType] = useState(isEditing ? 'EXACT' : 'EQUAL')
  const [selectedIds, setSelectedIds] = useState(() => initialExpense
    ? initialExpense.splits.map((split) => String(split.userId))
    : members.map((member) => String(member.userId)))
  const [exactAmounts, setExactAmounts] = useState(() => Object.fromEntries(
    (initialExpense?.splits ?? []).map((split) => [String(split.userId), normalizeMoney(split.amount) ?? '']),
  ))
  const [errors, setErrors] = useState({})
  const [requestError, setRequestError] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const submitLock = useRef(false)
  const amountPaise = parseMoneyToPaise(amount)

  const exactTotal = useMemo(() => selectedIds.reduce((total, id) => {
    const paise = parseMoneyToPaise(exactAmounts[id] ?? '')
    return paise === null ? total : total + paise
  }, 0n), [exactAmounts, selectedIds])

  function updateParticipants(id, checked) {
    setSelectedIds((current) => checked
      ? current.includes(id) ? current : [...current, id]
      : current.filter((value) => value !== id))
    setErrors((current) => ({ ...current, participants: '', total: '' }))
  }

  function validate() {
    const nextErrors = {}
    const normalizedDescription = description.trim()
    if (!normalizedDescription) nextErrors.description = 'Enter an expense description.'
    else if (normalizedDescription.length > 255) nextErrors.description = 'Description must be 255 characters or fewer.'

    if (!amount.trim()) nextErrors.amount = 'Enter the total expense amount.'
    else if (amountPaise === null) nextErrors.amount = 'Enter a positive amount with up to 17 whole-number digits and 2 decimal places.'

    if (!members.some((member) => String(member.userId) === payerId)) {
      nextErrors.payer = 'Choose a payer who belongs to this group.'
    }

    const uniqueIds = new Set(selectedIds)
    if (selectedIds.length === 0) nextErrors.participants = 'Choose at least one participant.'
    else if (uniqueIds.size !== selectedIds.length || selectedIds.some((id) => !members.some((member) => String(member.userId) === id))) {
      nextErrors.participants = 'Choose valid group members without duplicates.'
    }

    if (splitType === 'EQUAL' && amountPaise !== null && BigInt(selectedIds.length) > amountPaise) {
      nextErrors.participants = 'The total must provide at least INR 0.01 for each selected participant.'
    }

    if (splitType === 'EXACT' && selectedIds.length > 0) {
      let total = 0n
      for (const id of selectedIds) {
        const value = exactAmounts[id] ?? ''
        const paise = parseMoneyToPaise(value)
        if (paise === null) nextErrors[`share-${id}`] = 'Enter a positive share with up to 2 decimal places.'
        else total += paise
      }
      if (amountPaise !== null && total !== amountPaise) {
        nextErrors.total = `Allocated shares total ${formatPaise(total)}; they must equal ${formatInr(amount)}.`
      }
    }

    setErrors(nextErrors)
    return { valid: Object.keys(nextErrors).length === 0, normalizedDescription, errors: nextErrors }
  }

  async function handleSubmit(event) {
    event.preventDefault()
    if (submitLock.current) return

    setRequestError('')
    const result = validate()
    if (!result.valid) {
      const firstInvalid = ['description', 'amount', 'payer', 'participants', ...selectedIds.map((id) => `share-${id}`), 'total']
        .find((field) => result.errors[field])
      const focusId = {
        description: 'expense-description',
        amount: 'expense-amount',
        payer: 'expense-payer',
        participants: 'participants-heading',
        total: 'expense-amount',
      }[firstInvalid] ?? firstInvalid
      if (focusId) document.getElementById(focusId)?.focus?.()
      return
    }

    const normalizedAmount = normalizeMoney(amount)
    const payload = {
      description: result.normalizedDescription,
      amount: normalizedAmount,
      paidByUserId: Number(payerId),
      currencyCode: 'INR',
      splitType,
      ...(splitType === 'EQUAL'
        ? { participantUserIds: selectedIds.map(Number) }
        : {
            splits: selectedIds.map((id) => ({
              userId: Number(id),
              amount: normalizeMoney(exactAmounts[id]),
            })),
          }),
    }

    submitLock.current = true
    setIsSubmitting(true)
    try {
      await onSubmit(payload)
    } catch (error) {
      if (error instanceof ApiError && error.code === 'timeout') {
        setRequestError('The request timed out. The expense may have been saved; refresh the expense list before submitting again.')
      } else {
        setRequestError(error instanceof ApiError
          ? error.message
          : 'The expense could not be saved. Please try again.')
      }
    } finally {
      submitLock.current = false
      setIsSubmitting(false)
    }
  }

  return (
    <Card className="expense-form-card">
      <form className="expense-form" aria-label={isEditing ? 'Edit expense' : 'Create expense'} onSubmit={handleSubmit} noValidate>
        {requestError && <div className="request-error" role="alert"><p>{requestError}</p>{requestError.startsWith('The request timed out.') && <Link className="text-link" to={`/groups/${group.id}/expenses`}>Refresh expense list before trying again</Link>}</div>}
        <div className="form-field">
          <label htmlFor="expense-description">Description <span aria-hidden="true">*</span></label>
          <input
            id="expense-description"
            type="text"
            maxLength={255}
            autoComplete="off"
            value={description}
            onChange={(event) => { setDescription(event.target.value); setErrors((current) => ({ ...current, description: '' })) }}
            aria-invalid={Boolean(errors.description)}
            aria-describedby={errors.description ? 'expense-description-error' : undefined}
            required
          />
          {errors.description && <span className="field-error" id="expense-description-error" role="alert">{errors.description}</span>}
        </div>

        <div className="form-field">
          <label htmlFor="expense-amount">Total amount (INR) <span aria-hidden="true">*</span></label>
          <div className="expense-money-input">
            <span aria-hidden="true">₹</span>
            <input
              id="expense-amount"
              type="text"
              inputMode="decimal"
              autoComplete="off"
              placeholder="0.00"
              value={amount}
              onChange={(event) => { setAmount(event.target.value); setErrors((current) => ({ ...current, amount: '', total: '' })) }}
              aria-invalid={Boolean(errors.amount)}
              aria-describedby={errors.amount ? 'expense-amount-error' : 'expense-amount-help'}
              required
            />
          </div>
          <span className="form-help" id="expense-amount-help">Positive amounts, up to 17 whole-number digits and 2 decimal places.</span>
          {errors.amount && <span className="field-error" id="expense-amount-error" role="alert">{errors.amount}</span>}
        </div>

        <div className="form-field">
          <label htmlFor="expense-payer">Paid by <span aria-hidden="true">*</span></label>
          <select
            id="expense-payer"
            value={payerId}
            onChange={(event) => { setPayerId(event.target.value); setErrors((current) => ({ ...current, payer: '' })) }}
            aria-invalid={Boolean(errors.payer)}
            aria-describedby={errors.payer ? 'expense-payer-error' : undefined}
          >
            {members.map((member) => <option key={member.userId} value={member.userId}>{member.name}</option>)}
          </select>
          {errors.payer && <span className="field-error" id="expense-payer-error" role="alert">{errors.payer}</span>}
        </div>

        <fieldset className="expense-split-mode">
          <legend>Split type</legend>
          <label><input type="radio" name="split-type" value="EQUAL" checked={splitType === 'EQUAL'} onChange={() => { setSplitType('EQUAL'); setErrors({}) }} /> Equal split</label>
          <label><input type="radio" name="split-type" value="EXACT" checked={splitType === 'EXACT'} onChange={() => { setSplitType('EXACT'); setErrors({}) }} /> Custom amounts</label>
        </fieldset>

        {splitType === 'EQUAL' && <p className="form-help">The backend will divide the total among selected members and assign any remainder.</p>}

        <ParticipantPicker
          members={members}
          selectedIds={selectedIds}
          onChange={updateParticipants}
          exactAmounts={splitType === 'EXACT' ? exactAmounts : null}
          onAmountChange={(id, value) => {
            setExactAmounts((current) => ({ ...current, [id]: value }))
            setErrors((current) => ({ ...current, [`share-${id}`]: '', total: '' }))
          }}
          errors={errors}
        />
        {errors.participants && <p className="field-error" role="alert">{errors.participants}</p>}

        {splitType === 'EXACT' && (
          <div className="expense-allocation" aria-live="polite">
            <span>Allocated</span>
            <strong>{formatPaise(exactTotal)}</strong>
            {amountPaise !== null && <span className={exactTotal === amountPaise ? 'allocation-match' : 'allocation-mismatch'}>
              {exactTotal === amountPaise
                ? 'Matches the expense total'
                : exactTotal < amountPaise
                  ? `${formatPaise(amountPaise - exactTotal)} remaining`
                  : `${formatPaise(exactTotal - amountPaise)} over`}
            </span>}
            {errors.total && <span className="field-error" role="alert">{errors.total}</span>}
          </div>
        )}

        <div className="expense-form__actions">
          <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting}>{isSubmitting ? 'Saving…' : submitLabel}</Button>
          <Button type="button" variant="secondary" to={isEditing ? `/groups/${group.id}/expenses/${initialExpense.id}` : `/groups/${group.id}/expenses`}>Cancel</Button>
        </div>
      </form>
    </Card>
  )
}

export function ExpenseCreatePage() {
  const data = useGroupData()
  const navigate = useNavigate()

  if (data.status !== 'success') return <GroupUnavailable data={data} />

  return (
    <GroupPageLayout group={data.group} activeTitle="New expense" description={`Add a real expense to ${data.group.name}.`} eyebrow="GROUP ACTIVITY">
      <PageHeader eyebrow="NEW EXPENSE" title="Create expense" description="Add a shared expense using this group's current members." />
      <ExpenseForm
        key={`${data.group.id}:new`}
        group={data.group}
        members={data.members}
        submitLabel="Save expense"
        onSubmit={async (payload) => {
          const expense = await createExpense(data.group.id, payload)
          navigate(`/groups/${data.group.id}/expenses/${expense.id}`, {
            replace: true,
            state: { expenseNotice: 'Expense created successfully.' },
          })
        }}
      />
    </GroupPageLayout>
  )
}

function ExpenseDetailContent({ group, members, expense, onReload }) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [showDelete, setShowDelete] = useState(false)
  const [isDeleting, setIsDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState('')
  const deleteLock = useRef(false)
  const deleteTrigger = useRef(null)
  const isCreator = String(expense.createdByUserId) === String(user.id)
  const location = useLocation()

  useEffect(() => {
    if (!showDelete) return undefined

    document.querySelector('.expense-dialog .button--secondary')?.focus()
    function handleDialogKeyDown(event) {
      if (event.key === 'Escape' && !isDeleting) {
        setShowDelete(false)
        deleteTrigger.current?.focus()
        return
      }
      if (event.key !== 'Tab') return

      const dialog = event.currentTarget.querySelector('[role="alertdialog"]')
      const focusable = [...dialog.querySelectorAll('button:not(:disabled)')]
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last?.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first?.focus()
      }
    }

    document.addEventListener('keydown', handleDialogKeyDown)
    return () => document.removeEventListener('keydown', handleDialogKeyDown)
  }, [showDelete, isDeleting])

  function cancelDelete() {
    setShowDelete(false)
    deleteTrigger.current?.focus()
  }

  async function confirmDelete() {
    if (deleteLock.current) return
    deleteLock.current = true
    setIsDeleting(true)
    setDeleteError('')
    try {
      await deleteExpense(group.id, expense.id)
      navigate(`/groups/${group.id}/expenses`, {
        replace: true,
        state: { expenseNotice: 'Expense deleted successfully.' },
      })
    } catch (error) {
      setDeleteError(error instanceof ApiError
        ? error.message
        : 'The expense could not be deleted. Please try again.')
    } finally {
      deleteLock.current = false
      setIsDeleting(false)
    }
  }

  return (
    <>
      {location.state?.expenseNotice && <p className="inline-notice" role="status">{location.state.expenseNotice}</p>}
      <div className="expense-detail-actions">
        <Button variant="secondary" to={`/groups/${group.id}/expenses`}>Back to expenses</Button>
        {isCreator && <Button to={`/groups/${group.id}/expenses/${expense.id}/edit`}>Edit expense</Button>}
        {isCreator && <Button variant="danger" type="button" onClick={(event) => { deleteTrigger.current = event.currentTarget; setDeleteError(''); setShowDelete(true) }}>Delete expense</Button>}
      </div>
      {deleteError && !showDelete && <p className="request-error" role="alert">{deleteError}<button type="button" className="text-button" onClick={onReload}>Refresh expense</button></p>}
      <Card className="expense-detail-card">
        <p className="eyebrow">EXPENSE DETAILS</p>
        <h2>{expense.description}</h2>
        <p className="expense-detail-total">{formatInr(expense.amount)} <span>INR total</span></p>
        <dl className="expense-detail-facts">
          <div><dt>Paid by</dt><dd>{displayMemberName(members, expense.paidByUserId)}</dd></div>
          <div><dt>Created by</dt><dd>{displayMemberName(members, expense.createdByUserId)}</dd></div>
          {formatTimestamp(expense.createdAt)
            ? <div><dt>Created</dt><dd><time dateTime={expense.createdAt}>{formatTimestamp(expense.createdAt)}</time></dd></div>
            : <div><dt>Created</dt><dd>Timestamp not provided by the expense API</dd></div>}
        </dl>
      </Card>
      <Card className="expense-shares-card">
        <div className="section-heading">
          <div><h2>Participant shares</h2><p>Persisted amounts returned by the backend</p></div>
          <strong>{expense.splits.length} {expense.splits.length === 1 ? 'person' : 'people'}</strong>
        </div>
        <ul className="expense-share-list">
          {expense.splits.map((split) => (
            <li key={split.userId}>
              <span>{displayMemberName(members, split.userId)}</span>
              <strong>{formatInr(split.amount)}</strong>
            </li>
          ))}
        </ul>
      </Card>
      {showDelete && (
        <div className="expense-dialog-backdrop">
          <section className="expense-dialog" role="alertdialog" aria-modal="true" aria-labelledby="delete-expense-title" aria-describedby="delete-expense-description">
            <h2 id="delete-expense-title">Delete “{expense.description}”?</h2>
            <p id="delete-expense-description">This permanently deletes the expense and its shares from this MVP. This action cannot be undone.</p>
            {deleteError && <p className="field-error" role="alert">{deleteError}</p>}
            <div className="expense-form__actions">
              <Button type="button" variant="secondary" disabled={isDeleting} onClick={cancelDelete}>Cancel</Button>
              <Button type="button" variant="danger" disabled={isDeleting} aria-busy={isDeleting} onClick={confirmDelete}>{isDeleting ? 'Deleting…' : 'Delete expense'}</Button>
            </div>
          </section>
        </div>
      )}
    </>
  )
}

function ExpenseRecordPage({ mode }) {
  const data = useGroupData()
  const { expenseId } = useParams()
  const { user } = useAuth()
  const navigate = useNavigate()
  const record = useExpenseRecord(data.groupId, expenseId, user.id, data.status)

  if (data.status !== 'success') return <GroupUnavailable data={data} />
  if (record.status === 'loading') {
    return <GroupPageLayout group={data.group} activeTitle="Expense" eyebrow="GROUP ACTIVITY"><Card><p className="loading-state" role="status">Loading expense details…</p></Card></GroupPageLayout>
  }
  if (record.status === 'not-found') {
    return <GroupPageLayout group={data.group} activeTitle="Expense unavailable" eyebrow="GROUP ACTIVITY"><Card><EmptyState icon="receipt" title="Expense not found or unavailable" description="This expense may not exist in this group, or you may not have access to it." action={<Button variant="secondary" to={`/groups/${data.group.id}/expenses`}>Back to expenses</Button>} /></Card></GroupPageLayout>
  }
  if (record.status === 'error') {
    return <GroupPageLayout group={data.group} activeTitle="Expense unavailable" eyebrow="GROUP ACTIVITY"><Card><RequestError error={record.error} onRetry={record.reload} /></Card></GroupPageLayout>
  }

  const expense = record.expense
  const isCreator = String(expense.createdByUserId) === String(user.id)
  if (mode === 'edit' && !isCreator) {
    return (
      <GroupPageLayout group={data.group} activeTitle={expense.description} eyebrow="GROUP ACTIVITY">
        <Card><p role="status">Only the person who created this expense can edit it.</p><Button variant="secondary" to={`/groups/${data.group.id}/expenses/${expense.id}`}>Back to expense details</Button></Card>
      </GroupPageLayout>
    )
  }

  if (mode === 'edit') {
    return (
      <GroupPageLayout group={data.group} activeTitle="Edit expense" description={`Update ${expense.description}.`} eyebrow="GROUP ACTIVITY">
        <PageHeader eyebrow="EDIT EXPENSE" title="Edit expense" description="Saved shares are loaded as custom amounts. Choose equal split explicitly if you want to recalculate." />
        <ExpenseForm
          key={`${data.group.id}:${expense.id}:${user.id}`}
          group={data.group}
          members={data.members}
          initialExpense={expense}
          submitLabel="Save changes"
          onSubmit={async (payload) => {
            const updated = await updateExpense(data.group.id, expense.id, payload)
            navigate(`/groups/${data.group.id}/expenses/${updated.id}`, {
              replace: true,
              state: { expenseNotice: 'Expense updated successfully.' },
            })
          }}
        />
      </GroupPageLayout>
    )
  }

  return (
    <GroupPageLayout group={data.group} activeTitle="Expense details" description={expense.description} eyebrow="GROUP ACTIVITY">
      <ExpenseDetailContent group={data.group} members={data.members} expense={expense} onReload={record.reload} />
    </GroupPageLayout>
  )
}

export function ExpenseDetailPage() {
  return <ExpenseRecordPage mode="view" />
}

export function ExpenseEditPage() {
  return <ExpenseRecordPage mode="edit" />
}
