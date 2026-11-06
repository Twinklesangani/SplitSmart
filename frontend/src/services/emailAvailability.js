// UI gate only. Server-side delivery configuration remains the security boundary.
// Enable only after real delivery verification and a separately approved release.
export function emailActionsEnabled() {
  return import.meta.env.VITE_EMAIL_ACTIONS_ENABLED === 'true'
}
