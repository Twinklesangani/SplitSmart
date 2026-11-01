const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'
const REQUEST_TIMEOUT_MS = 10000

export class ApiError extends Error {
  constructor(message, { status = null, code = 'request_failed' } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

function resolveApiUrl(path) {
  if (!path.startsWith('/api/')) {
    throw new ApiError('The request could not be completed.', { code: 'invalid_request' })
  }

  let baseUrl
  let requestUrl
  try {
    baseUrl = new URL(API_BASE_URL)
    requestUrl = new URL(path, baseUrl)
  } catch {
    throw new ApiError('The API address is not configured correctly.', { code: 'invalid_configuration' })
  }

  if (requestUrl.origin !== baseUrl.origin) {
    throw new ApiError('The request could not be completed.', { code: 'invalid_request' })
  }
  return requestUrl.toString()
}

function messageForHttpError(status, code, path) {
  if (status === 401) {
    return path === '/api/auth/login'
      ? 'Invalid email or password.'
      : 'Your session has expired. Please sign in again.'
  }
  if (status === 403) return 'You do not have permission to complete this request.'
  if (status === 404) {
    return path.startsWith('/api/groups')
      ? 'The group or member could not be found or is unavailable.'
      : 'The requested service is unavailable.'
  }
  if (status === 409 || code === 'conflict') {
    if (path === '/api/auth/register') return 'An account with this email already exists.'
    if (/\/api\/groups\/[^/]+\/settlements(?:\/|$)/.test(path)) {
      return 'This repayment request conflicts with an existing record. Refresh settlement history before trying again.'
    }
    if (/^\/api\/groups\/[^/]+\/expenses$/.test(path)) return 'This operation was already used with different details. Check expense history before starting a separate expense.'
    if (path.startsWith('/api/groups')) return 'This person is already a group member or the request conflicts with current group data.'
    return 'This request conflicts with existing data.'
  }
  if (status === 400 && /\/api\/groups\/[^/]+\/settlements(?:\/|$)/.test(path)) {
    return 'The repayment could not be recorded. It may exceed the current outstanding balance; refresh balances and try again.'
  }
  if (status === 400 && /\/api\/groups\/[^/]+\/expenses(?:\/|$)/.test(path)) {
    return 'Check the description, amount, payer, participants, and split totals and try again.'
  }
  if (status === 400 && path.startsWith('/api/groups')) {
    return 'Check the group name or member email and try again.'
  }
  if (status === 400) return 'Please check your details and try again.'
  return 'The service could not complete your request. Please try again.'
}

function safeMessage(status, code, path) {
  return messageForHttpError(status, code, path)
}

function notifyExpiredGroupSession(path, status) {
  if (status === 401 && path.startsWith('/api/groups')) {
    window.dispatchEvent(new Event('splitsmart:session-expired'))
  }
}

function isAbortError(error) {
  return error?.name === 'AbortError' || error?.name === 'TimeoutError'
}

export async function apiRequest(path, { method = 'GET', body, token, signal, expectedStatus, operationKey } = {}) {
  const url = resolveApiUrl(path)
  let requestToken = token
  if (requestToken === undefined && path.startsWith('/api/groups')) {
    try {
      requestToken = window.sessionStorage.getItem('splitsmart.accessToken')
    } catch {
      throw new ApiError('This browser could not access the sign-in session. Check your privacy settings and try again.', {
        code: 'session_storage_unavailable',
      })
    }
  }
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  const abortFromCaller = () => controller.abort()

  if (signal?.aborted) controller.abort()
  else signal?.addEventListener('abort', abortFromCaller, { once: true })

  const headers = { Accept: 'application/json' }
  if (operationKey) headers['Idempotency-Key'] = '"' + operationKey + '"'
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (requestToken) headers.Authorization = `Bearer ${requestToken}`

  try {
    let response
    try {
      response = await fetch(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      })
    } catch (error) {
      if (isAbortError(error)) {
        throw new ApiError('The request timed out. Check your connection and try again.', { code: 'timeout' })
      }
      throw new ApiError('Could not reach SplitSmart. Check that the backend is running and try again.', { code: 'network_error' })
    }

    if (response.status === 204) {
      if (!response.ok) throw new ApiError(safeMessage(response.status, '', path), { status: response.status })
      if (expectedStatus !== undefined && response.status !== expectedStatus) {
        throw new ApiError('The service returned an unexpected response. Please refresh and try again.', {
          status: response.status,
          code: 'invalid_response',
        })
      }
      return null
    }

    let responseBody
    try {
      responseBody = await response.json()
    } catch {
      if (!response.ok) {
        notifyExpiredGroupSession(path, response.status)
        throw new ApiError(safeMessage(response.status, '', path), { status: response.status })
      }
      throw new ApiError('The service returned an unexpected response. Please try again.', { status: response.status, code: 'invalid_response' })
    }

    if (!response.ok) {
      const code = typeof responseBody?.code === 'string' ? responseBody.code : ''
      notifyExpiredGroupSession(path, response.status)
      throw new ApiError(safeMessage(response.status, code, path), { status: response.status, code: code || 'request_failed' })
    }
    if (expectedStatus !== undefined && response.status !== expectedStatus) {
      throw new ApiError('The service returned an unexpected response. Please refresh and try again.', {
        status: response.status,
        code: 'invalid_response',
      })
    }
    return responseBody
  } finally {
    clearTimeout(timeoutId)
    signal?.removeEventListener('abort', abortFromCaller)
  }
}
