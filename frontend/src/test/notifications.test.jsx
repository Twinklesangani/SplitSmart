import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import NotificationBell from '../components/NotificationBell.jsx'
import { apiRequest } from '../services/apiClient.js'

vi.mock('../services/apiClient.js', () => ({ apiRequest: vi.fn() }))
const item = { id: 7, type: 'EXPENSE', title: 'A new shared expense was added', read: false, createdAt: '2026-11-06T08:00:00', href: '/groups/42/expenses/9' }
beforeEach(() => vi.resetAllMocks())
function renderBell() { return render(<MemoryRouter><NotificationBell /><button>Outside panel</button></MemoryRouter>) }

it('refetches on open, shows the real unread count, and returns focus on Escape', async () => {
  const user = userEvent.setup()
  apiRequest.mockResolvedValue({ items: [item], unreadCount: 1, nextBefore: null })
  renderBell()
  expect(apiRequest).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: /Notifications/ }))
  expect(await screen.findByRole('link', { name: item.title })).toHaveAttribute('href', item.href)
  expect(screen.getByRole('heading', { name: 'Notifications' })).toHaveFocus()
  expect(screen.getByRole('button', { name: 'Notifications, 1 unread' })).toHaveAttribute('aria-expanded', 'true')
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('heading', { name: 'Notifications' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Notifications, 1 unread' })).toHaveFocus()
  await user.click(screen.getByRole('button', { name: /Notifications/ }))
  await waitFor(() => expect(apiRequest).toHaveBeenCalledTimes(2))
})

it('marks individual updates read and unread with accurate local count feedback', async () => {
  const user = userEvent.setup()
  apiRequest.mockResolvedValueOnce({ items: [item], unreadCount: 1 }).mockResolvedValue(null)
  renderBell()
  await user.click(screen.getByRole('button', { name: /Notifications/ }))
  await user.click(await screen.findByRole('button', { name: `Mark ${item.title} as read` }))
  expect(await screen.findByRole('button', { name: 'Notifications, 0 unread' })).toBeInTheDocument()
  expect(apiRequest).toHaveBeenLastCalledWith('/api/notifications/7', { method: 'PUT', body: { read: true }, expectedStatus: 204 })
  await user.click(screen.getByRole('button', { name: `Mark ${item.title} as unread` }))
  await screen.findByRole('button', { name: 'Notifications, 1 unread' })
})

it('offers retry for API errors and never treats a failure as an empty inbox', async () => {
  const user = userEvent.setup()
  apiRequest.mockRejectedValueOnce(new Error('Notifications unavailable')).mockResolvedValue({ items: [], unreadCount: 0 })
  renderBell()
  await user.click(screen.getByRole('button', { name: /Notifications/ }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Notifications unavailable')
  expect(screen.queryByText(/all caught up/)).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByText(/all caught up/)).toBeInTheDocument()
})

it('rejects external navigation and malformed pagination and closes on outside click', async () => {
  const user = userEvent.setup()
  apiRequest.mockResolvedValue({ items: [{ ...item, href: 'https://untrusted.example' }], unreadCount: 1, nextBefore: 'invalid' })
  renderBell()
  await user.click(screen.getByRole('button', { name: /Notifications/ }))
  await screen.findByRole('alert')
  expect(screen.queryByRole('link', { name: item.title })).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Outside panel' }))
  expect(screen.queryByRole('heading', { name: 'Notifications' })).not.toBeInTheDocument()
})
