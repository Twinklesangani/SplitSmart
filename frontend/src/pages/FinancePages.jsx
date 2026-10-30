import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { Button, Card, EmptyState } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { ApiError } from '../services/apiClient.js'
import { parseMoneyToPaise } from '../services/expenseService.js'
import {
  createSettlement,
  formatFinancialInr,
  getGroupBalances,
  getSettlementSuggestions,
  listSettlements,
  normalizeFinancialInr,
  parseFinancialInrPaise,
} from '../services/financeService.js'
import { GroupPageLayout, GroupUnavailable } from './GroupPages.jsx'
import { useGroupData } from './groupData.js'

function isUuid(value) {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
}

function apiErrorMessage(error, fallback) {
  return error instanceof ApiError ? error.message : fallback
}

function validateMemberCoverage(balances, members) {
  const memberIds = members.map((member) => String(member.userId))
  const balanceIds = balances.map((balance) => String(balance.userId))
  if (
    new Set(memberIds).size !== memberIds.length
    || memberIds.length !== balanceIds.length
    || memberIds.some((id) => !balanceIds.includes(id))
  ) {
    throw new ApiError('The service returned financial data that does not match the current group members. Refresh and try again.', {
      code: 'invalid_response',
    })
  }
}

function validateSuggestionMembers(suggestions, members) {
  const memberIds = new Set(members.map((member) => String(member.userId)))
  if (suggestions.some((suggestion) => (
    !memberIds.has(String(suggestion.fromUserId))
    || !memberIds.has(String(suggestion.toUserId))
  ))) {
    throw new ApiError('The service returned a repayment suggestion for an unknown group member. Refresh and try again.', {
      code: 'invalid_response',
    })
  }
}

function financeFailure(error, fallback) {
  return { status: 'error', value: null, error: apiErrorMessage(error, fallback) }
}

function financeSuccess(value) {
  return { status: 'success', value, error: '' }
}

function useFinanceData(groupId, userId, members, groupStatus) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ scope: '', status: 'loading', balances: null, suggestions: null, settlements: null })
  const loadRef = useRef({ id: 0, controller: null })
  const scope = `${userId}:${groupId}:${attempt}`

  const refresh = useCallback(async () => {
    if (groupStatus !== 'success') return null
    loadRef.current.controller?.abort()
    const controller = new AbortController()
    const requestId = loadRef.current.id + 1
    loadRef.current = { id: requestId, controller }

    const [balanceResult, suggestionResult, settlementResult] = await Promise.all([
      getGroupBalances(groupId, { signal: controller.signal }).then(
        (value) => ({ value }),
        (error) => ({ error }),
      ),
      getSettlementSuggestions(groupId, { signal: controller.signal }).then(
        (value) => ({ value }),
        (error) => ({ error }),
      ),
      listSettlements(groupId, { signal: controller.signal }).then(
        (value) => ({ value }),
        (error) => ({ error }),
      ),
    ])

    if (controller.signal.aborted || loadRef.current.id !== requestId) return null

    let balances = balanceResult.error
      ? financeFailure(balanceResult.error, 'Group balances could not be loaded. Please try again.')
      : financeSuccess(balanceResult.value)
    if (balances.status === 'success') {
      try {
        validateMemberCoverage(balances.value.balances, members)
      } catch (error) {
        balances = financeFailure(error, 'Group balances could not be loaded. Please try again.')
      }
    }

    let suggestions = suggestionResult.error
      ? financeFailure(suggestionResult.error, 'Repayment suggestions could not be loaded. Please try again.')
      : financeSuccess(suggestionResult.value)
    if (suggestions.status === 'success') {
      try {
        validateSuggestionMembers(suggestions.value.suggestions, members)
      } catch (error) {
        suggestions = financeFailure(error, 'Repayment suggestions could not be loaded. Please try again.')
      }
    }

    const settlements = settlementResult.error
      ? financeFailure(settlementResult.error, 'Settlement history could not be loaded. Please try again.')
      : financeSuccess(settlementResult.value)
    const freshResult = { scope, status: 'success', balances, suggestions, settlements }
    setResult(freshResult)
    loadRef.current = { id: requestId, controller: null }
    return freshResult
  }, [groupId, groupStatus, members, scope])

  useEffect(() => {
    refresh().catch((error) => {
      if (loadRef.current.controller) {
        setResult({
          scope,
          status: 'success',
          balances: financeFailure(error, 'Group balances could not be loaded. Please try again.'),
          suggestions: financeFailure(error, 'Repayment suggestions could not be loaded. Please try again.'),
          settlements: financeFailure(error, 'Settlement history could not be loaded. Please try again.'),
        })
      }
    })
    return () => {
      if (loadRef.current.controller) {
        loadRef.current.controller.abort()
        loadRef.current = { id: loadRef.current.id + 1, controller: null }
      }
    }
  }, [refresh, scope])

  const current = result.scope === scope
    ? result
    : { status: 'loading', balances: null, suggestions: null, settlements: null }
  return { ...current, refresh, reload: () => setAttempt((value) => value + 1) }
}

