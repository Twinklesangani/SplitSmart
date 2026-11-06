import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Button, Card, EmptyState, FormField, GroupCard, PageHeader } from '../components/UI.jsx'
import { useAuth } from '../context/useAuth.js'
import { ApiError } from '../services/apiClient.js'
import { createGroup, listGroups } from '../services/groupService.js'
import { InvitationInbox } from './Invitations.jsx'
import { useRefreshOnReturn } from './useRefreshOnReturn.js'

const GROUP_NAME_MAX_LENGTH = 120

export default function GroupsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [search] = useSearchParams()
  const [data, setData] = useState({ scope: '', status: 'loading', groups: [], error: '' })
  const [loadAttempt, setLoadAttempt] = useState(0)
  useRefreshOnReturn(setLoadAttempt)
  const scope = `${user.id}:${loadAttempt}`
  const [createOpen, setCreateOpen] = useState(() => search.get('create') === '1')
  const [groupName, setGroupName] = useState('')
  const [createError, setCreateError] = useState('')
  const [isCreating, setIsCreating] = useState(false)
  const [createNotice, setCreateNotice] = useState('')
  const createLock = useRef(false)
  const nameInput = useRef(null)

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
        error: error instanceof ApiError ? error.message : 'Groups could not be loaded. Please try again.',
      })
    })

    return () => controller.abort()
  }, [scope, loadAttempt])

  useEffect(() => {
    if (createOpen) nameInput.current?.focus()
  }, [createOpen])

  const visibleData = data.scope === scope
    ? data
    : { status: 'loading', groups: [], error: '' }

  function openCreateForm() {
    setCreateError('')
    setCreateNotice('')
    setCreateOpen(true)
  }

  async function handleCreate(event) {
    event.preventDefault()
    if (createLock.current || isCreating) return

    const name = groupName.trim()
    if (!name) {
      setCreateError('Enter a group name.')
      nameInput.current?.focus()
      return
    }
    if (name.length > GROUP_NAME_MAX_LENGTH) {
      setCreateError(`Group name must be ${GROUP_NAME_MAX_LENGTH} characters or fewer.`)
      nameInput.current?.focus()
      return
    }

    createLock.current = true
    setIsCreating(true)
    setCreateError('')
    setCreateNotice('')
    try {
      const group = await createGroup(name)
      setData((current) => current.scope === scope
        ? { ...current, groups: [group, ...current.groups] }
        : current)
      setCreateNotice('Group created.')
      navigate(`/groups/${group.id}`)
    } catch (error) {
      setCreateError(error instanceof ApiError
        ? error.message
        : 'The group could not be created. Please try again.')
    } finally {
      createLock.current = false
      setIsCreating(false)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="YOUR CIRCLES"
        title="My groups"
        description="All the people and plans you share expenses with."
        action={<Button type="button" icon="plus" onClick={openCreateForm}>Create group</Button>}
      />

      {createOpen && (
        <Card className="group-form-card">
          <div className="section-heading">
            <div>
              <h2 id="create-group-title">Create a group</h2>
              <p>Start a shared space for people you know.</p>
            </div>
            <Button
              type="button"
              variant="secondary"
              onClick={() => { setCreateOpen(false); setCreateError('') }}
              disabled={isCreating}
            >
              Cancel
            </Button>
          </div>
          <form className="group-form" aria-labelledby="create-group-title" onSubmit={handleCreate} noValidate>
            <FormField
              id="group-name"
              label="Group name"
              autoComplete="off"
              placeholder="For example, Weekend trip"
              value={groupName}
              onChange={(event) => { setGroupName(event.target.value); setCreateError('') }}
              error={createError}
              inputRef={nameInput}
              maxLength={GROUP_NAME_MAX_LENGTH}
            />
            <div className="group-form__actions">
              <Button type="submit" disabled={isCreating} aria-busy={isCreating}>
                {isCreating ? 'Creating…' : 'Create group'}
              </Button>
            </div>
          </form>
        </Card>
      )}

      <InvitationInbox key={user.id} userId={user.id} onAccepted={() => setLoadAttempt(value => value + 1)} />
      {createNotice && <p className="inline-notice" role="status">{createNotice}</p>}

      {visibleData.status === 'loading' && (
        <Card><p className="loading-state" role="status">Loading your groups…</p></Card>
      )}

      {visibleData.status === 'error' && (
        <Card>
          <div className="request-error" role="alert">
            <h2>Groups could not be loaded</h2>
            <p>{visibleData.error}</p>
            <Button type="button" variant="secondary" onClick={() => setLoadAttempt((attempt) => attempt + 1)}>Try again</Button>
          </div>
        </Card>
      )}

      {visibleData.status === 'success' && visibleData.groups.length === 0 && (
        <Card>
          <EmptyState
            icon="group"
            title="No groups yet"
            description="Create your first group to start sharing plans with people you know."
            action={<Button type="button" icon="plus" onClick={openCreateForm}>Create your first group</Button>}
          />
        </Card>
      )}

      {visibleData.status === 'success' && visibleData.groups.length > 0 && (
        <div className="groups-grid" aria-label="Your groups">
          {visibleData.groups.map((group) => (
            <Card className="groups-grid__item" key={group.id}>
              <GroupCard group={group} />
            </Card>
          ))}
        </div>
      )}
    </>
  )
}
