import { describe, test, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SavedQueryChip } from './SavedQueryChip'
import type { SavedQuery } from '../api/savedQueries'
import {
  BUILTIN_SAVED_QUERIES,
  MAX_USER_SAVED_QUERIES,
  SAVED_QUERIES_KEY,
  deleteUserSavedQuery,
  loadSavedQueries,
  loadUserSavedQueries,
  saveUserQuery,
} from '../api/savedQueries'

const BUILTIN: SavedQuery = {
  id: 'limit-breaches',
  label: 'Limit breaches today',
  prompt: 'Which risk limits are in breach today?',
  builtin: true,
}

const USER: SavedQuery = {
  id: 'user-1',
  label: 'My VaR question',
  prompt: 'Why did VaR move?',
  builtin: false,
}

describe('SavedQueryChip', () => {
  test('renders the saved query label', () => {
    render(<SavedQueryChip query={USER} onSelect={() => {}} />)
    expect(screen.getByText('My VaR question')).toBeInTheDocument()
  })

  test('a built-in chip shows the Lock icon and no delete control', () => {
    render(<SavedQueryChip query={BUILTIN} onSelect={() => {}} onDelete={() => {}} />)
    expect(
      screen.getByTestId('saved-query-chip-lock-limit-breaches'),
    ).toBeInTheDocument()
    expect(
      screen.queryByTestId('saved-query-chip-delete-limit-breaches'),
    ).not.toBeInTheDocument()
  })

  test('a user chip has a delete control and no Lock icon', () => {
    render(<SavedQueryChip query={USER} onSelect={() => {}} onDelete={() => {}} />)
    expect(
      screen.getByTestId('saved-query-chip-delete-user-1'),
    ).toBeInTheDocument()
    expect(
      screen.queryByTestId('saved-query-chip-lock-user-1'),
    ).not.toBeInTheDocument()
  })

  test('clicking the chip fires onSelect with the query', async () => {
    const onSelect = vi.fn()
    render(<SavedQueryChip query={USER} onSelect={onSelect} />)
    await userEvent.click(screen.getByTestId('saved-query-chip-run-user-1'))
    expect(onSelect).toHaveBeenCalledWith(USER)
  })

  test('clicking the delete control fires onDelete with the query', async () => {
    const onDelete = vi.fn()
    render(<SavedQueryChip query={USER} onSelect={() => {}} onDelete={onDelete} />)
    await userEvent.click(screen.getByTestId('saved-query-chip-delete-user-1'))
    expect(onDelete).toHaveBeenCalledWith(USER)
  })
})

describe('savedQueries localStorage helper', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  test('ships exactly five built-in defaults, all locked', () => {
    expect(BUILTIN_SAVED_QUERIES).toHaveLength(5)
    expect(BUILTIN_SAVED_QUERIES.every((q) => q.builtin)).toBe(true)
  })

  test('built-in ids match the server template ids from §8.1', () => {
    expect(BUILTIN_SAVED_QUERIES.map((q) => q.id)).toEqual([
      'limit-breaches',
      'pnl-vs-yesterday',
      'var-week-drivers',
      'top-positions-risk-contribution',
      'vol-dislocations',
    ])
  })

  test('loadUserSavedQueries returns an empty list when nothing stored', () => {
    expect(loadUserSavedQueries()).toEqual([])
  })

  test('loadSavedQueries returns the built-ins followed by user queries', () => {
    saveUserQuery('Why did VaR move?')
    const all = loadSavedQueries()
    expect(all.slice(0, 5)).toEqual(BUILTIN_SAVED_QUERIES)
    expect(all).toHaveLength(6)
    expect(all[5].builtin).toBe(false)
  })

  test('saveUserQuery persists a user query under the localStorage key', () => {
    const result = saveUserQuery('Show me top movers')
    expect(result.ok).toBe(true)
    const raw = window.localStorage.getItem(SAVED_QUERIES_KEY)
    expect(raw).not.toBeNull()
    expect(loadUserSavedQueries()).toHaveLength(1)
    expect(loadUserSavedQueries()[0].prompt).toBe('Show me top movers')
  })

  test('saveUserQuery defaults the label to the prompt text', () => {
    saveUserQuery('What is my net delta?')
    expect(loadUserSavedQueries()[0].label).toBe('What is my net delta?')
  })

  test('saveUserQuery rejects a blank prompt without persisting', () => {
    const result = saveUserQuery('   ')
    expect(result).toEqual({ ok: false, reason: 'empty' })
    expect(loadUserSavedQueries()).toEqual([])
  })

  test('saveUserQuery enforces the twelve-query maximum', () => {
    for (let i = 0; i < MAX_USER_SAVED_QUERIES; i++) {
      expect(saveUserQuery(`query ${i}`).ok).toBe(true)
    }
    expect(loadUserSavedQueries()).toHaveLength(MAX_USER_SAVED_QUERIES)
    const overflow = saveUserQuery('one too many')
    expect(overflow).toEqual({ ok: false, reason: 'limit' })
    expect(loadUserSavedQueries()).toHaveLength(MAX_USER_SAVED_QUERIES)
  })

  test('deleteUserSavedQuery removes a user query', () => {
    const result = saveUserQuery('temporary')
    expect(result.ok).toBe(true)
    const id = result.ok ? result.query.id : ''
    deleteUserSavedQuery(id)
    expect(loadUserSavedQueries()).toEqual([])
  })

  test('deleteUserSavedQuery with a built-in id is a no-op', () => {
    saveUserQuery('keep me')
    deleteUserSavedQuery('limit-breaches')
    expect(loadUserSavedQueries()).toHaveLength(1)
    // built-ins are still all present in the full list
    expect(loadSavedQueries().filter((q) => q.builtin)).toHaveLength(5)
  })

  test('loadUserSavedQueries tolerates a malformed localStorage value', () => {
    window.localStorage.setItem(SAVED_QUERIES_KEY, 'not json')
    expect(loadUserSavedQueries()).toEqual([])
  })
})
