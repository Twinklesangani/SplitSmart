// UI gates only; server-side channel configuration remains authoritative.
export function recoveryEmailEnabled() {
  return import.meta.env.VITE_RECOVERY_EMAIL_ENABLED === 'true'
}

export function invitationEmailEnabled() {
  return import.meta.env.VITE_INVITATION_EMAIL_ENABLED === 'true'
}

export function verificationEmailEnabled() {
  return import.meta.env.VITE_VERIFICATION_EMAIL_ENABLED === 'true'
}
