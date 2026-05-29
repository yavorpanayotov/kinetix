import { useCallback, useEffect, useState } from 'react'
import { fetchFrtb, fetchFrtbLatest, generateReport } from '../api/regulatory'
import type { FrtbResultDto } from '../types'

export interface UseRegulatoryResult {
  result: FrtbResultDto | null
  loading: boolean
  error: string | null
  calculate: () => void
  downloadCsv: () => void
  downloadXbrl: () => void
}

export function useRegulatory(bookId: string | null): UseRegulatoryResult {
  const [result, setResult] = useState<FrtbResultDto | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // On book change, reset and load the most recent persisted FRTB calculation
  // so the tab shows the last result by default instead of an empty state.
  // The background load does not flip `loading` (which is reserved for an
  // explicit Calculate action) and is cancelled if the book changes again.
  useEffect(() => {
    setResult(null)
    setError(null)
    if (!bookId) return

    let cancelled = false
    fetchFrtbLatest(bookId)
      .then((latest) => {
        if (!cancelled && latest) {
          setResult(latest)
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err))
        }
      })

    return () => {
      cancelled = true
    }
  }, [bookId])

  const calculate = useCallback(async () => {
    if (!bookId) return
    setLoading(true)
    setError(null)
    try {
      const data = await fetchFrtb(bookId)
      setResult(data)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [bookId])

  const triggerDownload = useCallback((content: string, filename: string) => {
    const blob = new Blob([content], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
  }, [])

  const downloadCsv = useCallback(async () => {
    if (!bookId) return
    try {
      const report = await generateReport(bookId, 'CSV')
      if (report) {
        triggerDownload(report.content, `frtb-${bookId}.csv`)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }, [bookId, triggerDownload])

  const downloadXbrl = useCallback(async () => {
    if (!bookId) return
    try {
      const report = await generateReport(bookId, 'XBRL')
      if (report) {
        triggerDownload(report.content, `frtb-${bookId}.xbrl`)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }, [bookId, triggerDownload])

  return { result, loading, error, calculate, downloadCsv, downloadXbrl }
}
