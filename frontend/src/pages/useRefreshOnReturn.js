import { useEffect } from 'react'

// Refresh server state when returning to a tab after another member has made changes.
export function useRefreshOnReturn(setAttempt) {
  useEffect(() => {
    let away = document.visibilityState === 'hidden'
    const leave = () => { away = true }
    const refresh = () => {
      if (document.visibilityState === 'hidden') {
        away = true
      } else if (away) {
        away = false
        setAttempt(value => value + 1)
      }
    }
    window.addEventListener('blur', leave)
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      window.removeEventListener('blur', leave)
      window.removeEventListener('focus', refresh)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [setAttempt])
}
