import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useAuth } from '../context/useAuth.js'
import { ApiError } from '../services/apiClient.js'
import { listGroupMembers, listGroups } from '../services/groupService.js'

function isValidGroupId(groupId) {
  return /^\d+$/.test(groupId) && Number.isSafeInteger(Number(groupId)) && Number(groupId) > 0
}

export function useGroupData() {
  const { groupId } = useParams()
  const { user } = useAuth()
  const [data, setData] = useState({ scope: '', status: 'loading', group: null, members: [], error: '' })
  const [loadAttempt, setLoadAttempt] = useState(0)
  const scope = `${user.id}:${groupId}:${loadAttempt}`

  useEffect(() => {
    const controller = new AbortController()

    if (!isValidGroupId(groupId)) {
      return () => controller.abort()
    }

    async function loadGroup() {
      try {
        const groups = await listGroups({ signal: controller.signal })
        const group = groups.find((candidate) => String(candidate.id) === String(groupId))
        if (!group) {
          setData({ scope, status: 'not-found', group: null, members: [], error: '' })
          return
        }

        const members = await listGroupMembers(group.id, { signal: controller.signal })
        if (!controller.signal.aborted) {
          setData({ scope, status: 'success', group, members, error: '' })
        }
      } catch (error) {
        if (controller.signal.aborted) return
        if (error instanceof ApiError && error.status === 404) {
          setData({ scope, status: 'not-found', group: null, members: [], error: '' })
          return
        }
        setData({
          scope,
          status: 'error',
          group: null,
          members: [],
          error: error instanceof ApiError ? error.message : 'Group information could not be loaded. Please try again.',
        })
      }
    }

    loadGroup().catch(() => {
      if (!controller.signal.aborted) {
        setData({ scope, status: 'error', group: null, members: [], error: 'Group information could not be loaded. Please try again.' })
      }
    })
    return () => controller.abort()
  }, [scope, groupId, loadAttempt])

  const visibleData = !isValidGroupId(groupId)
    ? { status: 'not-found', group: null, members: [], error: '' }
    : data.scope === scope
      ? data
      : { status: 'loading', group: null, members: [], error: '' }

  function updateMembers(members) {
    setData((current) => current.scope === scope && current.status === 'success'
      ? { ...current, members }
      : current)
  }

  return {
    ...visibleData,
    groupId,
    reload: () => setLoadAttempt((attempt) => attempt + 1),
    updateMembers,
  }
}
