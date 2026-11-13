import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.jsx'
import { AuthProvider } from '../context/AuthContext.jsx'

const creator = { id: 8, name: 'Samira Patel', email: 'samira@example.com' }
const member = { userId: 9, name: 'Alex Morgan', email: 'alex@example.com' }
const secondMember = { userId: 10, name: 'Riley Chen', email: 'riley@example.com' }
const group = { id: 42, name: 'Weekend cabin', createdByUserId: creator.id }
const placeholderToken = 'test-session-token'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

function renderApp(initialPath = '/groups', responses = []) {
  window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
  fetch.mockResolvedValueOnce(jsonResponse(creator))
  for (const response of responses) {
    if (typeof response === 'function') fetch.mockImplementationOnce(response)
    else fetch.mockResolvedValueOnce(response)
  }

  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

function groupsResponse(groups = [group]) {
  return jsonResponse(groups)
}

function membersResponse(members = [creatorMember()]) {
  return jsonResponse(members)
}

function creatorMember() {
  return { userId: creator.id, name: creator.name, email: creator.email }
}

beforeEach(() => {
  vi.stubEnv('VITE_INVITATION_EMAIL_ENABLED', 'true')
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('authenticated group management', () => {
  it('loads real groups and navigates to the matching group ID', async () => {
    const user = userEvent.setup()
    renderApp('/groups', [groupsResponse([group]), groupsResponse([group]), membersResponse()])

    const groupLink = await screen.findByRole('link', { name: /Weekend cabin/ })
    expect(groupLink).toHaveAttribute('href', '/groups/42')

    await user.click(groupLink)
    expect(await screen.findByRole('heading', { name: 'Weekend cabin', level: 1 })).toBeInTheDocument()
    expect(await screen.findByText('42', { selector: '.meta-line strong' })).toBeInTheDocument()
  })

  it('shows loading instead of a premature empty state', async () => {
    let finishGroups
    renderApp('/groups', [() => new Promise((resolve) => { finishGroups = resolve })])
    expect(await screen.findByRole('status')).toHaveTextContent('Loading your groups')
    await waitFor(() => expect(finishGroups).toBeTypeOf('function'))
    expect(screen.queryByRole('heading', { name: 'No groups yet' })).not.toBeInTheDocument()

    finishGroups(jsonResponse([group]))
    expect(await screen.findByRole('link', { name: /Weekend cabin/ })).toBeInTheDocument()
  })

  it('shows a real empty state for an empty group list', async () => {
    renderApp('/groups', [groupsResponse([])])
    expect(await screen.findByRole('heading', { name: 'No groups yet' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create your first group' })).toBeInTheDocument()
  })

  it('shows real groups and counts on the dashboard without inventing financial totals', async () => {
    renderApp('/dashboard', [jsonResponse({ groups: [group, { ...group, id: 43, name: 'Book club' }],
      totalExpenses: '30.00', owed: '5.00', receivable: '10.00', activity: [] })])

    expect(await screen.findByRole('link', { name: /Weekend cabin/ })).toBeInTheDocument()
    expect(screen.getByText('(2 active)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Book club/ })).toHaveAttribute('href', '/groups/43')
    expect(screen.getByText('₹30.00', { selector: '.dashboard-ledger dd' })).toBeInTheDocument()
    expect(within(screen.getByText('You owe', { selector: '.dashboard-ledger dt' }).parentElement).getByText('₹5.00')).toBeInTheDocument()
    expect(screen.getByText('₹10.00', { selector: '.dashboard-ledger dd' })).toBeInTheDocument()
    expect(screen.queryByText('$1,240.00')).not.toBeInTheDocument()
  })

  it('shows an API error and retries the group request', async () => {
    const user = userEvent.setup()
    renderApp('/groups', [
      jsonResponse({ message: 'do not reveal this detail' }, 500),
      groupsResponse([group]),
    ])

    expect(await screen.findByRole('alert')).toHaveTextContent('Groups could not be loaded')
    expect(screen.queryByText('do not reveal this detail')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: /Weekend cabin/ })).toBeInTheDocument()
  })

  it('rejects a blank group name without making a request', async () => {
    const user = userEvent.setup()
    renderApp('/groups', [groupsResponse([])])
    await user.click(await screen.findByRole('button', { name: 'Create group' }))
    const form = screen.getByRole('form', { name: 'Create a group' })
    await user.click(within(form).getByRole('button', { name: 'Create group' }))

    expect(await screen.findByText('Enter a group name.')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('creates a group with a trimmed name and opens its real details', async () => {
    const user = userEvent.setup()
    const createdGroup = { id: 73, name: 'Summer trip', createdByUserId: creator.id }
    renderApp('/groups', [
      groupsResponse([]),
      jsonResponse(createdGroup, 201),
      groupsResponse([createdGroup]),
      membersResponse([creatorMember()]),
    ])

    await user.click(await screen.findByRole('button', { name: 'Create group' }))
    const form = screen.getByRole('form', { name: 'Create a group' })
    await user.type(within(form).getByLabelText('Group name'), '  Summer trip  ')
    await user.click(within(form).getByRole('button', { name: 'Create group' }))

    expect(await screen.findByRole('heading', { name: 'Summer trip', level: 1 })).toBeInTheDocument()
    expect(fetch).toHaveBeenNthCalledWith(3, 'http://localhost:8081/api/groups', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ Authorization: `Bearer ${placeholderToken}` }),
      body: JSON.stringify({ name: 'Summer trip' }),
    }))
  })

  it('prevents duplicate group submissions while the create request is pending', async () => {
    const user = userEvent.setup()
    let finishCreate
    window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
    fetch.mockResolvedValueOnce(jsonResponse(creator))
    fetch.mockResolvedValueOnce(groupsResponse([]))
    fetch.mockImplementationOnce(() => new Promise((resolve) => { finishCreate = resolve }))
    render(
      <MemoryRouter initialEntries={['/groups']}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('button', { name: 'Create group' }))
    const form = screen.getByRole('form', { name: 'Create a group' })
    await user.type(within(form).getByLabelText('Group name'), 'One group')
    const submit = within(form).getByRole('button', { name: 'Create group' })
    await user.click(submit)
    expect(submit).toBeDisabled()
    await user.click(submit)
    expect(fetch).toHaveBeenCalledTimes(3)

    finishCreate(jsonResponse({ id: 74, name: 'One group', createdByUserId: creator.id }, 201))
    expect(await screen.findByRole('heading', { name: 'Group details unavailable', level: 1 })).toBeInTheDocument()
  })

  it('shows real group members and creator-only add-member controls', async () => {
    renderApp('/groups/42', [
      groupsResponse([group]),
      membersResponse([creatorMember(), member, secondMember]),
    ])

    expect(await screen.findByRole('heading', { name: 'Weekend cabin', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('3', { selector: '.meta-line strong' })).toBeInTheDocument()
    expect(screen.getByText('Alex Morgan', { selector: '.member-card strong' })).toBeInTheDocument()
    expect(screen.getByText('alex@example.com')).toBeInTheDocument()
    expect(screen.getByText('Riley Chen', { selector: '.member-card strong' })).toBeInTheDocument()
    expect(screen.getAllByText('Creator')).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Create invitation' })).toBeInTheDocument()
  })

  it('adds an existing member by email and refreshes the member list', async () => {
    const user = userEvent.setup()
    const added = { userId: member.userId, name: member.name, email: member.email }
    renderApp('/groups/42', [
      groupsResponse([group]),
      membersResponse([creatorMember()]),
      jsonResponse(added, 201),
      membersResponse([creatorMember(), added]),
    ])

    await screen.findByRole('button', { name: 'Create invitation' })
    const form = screen.getByLabelText('Invitation email address').closest('form')
    await user.type(within(form).getByLabelText('Invitation email address'), '  ALEX@EXAMPLE.COM ')
    await user.click(within(form).getByRole('button', { name: 'Create invitation' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Invitation created for seven days.')
    expect(screen.queryByText('alex@example.com', { selector: '.member-card span' })).not.toBeInTheDocument()
    expect(fetch).toHaveBeenNthCalledWith(4, 'http://localhost:8081/api/groups/42/invitations', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ email: 'alex@example.com' }),
    }))
  })

  it('shows safe conflict feedback when the member already exists', async () => {
    const user = userEvent.setup()
    renderApp('/groups/42', [
      groupsResponse([group]),
      membersResponse([creatorMember(), member]),
      jsonResponse({ code: 'conflict', message: 'database constraint details' }, 409),
    ])

    await screen.findByRole('button', { name: 'Create invitation' })
    const form = screen.getByLabelText('Invitation email address').closest('form')
    await user.type(within(form).getByLabelText('Invitation email address'), member.email)
    await user.click(within(form).getByRole('button', { name: 'Create invitation' }))

    expect(await screen.findByRole('status')).toHaveTextContent('already a group member')
    expect(screen.queryByText('database constraint details')).not.toBeInTheDocument()
  })

  it('handles a forbidden member addition without exposing backend details', async () => {
    const user = userEvent.setup()
    renderApp('/groups/42', [
      groupsResponse([group]),
      membersResponse([creatorMember()]),
      jsonResponse({ code: 'forbidden', message: 'internal authorization detail' }, 403),
    ])

    await screen.findByRole('button', { name: 'Create invitation' })
    const form = screen.getByLabelText('Invitation email address').closest('form')
    await user.type(within(form).getByLabelText('Invitation email address'), member.email)
    await user.click(within(form).getByRole('button', { name: 'Create invitation' }))

    expect(await screen.findByRole('status')).toHaveTextContent('You do not have permission')
    expect(screen.queryByText('internal authorization detail')).not.toBeInTheDocument()
  })

  it('does not expose add-member controls to a non-creator', async () => {
    const otherMember = { userId: member.userId, name: member.name, email: member.email }
    renderApp('/groups/42', [
      groupsResponse([{ ...group, createdByUserId: 9 }]),
      membersResponse([otherMember, creatorMember()]),
    ])

    expect(await screen.findByRole('heading', { name: 'Weekend cabin', level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create invitation' })).not.toBeInTheDocument()
  })

  it('shows a privacy-preserving not-found state for an inaccessible group', async () => {
    renderApp('/groups/999', [groupsResponse([])])

    expect(await screen.findByRole('heading', { name: 'Group not found or unavailable' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Back to My groups/ })).toHaveAttribute('href', '/groups')
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('clears protected group content when the group API reports an expired session', async () => {
    renderApp('/groups', [jsonResponse({ code: 'unauthorized' }, 401)])

    expect(await screen.findByRole('heading', { name: 'Sign in to SplitSmart' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'No groups yet' })).not.toBeInTheDocument()
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBeNull()
  })

  it('does not show a previous account’s groups after switching accounts', async () => {
    const user = userEvent.setup()
    const otherProfile = { id: 19, name: 'Jordan Lee', email: 'jordan@example.com' }
    const otherToken = 'second-test-session-token'
    window.sessionStorage.setItem('splitsmart.accessToken', placeholderToken)
    fetch.mockImplementation((url, options) => {
      if (url.endsWith('/api/auth/me')) {
        return jsonResponse(options.headers.Authorization === `Bearer ${otherToken}` ? otherProfile : creator)
      }
      if (url.endsWith('/api/auth/login')) {
        return jsonResponse({ accessToken: otherToken, tokenType: 'Bearer', expiresIn: 3600 })
      }
      if (url.endsWith('/api/groups')) {
        return jsonResponse(options.headers.Authorization === `Bearer ${otherToken}` ? [] : [group])
      }
      return jsonResponse([])
    })
    render(
      <MemoryRouter initialEntries={['/groups']}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('link', { name: /Weekend cabin/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    await user.type(await screen.findByLabelText('Email address'), otherProfile.email)
    await user.type(screen.getByLabelText('Password'), 'not-a-real-password')
    await user.click(screen.getByRole('button', { name: /sign in/i }))
    expect(await screen.findAllByText('Jordan Lee')).not.toHaveLength(0)
    await user.click(screen.getByRole('link', { name: 'My groups' }))

    expect(await screen.findByRole('heading', { name: 'No groups yet' })).toBeInTheDocument()
    expect(screen.queryByText('Weekend cabin')).not.toBeInTheDocument()
  })
})
