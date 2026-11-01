import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.jsx'
import { AuthProvider } from '../context/AuthContext.jsx'
import { formatInr, normalizeMoney, parseMoneyToPaise } from '../services/expenseService.js'

const creator = { id: 8, name: 'Samira Patel', email: 'samira@example.com' }
const members = [
  { userId: 8, name: creator.name, email: creator.email },
  { userId: 9, name: 'Alex Morgan', email: 'alex@example.com' },
  { userId: 10, name: 'Riley Chen', email: 'riley@example.com' },
]
const group = { id: 42, name: 'Weekend cabin', createdByUserId: creator.id }
const token = 'expense-test-token'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

function expense(overrides = {}) {
  return {
    id: 501,
    groupId: group.id,
    description: 'Dinner',
    amount: 1000,
    currencyCode: 'INR',
    paidByUserId: creator.id,
    createdByUserId: creator.id,
    splits: [
      { userId: creator.id, amount: 333.34 },
      { userId: 9, amount: 333.33 },
      { userId: 10, amount: 333.33 },
    ],
    ...overrides,
  }
}

function groupsResponse(items = [group]) {
  return jsonResponse(items)
}

function membersResponse(items = members) {
  return jsonResponse(items)
}

function renderApp(initialPath, responses = [], user = creator) {
  window.sessionStorage.setItem('splitsmart.accessToken', token)
  fetch.mockResolvedValueOnce(jsonResponse(user))
  for (const response of responses) {
    if (typeof response === 'function') fetch.mockImplementationOnce(response)
    else fetch.mockResolvedValueOnce(response)
  }

  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

function groupSetup() {
  return [groupsResponse(), membersResponse()]
}

function expenseDetailSetup(expenseResponse = jsonResponse(expense())) {
  return [...groupSetup(), expenseResponse]
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('expense money validation', () => {
  it('uses exact paise and normalizes decimal strings without floating-point addition', () => {
    expect(parseMoneyToPaise('333.34') + parseMoneyToPaise('333.33') + parseMoneyToPaise('333.33')).toBe(100000n)
    expect(normalizeMoney('000')).toBeNull()
    expect(normalizeMoney('900.5')).toBe('900.50')
    expect(formatInr('900.05')).toBe('₹900.05')
  })

  it.each(['0', '-1', '1.001', '100000000000000000.00', 'not money'])('rejects unsafe amount %s', (value) => {
    expect(parseMoneyToPaise(value)).toBeNull()
  })
})

describe('group expense listing and details', () => {
  it('loads real expenses and displays member names and persisted amounts', async () => {
    renderApp(`/groups/${group.id}/expenses`, [...groupSetup(), jsonResponse([expense()])])

    expect(await screen.findByRole('link', { name: 'Dinner' })).toHaveAttribute('href', '/groups/42/expenses/501')
    expect(screen.getByText('₹1000.00')).toBeInTheDocument()
    expect(screen.getByText('Paid by Samira Patel')).toBeInTheDocument()
    expect(screen.getByText('Added by Samira Patel')).toBeInTheDocument()
    expect(screen.getByText('3 participants')).toBeInTheDocument()
  })

  it('shows an honest empty state and working create link', async () => {
    renderApp(`/groups/${group.id}/expenses`, [...groupSetup(), jsonResponse([])])

    expect(await screen.findByRole('heading', { name: 'No expenses yet' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Create expense' })[0]).toHaveAttribute('href', '/groups/42/expenses/new')
  })

  it('reports list failures safely and retries the expense request', async () => {
    const user = userEvent.setup()
    renderApp(`/groups/${group.id}/expenses`, [
      ...groupSetup(),
      jsonResponse({ message: 'database password and internal query' }, 500),
      jsonResponse([expense()]),
    ])

    expect(await screen.findByRole('alert')).toHaveTextContent('could not complete your request')
    expect(screen.queryByText(/database password|internal query/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: 'Dinner' })).toBeInTheDocument()
  })

  it('rejects an expense response from another group', async () => {
    renderApp(`/groups/${group.id}/expenses`, [
      ...groupSetup(),
      jsonResponse([expense({ groupId: 999, description: 'Private other group record' })]),
    ])

    expect(await screen.findByRole('alert')).toHaveTextContent('unexpected expense data')
    expect(screen.queryByText('Private other group record')).not.toBeInTheDocument()
  })

  it('shows persisted detail shares and creator-only actions', async () => {
    renderApp(`/groups/${group.id}/expenses/501`, expenseDetailSetup())

    expect(await screen.findByRole('heading', { name: 'Dinner', level: 2 })).toBeInTheDocument()
    expect(screen.getByText('Paid by').parentElement).toHaveTextContent('Samira Patel')
    expect(screen.getByText('Created by').parentElement).toHaveTextContent('Samira Patel')
    expect(screen.getByText('₹333.34')).toBeInTheDocument()
    expect(screen.getAllByText('₹333.33')).toHaveLength(2)
    expect(screen.getByRole('link', { name: 'Edit expense' })).toHaveAttribute('href', '/groups/42/expenses/501/edit')
    expect(screen.getByRole('button', { name: 'Delete expense' })).toBeInTheDocument()
    expect(screen.getByText(/Timestamp not provided/)).toBeInTheDocument()
  })

  it('does not expose edit or delete for a noncreator, including on the edit route', async () => {
    const memberUser = { id: 9, name: 'Alex Morgan', email: 'alex@example.com' }
    renderApp(`/groups/${group.id}/expenses/501/edit`, expenseDetailSetup(), memberUser)

    expect(await screen.findByText('Only the person who created this expense can edit it.')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Edit expense' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete expense' })).not.toBeInTheDocument()
  })

  it('shows a private unavailable state for a missing expense', async () => {
    renderApp(`/groups/${group.id}/expenses/999`, expenseDetailSetup(jsonResponse({ message: 'internal detail' }, 404)))

    expect(await screen.findByRole('heading', { name: 'Expense not found or unavailable' })).toBeInTheDocument()
    expect(screen.queryByText('internal detail')).not.toBeInTheDocument()
  })

  it('expires the existing session when an expense request returns 401', async () => {
    renderApp(`/groups/${group.id}/expenses`, [
      ...groupSetup(),
      jsonResponse({ message: 'credential detail' }, 401),
    ])

    expect(await screen.findByRole('heading', { name: 'Sign in to SplitSmart' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('splitsmart.accessToken')).toBeNull()
    expect(screen.queryByText('Dinner')).not.toBeInTheDocument()
  })

  it('clears private expense details after the user signs out', async () => {
    const user = userEvent.setup()
    renderApp(`/groups/${group.id}/expenses/501`, expenseDetailSetup())
    expect(await screen.findByRole('heading', { name: 'Dinner', level: 2 })).toBeInTheDocument()

    await user.click(screen.getAllByRole('button', { name: 'Sign out' })[0])
    expect(await screen.findByRole('heading', { name: 'Sign in to SplitSmart' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Dinner', level: 2 })).not.toBeInTheDocument()
  })
})

describe('expense creation', () => {
  it('creates an equal split with participant IDs and permits a payer outside the split', async () => {
    const user = userEvent.setup()
    const created = expense({ id: 502, description: 'Shared lunch', amount: 20, paidByUserId: 10, splits: [{ userId: 8, amount: 10 }, { userId: 9, amount: 10 }] })
    renderApp(`/groups/${group.id}/expenses/new`, [
      ...groupSetup(),
      jsonResponse(created, 201),
      ...expenseDetailSetup(jsonResponse(created)),
    ])

    const form = await screen.findByRole('form', { name: 'Create expense' })
    await user.type(within(form).getByLabelText(/Description/), '  Shared lunch  ')
    await user.type(within(form).getByLabelText(/Total amount/), '20.00')
    await user.selectOptions(within(form).getByLabelText(/Paid by/), '10')
    await user.click(within(form).getByLabelText('Riley Chen'))
    await user.click(within(form).getByRole('button', { name: 'Save expense' }))

    expect(await screen.findByRole('heading', { name: 'Shared lunch', level: 2 })).toBeInTheDocument()
    const post = fetch.mock.calls.find(([url, options]) => url.endsWith('/api/groups/42/expenses') && options.method === 'POST')
    expect(post[1].body).toBe(JSON.stringify({
      description: 'Shared lunch',
      amount: '20.00',
      paidByUserId: 10,
      currencyCode: 'INR',
      splitType: 'EQUAL',
      participantUserIds: [8, 9],
    }))
    expect(post[1].headers.Authorization).toBe(`Bearer ${token}`)
  })

  it('creates EXACT shares as normalized decimal strings', async () => {
    const user = userEvent.setup()
    const created = expense({
      amount: 900,
      splits: [{ userId: 8, amount: 400 }, { userId: 9, amount: 300 }, { userId: 10, amount: 200 }],
    })
    renderApp(`/groups/${group.id}/expenses/new`, [
      ...groupSetup(),
      jsonResponse(created, 201),
      ...expenseDetailSetup(jsonResponse(created)),
    ])

    const form = await screen.findByRole('form', { name: 'Create expense' })
    await user.type(within(form).getByLabelText(/Description/), 'Dinner')
    await user.type(within(form).getByLabelText(/Total amount/), '900')
    await user.click(within(form).getByLabelText('Custom amounts'))
    await user.type(within(form).getByLabelText('Share for Samira Patel'), '400.00')
    await user.type(within(form).getByLabelText('Share for Alex Morgan'), '300')
    await user.type(within(form).getByLabelText('Share for Riley Chen'), '200')
    await user.click(within(form).getByRole('button', { name: 'Save expense' }))

    expect(await screen.findByRole('heading', { name: 'Dinner', level: 2 })).toBeInTheDocument()
    const post = fetch.mock.calls.find(([url, options]) => url.endsWith('/api/groups/42/expenses') && options.method === 'POST')
    expect(JSON.parse(post[1].body)).toEqual({
      description: 'Dinner',
      amount: '900.00',
      paidByUserId: creator.id,
      currencyCode: 'INR',
      splitType: 'EXACT',
      splits: [{ userId: 8, amount: '400.00' }, { userId: 9, amount: '300.00' }, { userId: 10, amount: '200.00' }],
    })
  })

  it.each([
    { description: '', amount: '10', expected: 'Enter an expense description.' },
    { description: 'Lunch', amount: '0', expected: 'positive amount' },
    { description: 'Lunch', amount: '-1', expected: 'positive amount' },
    { description: 'Lunch', amount: '1.001', expected: 'positive amount' },
  ])('rejects invalid input without making an API request: $expected', async ({ description, amount, expected }) => {
    const user = userEvent.setup()
    renderApp(`/groups/${group.id}/expenses/new`, groupSetup())
    const form = await screen.findByRole('form', { name: 'Create expense' })
    if (description)     await user.type(within(form).getByLabelText(/Description/), description)
    await user.type(within(form).getByLabelText(/Total amount/), amount)
    await user.click(within(form).getByRole('button', { name: 'Save expense' }))

    expect(await within(form).findByRole('alert')).toHaveTextContent(expected)
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('rejects exact shares that differ by one paisa', async () => {
    const user = userEvent.setup()
    renderApp(`/groups/${group.id}/expenses/new`, groupSetup())
    const form = await screen.findByRole('form', { name: 'Create expense' })
    await user.type(within(form).getByLabelText(/Description/), 'Dinner')
    await user.type(within(form).getByLabelText(/Total amount/), '900')
    await user.click(within(form).getByLabelText('Custom amounts'))
    await user.type(within(form).getByLabelText('Share for Samira Patel'), '400')
    await user.type(within(form).getByLabelText('Share for Alex Morgan'), '300')
    await user.type(within(form).getByLabelText('Share for Riley Chen'), '199.99')
    await user.click(within(form).getByRole('button', { name: 'Save expense' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Allocated shares total ₹899.99')
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('prevents duplicate create requests while the first POST is pending', async () => {
    const user = userEvent.setup()
    let finishPost
    const created = expense({ id: 503 })
    renderApp(`/groups/${group.id}/expenses/new`, [
      ...groupSetup(),
      () => new Promise((resolve) => { finishPost = resolve }),
      ...expenseDetailSetup(jsonResponse(created)),
    ])
    const form = await screen.findByRole('form', { name: 'Create expense' })
    await user.type(within(form).getByLabelText(/Description/), 'Quick meal')
    await user.type(within(form).getByLabelText(/Total amount/), '30')
    const submit = within(form).getByRole('button', { name: 'Save expense' })
    await user.click(submit)
    expect(submit).toBeDisabled()
    await user.click(submit)
    expect(fetch).toHaveBeenCalledTimes(4)

    finishPost(jsonResponse(created, 201))
    expect(await screen.findByRole('heading', { name: 'Dinner', level: 2 })).toBeInTheDocument()
  })
})

describe('expense editing and deletion', () => {
  it('prepopulates existing persisted shares in EXACT mode instead of inferring EQUAL', async () => {
    renderApp(`/groups/${group.id}/expenses/501/edit`, expenseDetailSetup())

    const form = await screen.findByRole('form', { name: 'Edit expense' })
    expect(within(form).getByLabelText('Custom amounts')).toBeChecked()
    expect(within(form).getByLabelText('Share for Samira Patel')).toHaveValue('333.34')
    expect(within(form).getByLabelText('Share for Alex Morgan')).toHaveValue('333.33')
    expect(within(form).getByLabelText('Share for Riley Chen')).toHaveValue('333.33')
    expect(within(form).getByLabelText(/Total amount/)).toHaveValue('1000.00')
  })

  it('sends an explicit EQUAL update to the existing expense URL', async () => {
    const user = userEvent.setup()
    const updated = expense({
      description: 'Updated dinner',
      amount: 1000,
      splits: [{ userId: 8, amount: 500 }, { userId: 9, amount: 500 }],
    })
    renderApp(`/groups/${group.id}/expenses/501/edit`, [
      ...expenseDetailSetup(),
      jsonResponse(updated),
      ...expenseDetailSetup(jsonResponse(updated)),
    ])
    const form = await screen.findByRole('form', { name: 'Edit expense' })
    await user.clear(within(form).getByLabelText(/Description/))
    await user.type(within(form).getByLabelText(/Description/), 'Updated dinner')
    await user.click(within(form).getByLabelText('Equal split'))
    await user.click(within(form).getByLabelText('Riley Chen'))
    await user.click(within(form).getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByRole('heading', { name: 'Updated dinner', level: 2 })).toBeInTheDocument()
    const put = fetch.mock.calls.find(([url, options]) => url.endsWith('/api/groups/42/expenses/501') && options.method === 'PUT')
    expect(JSON.parse(put[1].body)).toMatchObject({
      description: 'Updated dinner',
      splitType: 'EQUAL',
      participantUserIds: [8, 9],
    })
    expect(put[1].body).not.toContain('"splits"')
  })

  it('cancels the accessible delete confirmation without sending a request', async () => {
    const user = userEvent.setup()
    renderApp(`/groups/${group.id}/expenses/501`, expenseDetailSetup())
    await screen.findByRole('heading', { name: 'Dinner', level: 2 })
    await user.click(screen.getByRole('button', { name: 'Delete expense' }))
    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByText(/permanently deletes/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
  })

  it('keeps expense details when a DELETE fails and hides internal response text', async () => {
    const user = userEvent.setup()
    renderApp(`/groups/${group.id}/expenses/501`, [
      ...expenseDetailSetup(),
      jsonResponse({ message: 'SQL internal details' }, 500),
    ])
    await screen.findByRole('heading', { name: 'Dinner', level: 2 })
    await user.click(screen.getByRole('button', { name: 'Delete expense' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete expense' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('could not complete your request')
    expect(screen.getByRole('heading', { name: 'Dinner', level: 2 })).toBeInTheDocument()
    expect(screen.queryByText('SQL internal details')).not.toBeInTheDocument()
  })

  it('deletes only after server success and returns to the refreshed expense list', async () => {
    const user = userEvent.setup()
    renderApp(`/groups/${group.id}/expenses/501`, [
      ...expenseDetailSetup(),
      { ok: true, status: 204, json: async () => null },
      ...groupSetup(),
      jsonResponse([]),
    ])
    await screen.findByRole('heading', { name: 'Dinner', level: 2 })
    await user.click(screen.getByRole('button', { name: 'Delete expense' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete expense' }))

    expect(await screen.findByRole('heading', { name: 'No expenses yet' })).toBeInTheDocument()
    expect(await screen.findByRole('status')).toHaveTextContent('Expense deleted successfully')
    expect(fetch.mock.calls.some(([url, options]) => url.endsWith('/api/groups/42/expenses/501') && options.method === 'DELETE')).toBe(true)
  })
})
