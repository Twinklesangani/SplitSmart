// Recovery secrets live only in this module's memory, never browser storage or router state.
let recoveryToken = ''
let invitationToken = ''
let verificationToken = ''

export function prepareRecoveryLink() {
  recoveryToken = ''
  invitationToken = ''
  verificationToken = ''
  const url = new URL(window.location.href)
  if (!['/reset-password', '/join', '/verify-email'].includes(url.pathname)) return
  const values = new URLSearchParams(url.hash.slice(1)).getAll('token')
  if (values.length === 1 && /^[A-Za-z0-9_-]{43}$/.test(values[0])) {
    if (url.pathname === '/reset-password') recoveryToken = values[0]
    if (url.pathname === '/join') invitationToken = values[0]
    if (url.pathname === '/verify-email') verificationToken = values[0]
  }
  // Run before BrowserRouter/auth requests. Query-string tokens are intentionally unsupported.
  window.history.replaceState(window.history.state, '', url.pathname)
}

export function getRecoveryToken() { return recoveryToken }
export function clearRecoveryToken() { recoveryToken = '' }
export function getInvitationToken() { return invitationToken }
export function getVerificationToken() { return verificationToken }
export function clearInvitationToken() { invitationToken = '' }
export function clearVerificationToken() { verificationToken = '' }
