import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { apiRequest } from '../services/apiClient.js'
import './notifications.css'

export default function NotificationBell() {
  const location = useLocation()
  const [panel, setPanel] = useState({ open: false, pathname: '' })
  const [data, setData] = useState({ status: 'idle', items: [], nextBefore: null })
  const [count, setCount] = useState(null)
  const [message, setMessage] = useState('')
  const trigger = useRef(null)
  const root = useRef(null)
  const heading = useRef(null)
  const request = useRef(null)
  const opened = useRef(false)
  const pending = useRef(new Set())
  const open = panel.open && panel.pathname === location.pathname

  async function load(before) {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setData(old => ({ ...old, status: 'loading' })); setMessage('')
    try {
      const result = await apiRequest(`/api/notifications${before ? `?before=${before}` : ''}`, { signal: controller.signal })
      if (!Array.isArray(result.items) || !Number.isSafeInteger(result.unreadCount) || result.unreadCount < 0
        || result.nextBefore != null && (!Number.isSafeInteger(result.nextBefore) || result.nextBefore < 1)
        || result.items.some(item => !Number.isSafeInteger(item.id) || typeof item.title !== 'string' || typeof item.read !== 'boolean'
          || !Number.isFinite(Date.parse(item.createdAt)) || !/^\/groups(?:\/[1-9]\d*(?:\/expenses\/[1-9]\d*|\/settlements)?)?$/.test(item.href))) {
        throw new Error('Notifications could not be loaded. Please try again.')
      }
      if (!controller.signal.aborted) {
        setData(old => ({ status: 'success', items: before ? [...old.items, ...result.items] : result.items, nextBefore: result.nextBefore }))
        setCount(result.unreadCount)
      }
    } catch (error) { if (!controller.signal.aborted) setData(old => ({ ...old, status: 'error', error: error.message })) }
  }

  useEffect(() => {
    async function refreshCount() {
      if (!opened.current || document.visibilityState !== 'visible') return
      try {
        const result = await apiRequest('/api/notifications/unread-count')
        if (Number.isSafeInteger(result.count) && result.count >= 0) setCount(result.count)
      } catch { /* Keep last known count. Opening the panel exposes errors and a retry action. */ }
    }
    const interval = window.setInterval(refreshCount, 60000)
    window.addEventListener('focus', refreshCount)
    return () => { window.clearInterval(interval); window.removeEventListener('focus', refreshCount); request.current?.abort() }
  }, [])

  useEffect(() => {
    if (!open) return undefined
    heading.current?.focus()
    function key(event) {
      if (event.key === 'Escape') { setPanel({ open: false, pathname: location.pathname }); trigger.current?.focus() }
    }
    function outside(event) { if (!root.current?.contains(event.target)) setPanel({ open: false, pathname: location.pathname }) }
    document.addEventListener('keydown', key)
    document.addEventListener('pointerdown', outside)
    return () => { document.removeEventListener('keydown', key); document.removeEventListener('pointerdown', outside) }
  }, [open, location.pathname])

  function toggle() {
    if (open) { setPanel({ open: false, pathname: location.pathname }); return }
    opened.current = true
    setPanel({ open: true, pathname: location.pathname })
    load()
  }
  async function mark(item, read) {
    if (pending.current.has(item.id)) return
    pending.current.add(item.id)
    try {
      await apiRequest(`/api/notifications/${item.id}`, { method: 'PUT', body: { read }, expectedStatus: 204 })
      setData(old => ({ ...old, items: old.items.map(value => value.id === item.id ? { ...value, read } : value) }))
      setCount(old => old === null ? null : Math.max(0, old + (read ? -1 : 1)))
    } catch (error) { setMessage(error.message) }
    finally { pending.current.delete(item.id) }
  }

  return <div className="notification-root" ref={root}>
    <button type="button" className="notification-trigger" aria-label={count === null ? 'Notifications, open to check' : `Notifications, ${count} unread`}
      aria-expanded={open} aria-controls="notification-panel" ref={trigger} onClick={toggle}>
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></svg>
      {count > 0 && <span className="notification-count" aria-hidden="true">{count > 99 ? '99+' : count}</span>}
    </button>
    {open && <section className="notification-panel" id="notification-panel" aria-labelledby="notification-title">
      <div className="notification-heading"><h2 id="notification-title" tabIndex={-1} ref={heading}>Notifications</h2>
        <button type="button" aria-label="Close notifications" onClick={() => { setPanel({ open: false, pathname: location.pathname }); trigger.current?.focus() }}>Close</button></div>
      <p className="notification-note">Your latest 200 updates, kept for up to 90 days.</p>
      {data.status === 'loading' && <p role="status">Loading notifications…</p>}
      {data.status === 'error' && <div role="alert"><p>{data.error}</p><button type="button" onClick={() => load()}>Try again</button></div>}
      {data.status === 'success' && !data.items.length && <p>You’re all caught up. New group activity will appear here.</p>}
      {message && <p role="alert">{message}</p>}
      <ul>{data.items.map(item => <li key={item.id} className={item.read ? '' : 'is-unread'}>
        <Link to={item.href} onClick={() => { if (!item.read) mark(item, true) }}>{item.title}</Link>
        <time dateTime={item.createdAt}>{new Date(item.createdAt.endsWith('Z') ? item.createdAt : `${item.createdAt}Z`).toLocaleString()}</time>
        <button type="button" aria-label={`Mark ${item.title} as ${item.read ? 'unread' : 'read'}`} onClick={() => mark(item, !item.read)}>Mark {item.read ? 'unread' : 'read'}</button>
      </li>)}</ul>
      {data.nextBefore && <button type="button" disabled={data.status === 'loading'} onClick={() => load(data.nextBefore)}>Older notifications</button>}
    </section>}
  </div>
}
