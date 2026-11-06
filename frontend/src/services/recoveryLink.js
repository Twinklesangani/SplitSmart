// Recovery secrets live only in this module's memory, never browser storage or router state.
let recoveryToken = ''

export function prepareRecoveryLink() {
  recoveryToken = ''
  const url = new URL(window.location.href)
  if (url.pathname !== '/reset-password') return
  const values = new URLSearchParams(url.hash.slice(1)).getAll('token')
  if (values.length === 1 && /^[A-Za-z0-9_-]{43}$/.test(values[0])) recoveryToken = values[0]
  // Run before BrowserRouter/auth requests. Query-string tokens are intentionally unsupported.
  window.history.replaceState(window.history.state, '', url.pathname)
}

export function getRecoveryToken() { return recoveryToken }
export function clearRecoveryToken() { recoveryToken = '' }
