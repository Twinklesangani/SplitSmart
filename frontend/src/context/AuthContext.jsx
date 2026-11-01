import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, apiRequest } from '../services/apiClient.js'
import { AuthContext } from './authContext.js'

const SESSION_TOKEN_KEY = 'splitsmart.accessToken'
const SESSION_EXPIRY_KEY = 'splitsmart.accessTokenExpiresAt'

function validProfile(profile) {
  return profile
    && (typeof profile.id === 'number' || typeof profile.id === 'string')
    && typeof profile.name === 'string'
    && typeof profile.email === 'string'
}

function validLoginResponse(response) {
  return response
    && typeof response.accessToken === 'string'
    && response.accessToken.length > 0
    && typeof response.tokenType === 'string'
    && response.tokenType.toLowerCase() === 'bearer'
    && Number.isFinite(response.expiresIn)
    && response.expiresIn > 0
}

function storageError() {
  return new ApiError('This browser could not save the sign-in session. Check your privacy settings and try again.', {
    code: 'session_storage_unavailable',
  })
}

function readSessionToken() {
  try {
    return window.sessionStorage.getItem(SESSION_TOKEN_KEY)
  } catch {
    throw storageError()
  }
}

function saveSessionToken(token) {
  try {
    window.sessionStorage.setItem(SESSION_TOKEN_KEY, token)
  } catch {
    throw storageError()
  }
}

function removeSessionToken() {
  try {
    window.sessionStorage.removeItem(SESSION_TOKEN_KEY)
    window.sessionStorage.removeItem(SESSION_EXPIRY_KEY)
    for (const key of Object.keys(window.sessionStorage)) {
      if (key.startsWith('splitsmart.expenseOperation.')) window.sessionStorage.removeItem(key)
    }
  } catch {
    throw storageError()
  }
}

