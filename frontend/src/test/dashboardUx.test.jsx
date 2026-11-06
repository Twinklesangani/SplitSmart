import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import DashboardPage from '../pages/DashboardPage.jsx'
import AppShell from '../layouts/AppShell.jsx'
import { apiRequest } from '../services/apiClient.js'

vi.mock('../context/useAuth.js', () => ({ useAuth: () => ({ user: { id: 1, name: 'Product Owner', email: 'owner@example.invalid' }, logout: vi.fn() }) }))
vi.mock('../services/apiClient.js', async original => ({ ...(await original()), apiRequest: vi.fn() }))
const dashboard = { groups: [{ id: 1, name: 'Home' }, { id: 2, name: 'Trip' }], totalExpenses: '30.00', owed: '20.00', receivable: '5.00', netPosition: '-15.00', groupBalances: [{ groupId: 1, netBalance: '5.00' }, { groupId: 2, netBalance: '-20.00' }], activity: [] }
beforeEach(() => vi.resetAllMocks())
afterEach(() => vi.restoreAllMocks())

it('keeps owed and receivable separate, shows net and group balances and lets users select the expense group', async () => {
  const user = userEvent.setup()
  apiRequest.mockResolvedValue(dashboard)
  render(<MemoryRouter><DashboardPage /></MemoryRouter>)
  const net = await screen.findByText('Net position')
  expect(within(net.parentElement).getByText('−₹15.00')).toBeInTheDocument()
  expect(screen.getByText('You owe ₹20.00')).toBeInTheDocument()
  expect(screen.getByText('You are owed ₹5.00')).toBeInTheDocument()
  await user.selectOptions(screen.getByLabelText('Add an expense in'), '2')
  expect(screen.getByRole('link', { name: 'Add expense' })).toHaveAttribute('href', '/groups/2/expenses/new')
  expect(screen.getByRole('link', { name: 'Create group' })).toHaveAttribute('href', '/groups?create=1')
})

it('onboards an empty account without invented activity', async () => {
  apiRequest.mockResolvedValue({ groups: [], activity: [], owed: '0.00', receivable: '0.00', totalExpenses: '0.00', netPosition: '0.00', groupBalances: [] })
  render(<MemoryRouter><DashboardPage /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: 'Start sharing, one group at a time' })).toBeInTheDocument()
  expect(screen.getByText('Share a private link; they choose whether to join.')).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Add expense' })).not.toBeInTheDocument()
})

it('rejects inconsistent net values and retries to real data', async () => {
  const user = userEvent.setup()
  apiRequest.mockResolvedValueOnce({ ...dashboard, netPosition: '99.00' }).mockResolvedValue(dashboard)
  render(<MemoryRouter><DashboardPage /></MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('unexpected dashboard data')
  expect(screen.queryByText('₹99.00')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  await screen.findByText('You owe ₹20.00')
})

it('traps drawer focus, isolates the background and restores the trigger after Escape', async () => {
  const user = userEvent.setup()
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{ width: 1, height: 1 }])
  vi.stubGlobal('innerWidth', 375)
  render(<MemoryRouter><AppShell /></MemoryRouter>)
  await user.click(screen.getByRole('button', { name: 'Open navigation' }))
  const dialog = screen.getByRole('dialog', { name: 'Navigation' })
  expect(screen.getByRole('main', { hidden: true })).toHaveAttribute('inert')
  const signOut = within(dialog).getByRole('button', { name: 'Sign out' })
  signOut.focus()
  await user.tab()
  expect(within(dialog).getByRole('link', { name: 'SplitSmart home' })).toHaveFocus()
  await user.keyboard('{Escape}')
  await waitFor(() => expect(screen.getByRole('button', { name: 'Open navigation' })).toHaveFocus())
  expect(screen.getByRole('main')).not.toHaveAttribute('inert')
  expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main-content')
})