function retryFinance(finance) {
  finance.reload()
}

function RequestError({ error, onRetry }) {
  return (
    <div className="request-error" role="alert">
      <p>{error}</p>
      <Button type="button" variant="secondary" onClick={onRetry}>Try again</Button>
    </div>
  )
}

function memberName(members, userId) {
  return members.find((member) => String(member.userId) === String(userId))?.name ?? 'Group member'
}

function timestampLabel(value) {
  if (typeof value !== 'string') return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toLocaleString()
}

function PersonalBalance({ balance }) {
  const net = parseFinancialInrPaise(balance.netBalance)
  if (net === null) return null
  if (net < 0n) {
    return <p className="finance-personal__message">You owe <strong>{formatFinancialInr(balance.netBalance).replace('−', '')}</strong> overall.</p>
  }
  if (net > 0n) {
    return <p className="finance-personal__message">You are owed <strong>{formatFinancialInr(balance.netBalance)}</strong> overall.</p>
  }
  return <p className="finance-personal__message">You are settled with this group.</p>
}

function MemberBalanceList({ balances, currentUserId }) {
  return (
    <div className="finance-member-list" aria-label="Member balances">
      {balances.map((balance) => {
        const net = parseFinancialInrPaise(balance.netBalance)
        const status = net > 0n ? 'receivable' : net < 0n ? 'owes' : 'settled'
        const statusText = status === 'receivable'
          ? 'Should receive'
          : status === 'owes'
            ? 'Owes'
            : 'Settled'
        return (
          <article className={`finance-member${String(balance.userId) === String(currentUserId) ? ' is-current-user' : ''}`} key={balance.userId}>
            <div className="finance-member__heading">
              <div><h3>{balance.name}{String(balance.userId) === String(currentUserId) ? <span className="finance-you-label">You</span> : null}</h3><span>{balance.email}</span></div>
              <strong className={`finance-balance finance-balance--${status}`}>
                <span>{statusText}</span>
                <span>{status === 'settled' ? formatFinancialInr(balance.netBalance) : formatFinancialInr(balance.netBalance).replace(status === 'owes' ? '−' : '', '')}</span>
              </strong>
            </div>
            <dl className="finance-member__totals">
              <div><dt>Total paid</dt><dd>{formatFinancialInr(balance.totalPaid)}</dd></div>
              <div><dt>Expense share</dt><dd>{formatFinancialInr(balance.totalShare)}</dd></div>
              <div><dt>Repayments sent</dt><dd>{formatFinancialInr(balance.totalSentSettlements)}</dd></div>
              <div><dt>Repayments received</dt><dd>{formatFinancialInr(balance.totalReceivedSettlements)}</dd></div>
            </dl>
          </article>
        )
      })}
    </div>
  )
}

function SuggestionList({ suggestions, balances, members, groupId, currentUserId }) {
  const allSettled = balances !== null && balances.every((balance) => parseFinancialInrPaise(balance.netBalance) === 0n)
  if (suggestions.length === 0) {
    if (!balances) {
      return <EmptyState icon="wallet" title="Balance status unavailable" description="No suggestions were returned, but current balances could not be verified. Refresh before deciding whether the group is settled." />
    }
    return allSettled
      ? <EmptyState icon="check" title="All settled" description="The current group balances are settled. There are no repayment recommendations." />
      : <EmptyState icon="wallet" title="No recommendations available" description="The balances are not settled, but the service returned no repayment recommendations. Refresh to check again." />
  }
  if (allSettled) {
    return <p className="request-error" role="alert">The service returned repayment recommendations while every member balance is settled. Refresh before recording a repayment.</p>
  }

  return (
    <ol className="suggestion-list">
      {suggestions.map((suggestion, index) => {
        const canRecord = String(suggestion.fromUserId) === String(currentUserId)
        return (
          <li className="suggestion-item" key={`${suggestion.fromUserId}-${suggestion.toUserId}-${index}`}>
            <span className="suggestion-item__direction" aria-hidden="true">→</span>
            <div className="suggestion-item__main">
              <p><strong>{memberName(members, suggestion.fromUserId)}</strong><span> pays </span><strong>{memberName(members, suggestion.toUserId)}</strong></p>
              <span>Informational recommendation only. No money is transferred.</span>
            </div>
            <strong className="suggestion-item__amount">{formatFinancialInr(suggestion.amount)}</strong>
            {canRecord && (
              <Button
                to={`/groups/${groupId}/settlements`}
                state={{ suggestion }}
                variant="secondary"
                className="suggestion-item__action"
              >
                Record this repayment
              </Button>
            )}
          </li>
        )
      })}
    </ol>
  )
}

