import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import Icon from '../components/Icon.jsx'
import { useAuth } from '../context/useAuth.js'
import NotificationBell from '../components/NotificationBell.jsx'

const primaryLinks = [
  { to: '/dashboard', label: 'Dashboard', icon: 'home', end: true },
  { to: '/groups', label: 'My groups', icon: 'group' },
  { to: '/settings', label: 'Settings', icon: 'settings' },
]

function Sidebar({ user, onNavigate, closeRef, onLogout }) {
  return (
    <nav className="sidebar-nav" aria-label="Main navigation">
      {onNavigate && <button className="icon-button mobile-drawer__close" onClick={onNavigate} ref={closeRef} aria-label="Close navigation"><Icon name="close" /></button>}
      <p className="sidebar-label">WORKSPACE</p>
      {primaryLinks.map((link) => (
        <NavLink key={link.to} to={link.to} end={link.end} className={({ isActive }) => `nav-link${isActive ? ' is-active' : ''}`} onClick={onNavigate}>
          <Icon name={link.icon} size={19} /><span>{link.label}</span>
        </NavLink>
      ))}
      <div className="sidebar-bottom">
        <div className="account-card">
          <span className="account-avatar" aria-hidden="true">{user.name.trim().slice(0, 1).toUpperCase()}</span>
          <span className="account-card__identity"><strong>{user.name}</strong><small>{user.email}</small></span>
        </div>
        <button className="nav-link logout-button" type="button" onClick={onLogout}><Icon name="arrowLeft" size={19} /><span>Sign out</span></button>
      </div>
    </nav>
  )
}

function currentTitle(pathname) {
  if (pathname === '/settings') return 'Settings'
  if (pathname.startsWith('/groups')) return 'Groups'
  return 'Workspace'
}

export default function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  const { user, logout } = useAuth()
  const [drawerState, setDrawerState] = useState({ open: false, pathname: '' })
  const menuOpen = drawerState.open && drawerState.pathname === location.pathname
  const triggerRef = useRef(null)
  const closeRef = useRef(null)
  const panelRef = useRef(null)
  const mainRef = useRef(null)
  const wasOpen = useRef(false)

  useEffect(() => {
    mainRef.current?.focus({ preventScroll: true })
  }, [location.pathname])

  useEffect(() => {
    if (menuOpen) closeRef.current?.focus()
    else if (wasOpen.current) {
      if (window.innerWidth <= 760) triggerRef.current?.focus()
      else mainRef.current?.focus()
    }
    wasOpen.current = menuOpen
  }, [menuOpen])

  useEffect(() => {
    if (!menuOpen) return undefined
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    function handleKeyDown(event) {
      if (event.key === 'Escape') {
        event.preventDefault()
        setDrawerState({ open: false, pathname: location.pathname })
      }
      if (event.key === 'Tab') {
        const focusable = [...panelRef.current.querySelectorAll('a[href],button:not([disabled])')].filter(item => item.getClientRects().length)
        const first = focusable[0], last = focusable.at(-1)
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
      }
    }
    function resize() { if (window.innerWidth > 760) setDrawerState({ open: false, pathname: location.pathname }) }
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('resize', resize)
    return () => { window.removeEventListener('keydown', handleKeyDown); window.removeEventListener('resize', resize); document.body.style.overflow = overflow }
  }, [location.pathname, menuOpen])

  function closeMenu() {
    setDrawerState({ open: false, pathname: location.pathname })
  }

  function handleLogout() {
    try {
      logout()
      navigate('/login', { replace: true })
    } catch {
      navigate('/login', {
        replace: true,
        state: { logoutWarning: 'Your in-memory session ended, but this tab could not clear its saved token. Close the tab and review browser storage settings.' },
      })
    }
  }

  return (
    <div className="app-shell">
      <a href="#main-content" className="skip-link" inert={menuOpen}>Skip to main content</a>
      <aside className="desktop-sidebar" inert={menuOpen}>
        <NavLink className="brand" to="/dashboard" aria-label="SplitSmart home"><span className="brand-mark"><Icon name="wallet" size={21} strokeWidth={2} /></span><span>split<span>smart</span></span></NavLink>
        <Sidebar user={user} onLogout={handleLogout} />
        <p className="sidebar-version">A clearer way to share</p>
      </aside>
      <div className={`mobile-drawer${menuOpen ? ' is-open' : ''}`} aria-hidden={!menuOpen} inert={!menuOpen}>
        <button className="mobile-drawer__backdrop" aria-label="Close navigation" tabIndex={menuOpen ? 0 : -1} onClick={closeMenu} />
        <aside id="mobile-navigation" className="mobile-drawer__panel" role="dialog" aria-modal={menuOpen ? 'true' : undefined} aria-label="Navigation" ref={panelRef}>
          <NavLink className="brand" to="/dashboard" aria-label="SplitSmart home" onClick={closeMenu}><span className="brand-mark"><Icon name="wallet" size={21} strokeWidth={2} /></span><span>split<span>smart</span></span></NavLink>
          <Sidebar user={user} onNavigate={closeMenu} closeRef={closeRef} onLogout={handleLogout} />
        </aside>
      </div>
      <main className="main-shell" id="main-content" tabIndex={-1} inert={menuOpen} ref={mainRef}>
        <header className="topbar">
          <div className="topbar__leading">
            <button className="icon-button mobile-menu-button" type="button" aria-label="Open navigation" aria-expanded={menuOpen} aria-controls="mobile-navigation" ref={triggerRef} onClick={() => setDrawerState({ open: true, pathname: location.pathname })}><Icon name="menu" /></button>
            <span className="topbar__context">{currentTitle(location.pathname)}{location.pathname.startsWith('/groups/') ? ' / Group' : ''}</span>
          </div>
          <div className="topbar-trailing"><span className="preview-badge"><span aria-hidden="true" /> Signed in</span><NotificationBell /></div>
        </header>
        <div className="page-content"><Outlet /></div>
        <footer className="app-footer">SplitSmart <span aria-hidden="true">·</span> A calmer way to share the bill</footer>
      </main>
    </div>
  )
}