export function AuthProvider({ children }) {
  const [session, setSession] = useState({ status: 'restoring', user: null, error: null, expiresAt: null })
  const operationRef = useRef(0)
  const requestRef = useRef(null)

  const cancelCurrentRequest = useCallback(() => {
    requestRef.current?.abort()
    requestRef.current = null
  }, [])

  const clearSession = useCallback(() => {
    operationRef.current += 1
    cancelCurrentRequest()
    setSession({ status: 'unauthenticated', user: null, error: null, expiresAt: null })
    removeSessionToken()
  }, [cancelCurrentRequest])

  const restoreSession = useCallback(async (providedToken, { signal, preserveOnFailure = true } = {}) => {
    const operationId = ++operationRef.current
    const controller = new AbortController()
    requestRef.current?.abort()
    requestRef.current = controller
    let abortFromCaller
    if (signal) {
      if (signal.aborted) controller.abort()
      else {
        abortFromCaller = () => controller.abort()
        signal.addEventListener('abort', abortFromCaller, { once: true })
      }
    }

    try {
      const profile = await apiRequest('/api/auth/me', { token: providedToken, signal: controller.signal })
      if (!validProfile(profile)) {
        throw new ApiError('The service returned an unexpected profile. Please try again.', { code: 'invalid_response' })
      }
      if (operationRef.current === operationId) {
        let expiresAt = null
        try {
          const storedExpiry = Number(window.sessionStorage.getItem(SESSION_EXPIRY_KEY))
          if (Number.isFinite(storedExpiry) && storedExpiry > 0) expiresAt = storedExpiry
        } catch {
          throw storageError()
        }
        setSession({ status: 'authenticated', user: profile, error: null, expiresAt })
      }
      return profile
    } catch (error) {
      if (operationRef.current !== operationId || controller.signal.aborted) throw error
      if (error instanceof ApiError && error.status === 401) {
        removeSessionToken()
        setSession({ status: 'unauthenticated', user: null, error: null, expiresAt: null })
      } else if (preserveOnFailure) {
        setSession({
          status: 'unavailable',
          user: null,
          error: error instanceof ApiError ? error.message : 'Could not verify the session. Please try again.',
          expiresAt: null,
        })
      }
      throw error
    } finally {
      if (signal && abortFromCaller) signal.removeEventListener('abort', abortFromCaller)
      if (requestRef.current === controller) requestRef.current = null
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    let mounted = true
    async function initialize() {
      try {
        const token = readSessionToken()
        if (!token) {
          if (mounted) setSession({ status: 'unauthenticated', user: null, error: null, expiresAt: null })
          return
        }
        await restoreSession(token, { signal: controller.signal })
      } catch (error) {
        if (!mounted || controller.signal.aborted) return
        if (!(error instanceof ApiError) || error.status !== 401) {
          setSession({
            status: 'unavailable',
            user: null,
            error: error instanceof ApiError ? error.message : 'Could not verify the saved session. Check your connection and retry.',
            expiresAt: null,
          })
        }
      }
    }
    initialize().catch(() => {
      if (mounted && !controller.signal.aborted) {
        setSession({ status: 'unavailable', user: null, error: 'Could not verify the saved session. Please retry.', expiresAt: null })
      }
    })
    return () => {
      mounted = false
      controller.abort()
    }
  }, [restoreSession])

  const login = useCallback(async (email, password) => {
    operationRef.current += 1
    const operationId = operationRef.current
    cancelCurrentRequest()
    const controller = new AbortController()
    requestRef.current = controller
    try {
      const response = await apiRequest('/api/auth/login', {
        method: 'POST',
        body: { email: email.trim().toLowerCase(), password },
        signal: controller.signal,
      })
      if (!validLoginResponse(response)) {
        throw new ApiError('The service returned an unexpected sign-in response. Please try again.', { code: 'invalid_response' })
      }
      saveSessionToken(response.accessToken)
      const expiresAt = Date.now() + response.expiresIn * 1000
      try {
        window.sessionStorage.setItem(SESSION_EXPIRY_KEY, String(expiresAt))
      } catch {
        throw storageError()
      }
      const profile = await apiRequest('/api/auth/me', {
        token: response.accessToken,
        signal: controller.signal,
      })
      if (!validProfile(profile)) {
        throw new ApiError('The service returned an unexpected profile. Please try again.', { code: 'invalid_response' })
      }
      if (operationRef.current === operationId) {
        setSession({ status: 'authenticated', user: profile, error: null, expiresAt })
      }
      return profile
    } catch (error) {
      if (operationRef.current === operationId) {
        removeSessionToken()
        setSession({ status: 'unauthenticated', user: null, error: null, expiresAt: null })
      }
      throw error
    } finally {
      if (requestRef.current === controller) requestRef.current = null
    }
  }, [cancelCurrentRequest])

  const retrySession = useCallback(async () => {
    try {
      const token = readSessionToken()
      if (!token) {
        setSession({ status: 'unauthenticated', user: null, error: null, expiresAt: null })
        return null
      }
      setSession({ status: 'restoring', user: null, error: null, expiresAt: null })
      await restoreSession(token)
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) {
        setSession({
          status: 'unavailable',
          user: null,
          error: error instanceof ApiError ? error.message : 'Could not verify the session. Please try again.',
          expiresAt: null,
        })
      }
    }
  }, [restoreSession])

  const logout = useCallback(() => {
    operationRef.current += 1
    cancelCurrentRequest()
    setSession({ status: 'unauthenticated', user: null, error: null, expiresAt: null })
    removeSessionToken()
  }, [cancelCurrentRequest])

  useEffect(() => {
    if (session.status !== 'authenticated' || !session.expiresAt) return undefined
    const remaining = session.expiresAt - Date.now()
    const timeout = window.setTimeout(() => {
      try {
        clearSession()
      } catch {
        setSession({ status: 'unauthenticated', user: null, error: null, expiresAt: null })
      }
    }, Math.max(remaining, 0))
    return () => window.clearTimeout(timeout)
  }, [session.status, session.expiresAt, clearSession])

  useEffect(() => {
    function handleSessionExpired() {
      try {
        clearSession()
      } catch {
        setSession({ status: 'unauthenticated', user: null, error: null, expiresAt: null })
      }
    }

    window.addEventListener('splitsmart:session-expired', handleSessionExpired)
    return () => window.removeEventListener('splitsmart:session-expired', handleSessionExpired)
  }, [clearSession])

  const value = useMemo(() => ({
    status: session.status,
    user: session.user,
    error: session.error,
    login,
    logout,
    retrySession,
  }), [session, login, logout, retrySession])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
