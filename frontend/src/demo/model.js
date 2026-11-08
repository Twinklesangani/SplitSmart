import raw from './scenarios.json'
function freeze(value) { Object.values(value).forEach(item => { if (item && typeof item === 'object') freeze(item) }); return Object.freeze(value) }
export const fixtures = freeze(raw)
export function initialDemo() { return { persona: null, registered: false, group: false, invited: false, revoked: false, expired: false, accepted: [], equal: false, unequal: false, settled: false, read: [], message: '' } }
const fail = state => ({ ...state, message: 'This action is unavailable for the selected fictional profile or step.' })
export function transition(state, action) {
  if (!action || Object.keys(action).some(key => key !== 'type') || typeof action.type !== 'string') return fail(state)
  const type = action.type
  if (type === 'register' && !state.registered) return { ...state, registered: true, message: 'Fictional profile prepared in this tab. No account was created.' }
  if (type.startsWith('login:')) {
    const key = type.slice(6)
    if (!fixtures.users.some(u => u.key === key) || key === 'mira' && !state.registered) return fail(state)
    return { ...state, persona: key, message: 'Fictional profile selected. This is a simulation, not a real sign-in.' }
  }
  if (!state.persona) return fail(state)
  if (type === 'logout') return { ...state, persona: null, message: 'Simulation signed out. No real session was changed.' }
  if (type === 'reset') return initialDemo()
  const owner = state.persona === 'mira'
  if (type === 'create' && owner && !state.group) return { ...state, group: true, message: 'Fictional group created.' }
  if (type === 'invite' && owner && state.group && !state.invited) return { ...state, invited: true, message: 'Three fictional invitations are pending. Each recipient must accept.' }
  if (type === 'revoke' && owner && state.invited && !state.accepted.length && !state.revoked) return { ...state, revoked: true, message: 'Fictional invitation revoked. Acceptance is blocked.' }
  if (type === 'expire' && owner && state.invited && !state.accepted.length && !state.expired) return { ...state, expired: true, message: 'Fictional invitation expired. Acceptance is blocked.' }
  if (type === 'accept' && !owner && state.invited && !state.revoked && !state.expired && !state.accepted.includes(state.persona)) return { ...state, accepted: [...state.accepted, state.persona], message: 'Fictional invitation accepted by its selected recipient.' }
  const membersReady = state.accepted.length === 3
  if (type === 'equal' && owner && membersReady && !state.equal) return { ...state, equal: true, message: 'Equal expense added: four shares of â‚¹300.00.' }
  if (type === 'unequal' && owner && state.equal && !state.unequal) return { ...state, unequal: true, message: 'Unequal expense added. Every share reconciles to â‚¹800.00.' }
  if (type === 'settle' && state.persona === 'lena' && state.unequal && !state.settled) return { ...state, settled: true, message: 'Fictional â‚¹600.00 repayment recorded once. Original expenses remain.' }
  if (type === 'read' && state.group) return { ...state, read: [...new Set([...state.read, state.persona])], message: 'This tabâ€™s fictional notifications are marked read.' }
  return fail(state)
}
export function snapshot(state) { return fixtures.snapshots[state.settled ? 'settled' : state.unequal ? 'unequal' : state.equal ? 'equal' : 'empty'] }
export function invitationState(state, key) { return state.accepted.includes(key) ? 'Accepted' : state.revoked ? 'Revoked' : state.expired ? 'Expired' : state.invited ? 'Pending' : 'Not invited' }