function GroupBalancesContent({ data, user }) {
  const finance = useFinanceData(data.group.id, user.id, data.members, data.status)
  const personalBalance = finance.balances?.status === 'success'
    ? finance.balances.value.balances.find((balance) => String(balance.userId) === String(user.id))
    : null
  const allSettled = finance.balances?.status === 'success'
    && finance.balances.value.balances.every((balance) => parseFinancialInrPaise(balance.netBalance) === 0n)

  return (
    <GroupPageLayout group={data.group} activeTitle="Balances" description={`Member balances for ${data.group.name}.`} eyebrow="SETTLE UP, SIMPLY">
      <section className="finance-summary-grid" aria-label="Group financial summary">
        <Card className="finance-total-card">
          <p className="eyebrow">GROUP TOTAL</p>
          <h2>{finance.balances?.status === 'success' ? formatFinancialInr(finance.balances.value.totalExpenseAmount) : '—'}</h2>
          <p>Total expenses recorded · INR</p>
        </Card>
        <Card className="finance-personal">
          <p className="eyebrow">YOUR POSITION</p>
          <h2>{personalBalance ? formatFinancialInr(personalBalance.netBalance) : '—'}</h2>
          {personalBalance
            ? <PersonalBalance balance={personalBalance} />
            : <p className="finance-personal__message">{finance.balances?.status === 'success' ? 'Your account is not listed as a current group member.' : 'Your personal balance is unavailable.'}</p>}
        </Card>
      </section>

      <Card className="finance-section">
        <div className="section-heading">
          <div><h2>Member balances</h2><p>Paid, shared, repaid, and outstanding amounts from the service.</p></div>
          {finance.balances?.status !== 'success' && <Button type="button" variant="secondary" onClick={() => retryFinance(finance)}>Refresh</Button>}
        </div>
        {finance.balances?.status === 'success'
          ? <MemberBalanceList balances={finance.balances.value.balances} currentUserId={user.id} />
          : finance.status === 'loading'
            ? <p className="loading-state" role="status">Loading group balances…</p>
            : <RequestError error={finance.balances?.error ?? 'Group balances could not be loaded. Please try again.'} onRetry={() => retryFinance(finance)} />}
      </Card>

      <Card className="finance-section">
        <div className="section-heading">
          <div><h2>Suggested repayments</h2><p>Recommendations generated from the current outstanding balances.</p></div>
          {finance.suggestions?.status !== 'success' && <Button type="button" variant="secondary" onClick={() => retryFinance(finance)}>Refresh</Button>}
        </div>
        {finance.suggestions?.status === 'success'
          ? <>
            <p className="finance-suggestion-total">Suggested total <strong>{formatFinancialInr(finance.suggestions.value.totalSuggestedAmount)}</strong></p>
            <SuggestionList
              suggestions={finance.suggestions.value.suggestions}
              balances={finance.balances?.status === 'success' ? finance.balances.value.balances : null}
              members={data.members}
              groupId={data.group.id}
              currentUserId={user.id}
            />
            <p className="finance-disclaimer">Suggestions are informational only. SplitSmart does not initiate a bank, UPI, or other money transfer.</p>
          </>
          : finance.status === 'loading'
            ? <p className="loading-state" role="status">Loading repayment suggestions…</p>
            : <RequestError error={finance.suggestions?.error ?? 'Repayment suggestions could not be loaded. Please try again.'} onRetry={() => retryFinance(finance)} />}
      </Card>
      {allSettled && <span className="sr-only" aria-live="polite">All current group balances are settled.</span>}
    </GroupPageLayout>
  )
}

