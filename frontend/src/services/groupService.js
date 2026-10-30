import { ApiError, apiRequest } from './apiClient.js'

function isId(value) {
  return (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
    || (typeof value === 'string' && /^\d+$/.test(value) && Number.isSafeInteger(Number(value)) && Number(value) > 0)
}

function requireGroups(groups) {
  if (!Array.isArray(groups) || groups.some((group) => (
    !group
    || !isId(group.id)
    || typeof group.name !== 'string'
    || !isId(group.createdByUserId)
  ))) {
    throw new ApiError('The service returned unexpected group data. Please try again.', { code: 'invalid_response' })
  }
  return groups
}

function requireGroup(group) {
  if (!group || !isId(group.id) || typeof group.name !== 'string' || !isId(group.createdByUserId)) {
    throw new ApiError('The service returned unexpected group data. Please try again.', { code: 'invalid_response' })
  }
  return group
}

function requireMembers(members) {
  if (!Array.isArray(members) || members.some((member) => (
    !member
    || !isId(member.userId)
    || typeof member.name !== 'string'
    || typeof member.email !== 'string'
  ))) {
    throw new ApiError('The service returned unexpected member data. Please try again.', { code: 'invalid_response' })
  }
  return members
}

function requireMember(member) {
  if (!member || !isId(member.userId) || typeof member.name !== 'string' || typeof member.email !== 'string') {
    throw new ApiError('The service returned unexpected member data. Please try again.', { code: 'invalid_response' })
  }
  return member
}

export async function listGroups({ signal } = {}) {
  return requireGroups(await apiRequest('/api/groups', { signal }))
}

export async function createGroup(name, { signal } = {}) {
  return requireGroup(await apiRequest('/api/groups', {
    method: 'POST',
    body: { name },
    signal,
  }))
}

export async function listGroupMembers(groupId, { signal } = {}) {
  return requireMembers(await apiRequest(`/api/groups/${encodeURIComponent(groupId)}/members`, { signal }))
}

export async function addGroupMember(groupId, email, { signal } = {}) {
  return requireMember(await apiRequest(`/api/groups/${encodeURIComponent(groupId)}/members`, {
    method: 'POST',
    body: { email },
    signal,
  }))
}
