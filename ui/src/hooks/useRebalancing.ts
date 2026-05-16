import { useCallback, useState } from 'react'
import { FetchError, runRebalancingAnalysis } from '../api/whatIf'
import { classifyFetchError } from '../utils/errorClassifier'
import type { RebalancingResponseDto } from '../types'
import type { TradeFormEntry } from './useWhatIf'

export interface UseRebalancingResult {
  rebalancingResult: RebalancingResponseDto | null
  rebalancingLoading: boolean
  rebalancingError: string | null
  submitRebalancing: (bookId: string, trades: TradeFormEntry[]) => Promise<void>
  resetRebalancing: () => void
}

export function useRebalancing(): UseRebalancingResult {
  const [rebalancingResult, setRebalancingResult] = useState<RebalancingResponseDto | null>(null)
  const [rebalancingLoading, setRebalancingLoading] = useState(false)
  const [rebalancingError, setRebalancingError] = useState<string | null>(null)

  const submitRebalancing = useCallback(async (bookId: string, trades: TradeFormEntry[]) => {
    setRebalancingLoading(true)
    setRebalancingError(null)

    try {
      const data = await runRebalancingAnalysis(bookId, {
        trades: trades.map((t) => ({
          instrumentId: t.instrumentId,
          instrumentType: t.instrumentType,
          assetClass: t.assetClass,
          side: t.side,
          quantity: t.quantity,
          priceAmount: t.priceAmount,
          priceCurrency: t.priceCurrency,
          bidAskSpreadBps: t.bidAskSpreadBps ? Number(t.bidAskSpreadBps) : 5,
        })),
      })
      setRebalancingResult(data)
    } catch (err) {
      const status = err instanceof FetchError ? err.status : undefined
      const classified = classifyFetchError(err, status)
      setRebalancingResult(null)
      setRebalancingError(classified.message)
    } finally {
      setRebalancingLoading(false)
    }
  }, [])

  const resetRebalancing = useCallback(() => {
    setRebalancingResult(null)
    setRebalancingError(null)
  }, [])

  return { rebalancingResult, rebalancingLoading, rebalancingError, submitRebalancing, resetRebalancing }
}
