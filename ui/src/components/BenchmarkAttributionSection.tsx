import { useState } from 'react'
import { useBrinsonAttribution } from '../hooks/useBrinsonAttribution'
import { BrinsonAttributionTable } from './BrinsonAttributionTable'
import { Spinner } from './ui/Spinner'
import { Card } from './ui/Card'
import { EmptyState } from './ui/EmptyState'
import { ErrorCard } from './ui/ErrorCard'

interface BenchmarkAttributionSectionProps {
  bookId: string
}

export function BenchmarkAttributionSection({ bookId }: BenchmarkAttributionSectionProps) {
  const [benchmarkId, setBenchmarkId] = useState('')
  const [submittedBenchmarkId, setSubmittedBenchmarkId] = useState<string | null>(null)

  const { data, loading, error } = useBrinsonAttribution(bookId, submittedBenchmarkId)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const trimmed = benchmarkId.trim()
    if (trimmed) {
      setSubmittedBenchmarkId(trimmed)
    }
  }

  return (
    <Card header="Benchmark Attribution (Brinson-Hood-Beebower)" data-testid="benchmark-attribution-section">
      <form
        data-testid="benchmark-attribution-form"
        onSubmit={handleSubmit}
        className="flex items-center gap-2 mb-4"
      >
        <label htmlFor="benchmark-id-input" className="text-sm text-slate-600 whitespace-nowrap">
          Benchmark ID
        </label>
        <input
          id="benchmark-id-input"
          data-testid="benchmark-id-input"
          type="text"
          value={benchmarkId}
          onChange={(e) => setBenchmarkId(e.target.value)}
          placeholder="e.g. SP500"
          className="flex-1 rounded border border-slate-300 px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="submit"
          data-testid="benchmark-attribution-submit"
          disabled={!benchmarkId.trim()}
          className="px-3 py-1 text-sm font-medium rounded bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          Run
        </button>
      </form>

      {loading && (
        <div data-testid="benchmark-attribution-loading" className="flex justify-center py-6">
          <Spinner />
        </div>
      )}

      {error && (
        <div className="py-2">
          <ErrorCard message={error} data-testid="benchmark-attribution-error" />
        </div>
      )}

      {!submittedBenchmarkId && !loading && (
        <div data-testid="benchmark-attribution-empty">
          <EmptyState
            title="No attribution yet"
            description="Enter a benchmark ID and click Run to compute attribution."
          />
        </div>
      )}

      {data && !loading && (
        <BrinsonAttributionTable data={data} />
      )}
    </Card>
  )
}
