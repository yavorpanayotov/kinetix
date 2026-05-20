import type { ReactNode } from 'react'
import { render, renderHook } from '@testing-library/react'
import { describe, expect, test } from 'vitest'
import {
  serialiseCopilotContext,
  useCopilotContext,
  type CopilotContextInput,
} from './useCopilotContext'
import { CopilotContextProvider } from './CopilotContextProvider'

function wrapperFor(value: CopilotContextInput) {
  return ({ children }: { children: ReactNode }) => (
    <CopilotContextProvider value={value}>{children}</CopilotContextProvider>
  )
}

describe('serialiseCopilotContext', () => {
  test('serialiseCopilotContext returns {page:unknown} for null input', () => {
    expect(serialiseCopilotContext(null)).toEqual({ page: 'unknown' })
  })

  test('serialiseCopilotContext includes page from route', () => {
    expect(serialiseCopilotContext({ route: 'risk' })).toEqual({ page: 'risk' })
  })

  test('serialiseCopilotContext includes book_id when a concrete book is selected', () => {
    expect(
      serialiseCopilotContext({ route: 'positions', bookId: 'fx-main' }),
    ).toEqual({ page: 'positions', book_id: 'fx-main' })
  })

  test('serialiseCopilotContext omits book_id and sets book_scope:all for the all-books sentinel', () => {
    const result = serialiseCopilotContext({
      route: 'positions',
      bookId: '__ALL__',
    })
    expect(result).not.toHaveProperty('book_id')
    expect(result).toEqual({ page: 'positions', book_scope: 'all' })
  })

  test('serialiseCopilotContext omits book_id when bookId is null', () => {
    const result = serialiseCopilotContext({ route: 'positions', bookId: null })
    expect(result).not.toHaveProperty('book_id')
    expect(result).toEqual({ page: 'positions' })
  })

  test('serialiseCopilotContext includes scenario on the scenarios route', () => {
    expect(
      serialiseCopilotContext({
        route: 'scenarios',
        bookId: 'fx-main',
        scenario: 'GFC',
      }),
    ).toEqual({ page: 'scenarios', book_id: 'fx-main', scenario: 'GFC' })
  })

  test('serialiseCopilotContext includes var_result_id on the risk route', () => {
    const result = serialiseCopilotContext({
      route: 'risk',
      bookId: 'fx-main',
      varResultId: 'job-123',
    })
    expect(result).toMatchObject({ var_result_id: 'job-123' })
  })

  test('serialiseCopilotContext omits scenario and var_result_id when absent', () => {
    const result = serialiseCopilotContext({
      route: 'positions',
      bookId: 'fx-main',
    })
    expect(Object.keys(result).sort()).toEqual(['book_id', 'page'])
  })

  test('serialiseCopilotContext merges extra keys verbatim', () => {
    const result = serialiseCopilotContext({
      route: 'positions',
      extra: { instrument_id: 'EURUSD' },
    })
    expect(result).toMatchObject({ page: 'positions', instrument_id: 'EURUSD' })
  })

  test('serialiseCopilotContext extra can override a derived key', () => {
    const result = serialiseCopilotContext({
      route: 'positions',
      bookId: 'fx-main',
      extra: { book_id: 'override' },
    })
    expect(result.book_id).toBe('override')
  })

  test('serialiseCopilotContext never emits null/undefined/empty-string values', () => {
    const result = serialiseCopilotContext({
      route: 'risk',
      bookId: '',
      scenario: null,
      varResultId: undefined,
    })
    expect(result).toEqual({ page: 'risk' })
  })
})

describe('useCopilotContext', () => {
  test('useCopilotContext returns {page:unknown} when used outside a provider', () => {
    const { result } = renderHook(() => useCopilotContext())
    expect(result.current).toEqual({ page: 'unknown' })
  })

  test('useCopilotContext reads the provider value', () => {
    const { result } = renderHook(() => useCopilotContext(), {
      wrapper: wrapperFor({
        route: 'risk',
        bookId: 'fx-main',
        varResultId: 'job-1',
      }),
    })
    expect(result.current).toEqual({
      page: 'risk',
      book_id: 'fx-main',
      var_result_id: 'job-1',
    })
  })

  test('useCopilotContext memoises — stable reference when input is unchanged', () => {
    const value: CopilotContextInput = { route: 'risk', bookId: 'fx-main' }
    const { result, rerender } = renderHook(() => useCopilotContext(), {
      wrapper: wrapperFor(value),
    })
    const first = result.current
    rerender()
    expect(result.current).toBe(first)
  })

  test('useCopilotContext produces a new context when the provider value changes', () => {
    // Render a provider+consumer subtree and surface the serialised
    // context through the DOM so `rerender` props drive the provider
    // value without mutating any out-of-render variable.
    function Probe() {
      const context = useCopilotContext()
      return <span data-testid="ctx">{JSON.stringify(context)}</span>
    }
    function Tree({ value }: { value: CopilotContextInput }) {
      return (
        <CopilotContextProvider value={value}>
          <Probe />
        </CopilotContextProvider>
      )
    }
    const { rerender, getByTestId } = render(<Tree value={{ route: 'risk' }} />)
    expect(JSON.parse(getByTestId('ctx').textContent ?? '')).toEqual({
      page: 'risk',
    })
    rerender(<Tree value={{ route: 'positions' }} />)
    expect(JSON.parse(getByTestId('ctx').textContent ?? '')).toEqual({
      page: 'positions',
    })
  })

  test('per-route shapes — risk vs scenarios vs positions vs alerts', () => {
    const cases: Array<{
      input: CopilotContextInput
      expected: Record<string, unknown>
    }> = [
      {
        input: { route: 'risk', bookId: 'fx-main', varResultId: 'job-9' },
        expected: { page: 'risk', book_id: 'fx-main', var_result_id: 'job-9' },
      },
      {
        input: { route: 'scenarios', bookId: 'fx-main', scenario: 'GFC' },
        expected: { page: 'scenarios', book_id: 'fx-main', scenario: 'GFC' },
      },
      {
        input: { route: 'positions', bookId: 'fx-main' },
        expected: { page: 'positions', book_id: 'fx-main' },
      },
      {
        input: { route: 'alerts', bookId: 'fx-main' },
        expected: { page: 'alerts', book_id: 'fx-main' },
      },
    ]
    for (const { input, expected } of cases) {
      expect(serialiseCopilotContext(input)).toEqual(expected)
    }
  })
})