export function GroupBalancesPage() {
  const data = useGroupData()
  const { user } = useAuth()
  if (data.status !== 'success') return <GroupUnavailable data={data} />
  return <GroupBalancesContent key={`${user.id}:${data.group.id}`} data={data} user={user} />
}

function formError(error) {
  if (error instanceof ApiError && error.status === 400) {
    return 'The repayment could not be recorded. It may exceed the current outstanding balance. Refresh balances and try again.'
  }
  return apiErrorMessage(error, 'The repayment could not be recorded. Please try again.')
}

function SettlementForm({ groupId, members, currentUser, finance, onRefresh }) {
  const location = useLocation()
  const suggestion = location.state?.suggestion
  const suggestedReceiver = suggestion
    && String(suggestion.fromUserId) === String(currentUser.id)
    && members.some((member) => String(member.userId) === String(suggestion.toUserId))
    ? String(suggestion.toUserId)
    : ''
  const suggestedAmount = suggestedReceiver ? normalizeFinancialInr(suggestion.amount) ?? '' : ''
  const [receiverId, setReceiverId] = useState(suggestedReceiver)
  const [amount, setAmount] = useState(suggestedAmount)
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [pendingSubmission, setPendingSubmission] = useState(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [hasConfirmed, setHasConfirmed] = useState(false)
  const receiverRef = useRef(null)
  const amountRef = useRef(null)
  const submissionLock = useRef(false)
  const mounted = useRef(true)
  const canSubmit = finance.balances?.status === 'success' && finance.settlements?.status === 'success'

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  function showFormError(message, target = 'amount') {
    setError(message)
    setNotice('')
    if (target === 'receiver') receiverRef.current?.focus()
    else amountRef.current?.focus()
  }

  function confirmRecorded(settlement, keepSubmitting = false) {
    setPendingSubmission(null)
    setHasConfirmed(true)
    setIsSubmitting(keepSubmitting)
    setError('')
    setNotice(`Repayment record ${settlement.id} was saved. This does not confirm or initiate a money transfer.`)
    setReceiverId('')
    setAmount('')
    setNote('')
  }

  async function reconcilePending() {
    setError('')
    const refreshed = await onRefresh()
    if (!mounted.current) return
    const pending = pendingSubmission
    if (!refreshed || refreshed.settlements?.status !== 'success') {
      setError('Settlement history could not be refreshed. The request outcome remains uncertain; do not record a different repayment yet.')
      return
    }
    const recorded = refreshed.settlements.value.find((item) => item.requestId === pending?.requestId)
    if (recorded) {
      confirmRecorded(recorded)
      setNotice(`Repayment record ${recorded.id} was found in settlement history. This does not confirm or initiate a money transfer.`)
      return
    }
    setError('No matching request is visible in the refreshed history, but the earlier result is still uncertain. You may explicitly retry the same request safely.')
  }

  async function submitPayload(payload) {
    if (submissionLock.current) return
    submissionLock.current = true
    setIsSubmitting(true)
    setError('')
    setNotice('')
    setHasConfirmed(false)
    setPendingSubmission({ requestId: payload.requestId, payload })
    try {
      const saved = await createSettlement(groupId, payload)
      if (!mounted.current) return
      confirmRecorded(saved, true)
      const refreshed = await onRefresh()
      if (mounted.current && (!refreshed
        || refreshed.balances?.status !== 'success'
        || refreshed.suggestions?.status !== 'success'
        || refreshed.settlements?.status !== 'success')) {
        setNotice((current) => `${current} Some updated financial data is unavailable; use Refresh to load it.`)
      }
    } catch (requestError) {
      if (!mounted.current) return
      if (
        requestError instanceof ApiError
        && [400, 401, 403, 404].includes(requestError.status)
      ) {
        setPendingSubmission(null)
        setIsSubmitting(false)
        setError(formError(requestError))
        return
      }

      setError(requestError instanceof ApiError && requestError.status === 409
        ? requestError.message
        : 'We could not confirm whether the repayment record was saved. Refresh settlement history before trying again.')
    } finally {
      submissionLock.current = false
      if (mounted.current) setIsSubmitting(false)
    }
  }

  async function handleSubmit(event) {
    event.preventDefault()
    if (submissionLock.current) return
    if (pendingSubmission && !hasConfirmed) {
      showFormError('Refresh settlement history before retrying the pending request.')
      return
    }
    if (!canSubmit) {
      showFormError('Load current balances and settlement history before recording a repayment.')
      return
    }
    const receiver = members.find((member) => String(member.userId) === receiverId)
    if (!receiver || String(receiver.userId) === String(currentUser.id)) {
      showFormError('Choose another current group member as the recipient.', 'receiver')
      return
    }
    const amountPaise = parseMoneyToPaise(amount)
    if (amountPaise === null) {
      showFormError('Enter a positive amount with no more than two decimal places.')
      return
    }
    const normalizedAmount = `${amountPaise / 100n}.${String(amountPaise % 100n).padStart(2, '0')}`
    const cleanNote = note.trim()
    if (cleanNote.length > 200) {
      showFormError('The note must be 200 characters or fewer.')
      return
    }
    let requestId
    try {
      requestId = window.crypto.randomUUID()
    } catch {
      showFormError('This browser cannot generate a secure request identifier. Update the browser or try again.')
      return
    }
    if (!isUuid(requestId)) {
      showFormError('This browser returned an invalid request identifier. Try again.')
      return
    }

    const payload = {
      fromUserId: currentUser.id,
      toUserId: receiver.userId,
      amount: normalizedAmount,
      requestId,
      note: cleanNote || null,
    }
    await submitPayload(payload)
  }

  async function retrySameRequest() {
    if (!pendingSubmission || submissionLock.current) return
    const refreshed = await onRefresh()
    if (!mounted.current) return
    if (!refreshed || refreshed.settlements?.status !== 'success') {
      setError('Settlement history could not be refreshed. The request outcome remains uncertain; do not submit a new repayment.')
      return
    }
    const recorded = refreshed.settlements.value.find((item) => item.requestId === pendingSubmission.requestId)
    if (recorded) {
      confirmRecorded(recorded)
      setNotice(`Repayment record ${recorded.id} was found in settlement history. This does not confirm or initiate a money transfer.`)
      return
    }
    await submitPayload(pendingSubmission.payload)
  }

  const locked = isSubmitting || Boolean(pendingSubmission && !hasConfirmed)
  const availableMembers = members.filter((member) => String(member.userId) !== String(currentUser.id))

  return (
    <Card className="finance-section">
      <div className="finance-form-heading">
        <p className="eyebrow">REPORTED PAYMENT</p>
        <h2>Record repayment</h2>
        <p>Mark a payment you have already made to another member.</p>
      </div>
      <p className="finance-disclaimer" role="note">This records a reported payment in SplitSmart. It does not transfer money or verify a bank or UPI transaction.</p>
      {notice && <p className="inline-notice" role="status">{notice}</p>}
      {error && <div className="request-error finance-form-error" role="alert"><p>{error}</p>
        {pendingSubmission && !hasConfirmed
          ? <Button type="button" variant="secondary" disabled={isSubmitting} onClick={reconcilePending}>Refresh settlement history</Button>
          : <Button type="button" variant="secondary" disabled={isSubmitting} onClick={() => onRefresh()}>Refresh balances and history</Button>}
        {pendingSubmission && !hasConfirmed && finance.settlements?.status === 'success'
          && <Button type="button" disabled={isSubmitting} onClick={retrySameRequest}>Retry same request</Button>}
      </div>}
      <form className="finance-form" aria-label="Record a repayment" onSubmit={handleSubmit} noValidate>
        <div className="form-field">
          <label htmlFor="settlement-sender">Sender</label>
          <input id="settlement-sender" value={`${currentUser.name} (you)`} readOnly aria-readonly="true" />
          <span className="form-help">Only the signed-in member can record their own reported repayment.</span>
        </div>
        <div className="form-field">
          <label htmlFor="settlement-recipient">Recipient <span aria-hidden="true">*</span></label>
          <select
            id="settlement-recipient"
            ref={receiverRef}
            value={receiverId}
            onChange={(event) => { setReceiverId(event.target.value); setError('') }}
            disabled={locked}
            aria-invalid={Boolean(error && !receiverId)}
          >
            <option value="">Choose a group member</option>
            {availableMembers.map((member) => <option key={member.userId} value={String(member.userId)}>{member.name}</option>)}
          </select>
        </div>
        <div className="form-field">
          <label htmlFor="settlement-amount">Amount (INR) <span aria-hidden="true">*</span></label>
          <div className="money-input"><span aria-hidden="true">₹</span><input
            id="settlement-amount"
            ref={amountRef}
            type="text"
            inputMode="decimal"
            autoComplete="off"
            value={amount}
            onChange={(event) => { setAmount(event.target.value); setError('') }}
            maxLength={15}
            placeholder="0.00"
            disabled={locked}
            aria-invalid={Boolean(error && amount !== '')}
          /></div>
          <span className="form-help">Enter a positive amount with at most two decimal places. No rounding is applied.</span>
        </div>
        <div className="form-field">
          <label htmlFor="settlement-note">Note <span>(optional)</span></label>
          <textarea
            id="settlement-note"
            value={note}
            onChange={(event) => { setNote(event.target.value); setError('') }}
            maxLength={200}
            rows={3}
            disabled={locked}
            placeholder="Short, non-sensitive note"
          />
          <span className="form-help">{note.length}/200 characters. Do not include account credentials or payment secrets.</span>
        </div>
        <div className="finance-form__actions">
          <Button type="submit" disabled={!canSubmit || isSubmitting || Boolean(pendingSubmission && !hasConfirmed)} aria-busy={isSubmitting}>
            {isSubmitting ? 'Recording…' : 'Record repayment'}
          </Button>
          {!canSubmit && <span className="form-help" role="status">Load current balances and settlement history before submitting.</span>}
        </div>
      </form>
    </Card>
  )
}

function SettlementHistory({ settlements, members, isLoading, error, onRetry }) {
  if (isLoading) return <p className="loading-state" role="status">Loading settlement history…</p>
  if (error) return <RequestError error={error} onRetry={onRetry} />
  if (settlements.length === 0) {
    return <EmptyState icon="check" title="No repayments recorded yet" description="This group has no recorded repayments. This does not indicate whether anyone currently owes money." />
  }

  return (
    <ol className="settlement-history">
      {settlements.map((settlement) => {
        const timestamp = timestampLabel(settlement.createdAt)
        return (
          <li className="settlement-history__item" key={settlement.id}>
            <div className="settlement-history__main">
              <div><p><strong>{memberName(members, settlement.fromUserId)}</strong><span> repaid </span><strong>{memberName(members, settlement.toUserId)}</strong></p><span>Recorded by {memberName(members, settlement.recordedByUserId)}</span></div>
              <strong className="settlement-history__amount">{formatFinancialInr(settlement.amount)}</strong>
            </div>
            {settlement.note && <p className="settlement-history__note">{settlement.note}</p>}
            {timestamp && <time dateTime={settlement.createdAt}>{timestamp}</time>}
          </li>
        )
      })}
    </ol>
  )
}

function GroupSettlementsContent({ data, user }) {
  const finance = useFinanceData(data.group.id, user.id, data.members, data.status)

  return (
    <GroupPageLayout group={data.group} activeTitle="Settlements" description={`Reported repayments for ${data.group.name}.`} eyebrow="REPAYMENT HISTORY">
      <SettlementForm
        key={`${user.id}:${data.group.id}`}
        groupId={data.group.id}
        members={data.members}
        currentUser={user}
        finance={finance}
        onRefresh={finance.refresh}
      />
      <Card className="finance-section">
        <div className="section-heading">
          <div><h2>Settlement history</h2><p>Reported repayments recorded for this group, newest first.</p></div>
          <Button type="button" variant="secondary" onClick={() => finance.refresh()}>Refresh history and balances</Button>
        </div>
        <SettlementHistory
          settlements={finance.settlements?.value ?? []}
          members={data.members}
          isLoading={finance.status === 'loading' || finance.settlements?.status === 'loading'}
          error={finance.settlements?.error}
          onRetry={() => retryFinance(finance)}
        />
      </Card>
      {finance.balances?.status === 'error' && <Card><RequestError error={finance.balances.error} onRetry={() => retryFinance(finance)} /></Card>}
      {finance.suggestions?.status === 'error' && <Card><RequestError error={finance.suggestions.error} onRetry={() => retryFinance(finance)} /></Card>}
      <p className="finance-disclaimer">A settlement is a self-reported record, not a payment or transfer initiated by SplitSmart.</p>
    </GroupPageLayout>
  )
}

export function GroupSettlementsPage() {
  const data = useGroupData()
  const { user } = useAuth()
  if (data.status !== 'success') return <GroupUnavailable data={data} />
  return <GroupSettlementsContent key={`${user.id}:${data.group.id}`} data={data} user={user} />
}
