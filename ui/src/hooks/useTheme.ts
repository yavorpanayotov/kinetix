import { useCallback, useEffect, useState } from 'react'

const STORAGE_KEY = 'kinetix:theme'

export function useTheme() {
  const [isDark, setIsDark] = useState(() => {
    // Dark by default — explicit 'light' preference opts out, anything else
    // (including unset, which is what every new user lands on) gets dark.
    return localStorage.getItem(STORAGE_KEY) !== 'light'
  })

  useEffect(() => {
    if (isDark) {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
    localStorage.setItem(STORAGE_KEY, isDark ? 'dark' : 'light')
  }, [isDark])

  const toggle = useCallback(() => {
    setIsDark((prev) => !prev)
  }, [])

  return { isDark, toggle }
}
