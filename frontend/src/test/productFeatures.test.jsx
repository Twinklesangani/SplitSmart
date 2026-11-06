import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import DashboardPage from '../pages/DashboardPage.jsx'
import GroupManagement from '../pages/GroupManagement.jsx'
import ExpenseMetadata from '../components/ExpenseMetadata.jsx'
import { expenseDateLabel } from '../services/expenseMetadata.js'
import { apiRequest } from '../services/apiClient.js'

vi.mock('../context/useAuth.js', () => ({ useAuth: () => ({ user: { id: 1, name: 'Product Owner' } }) }))
vi.mock('../services/apiClient.js', async importOriginal => ({ ...(await importOriginal()), apiRequest: vi.fn() }))
const originalShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
beforeEach(() => vi.resetAllMocks())
afterEach(() => {
  vi.restoreAllMocks()
  if (originalShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', originalShowModal)
  else delete HTMLDialogElement.prototype.showModal
})

it('shows authenticated totals exactly with real expense and repayment activity', async () => {
  apiRequest.mockResolvedValue({ groups: [{ id: 4, name: 'Weekend', createdByUserId: 1 }],
    totalExpenses: '9007199254740993.01', owed: '5.00', receivable: '7.00', activity: [
      { type: 'EXPENSE', id: 2, groupId: 4, groupName: 'Weekend', description: 'Train', amount: '20.00', expenseDate: '2024-06-10', category: 'TRAVEL' },
      { type: 'SETTLEMENT', id: 3, groupId: 4, description: 'Member repaid Owner', amount: '2.00', recordedAt: '2024-06-11T10:00:00' },
    ] })
  render(<MemoryRouter><DashboardPage /></MemoryRouter>)
  expect(await screen.findByText('₹9007199254740993.01')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Train' })).toHaveAttribute('href', '/groups/4/expenses/2')
  expect(screen.getByRole('link', { name: 'Member repaid Owner' })).toHaveAttribute('href', '/groups/4/settlements')
  expect(screen.queryByText(/Available after/)).not.toBeInTheDocument()
})

it('shows a recoverable dashboard error for malformed financial data instead of fabricated amounts', async () => {
  apiRequest.mockResolvedValue({ groups: [], activity: [], totalExpenses: null, owed: '0', receivable: '0' })
  render(<MemoryRouter><DashboardPage /></MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('unexpected dashboard data')
  expect(screen.queryByText('₹0.00')).not.toBeInTheDocument()
})

it('displays an expense date and category without timezone shifts', () => {
  render(<ExpenseMetadata expense={{ expenseDate: '2024-06-10', category: 'FOOD_DRINKS' }} />)
  expect(screen.getByText('Food & Drinks')).toBeInTheDocument()
  expect(screen.getByText(expenseDateLabel('2024-06-10'))).toHaveAttribute('datetime', '2024-06-10')
})

it('requires confirmation before archiving and refreshes after server approval', async () => {
  const user = userEvent.setup()
  const reload = vi.fn()
  apiRequest.mockResolvedValue(null)
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true,
    value() { this.setAttribute('open', '') },
  })
  render(<MemoryRouter><GroupManagement group={{ id: 4, name: 'Weekend', createdByUserId: 1 }} members={[]}
    user={{ id: 1 }} reload={reload} /></MemoryRouter>)
  await user.click(screen.getByRole('button', { name: 'Archive group…' }))
  expect(apiRequest).not.toHaveBeenCalled()
  const dialog = screen.getByRole('dialog')
  await user.click(within(dialog).getByRole('button', { name: 'Confirm' }))
  await waitFor(() => expect(reload).toHaveBeenCalledOnce())
  expect(apiRequest).toHaveBeenCalledWith('/api/groups/4', { method: 'DELETE' })
})
