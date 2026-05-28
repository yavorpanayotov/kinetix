// Tests for the regulatory RAG-badge helper (kx-1try).
//
// Compliance teams report at-a-glance status on regulatory submissions
// using the classic red/amber/green (RAG) framing: green means the model
// is approved and active, amber means a review is pending or imminent,
// red means the model is in breach or has missed a regulatory deadline.
// The helper returns the badge tone, colour token, status icon, and an
// accessible aria-label so the badge component renders the same triad
// across the regulatory dashboards.

import { describe, it, expect } from 'vitest'

import { regulatoryBadge, type RegulatoryStatus } from './regulatoryBadge'

describe('regulatoryBadge', () => {
  it('returns the compliant badge for APPROVED', () => {
    expect(regulatoryBadge('APPROVED')).toEqual({
      tone: 'compliant',
      color: 'green',
      icon: 'check',
      ariaLabel: 'Compliant: approved',
    })
  })

  it('returns the compliant badge for ACTIVE', () => {
    expect(regulatoryBadge('ACTIVE').tone).toBe('compliant')
  })

  it('returns the warning badge for PENDING_REVIEW', () => {
    expect(regulatoryBadge('PENDING_REVIEW')).toEqual({
      tone: 'warning',
      color: 'amber',
      icon: 'clock',
      ariaLabel: 'Warning: pending review',
    })
  })

  it('returns the warning badge for DEADLINE_NEAR', () => {
    expect(regulatoryBadge('DEADLINE_NEAR').tone).toBe('warning')
  })

  it('returns the breach badge for IN_BREACH', () => {
    expect(regulatoryBadge('IN_BREACH')).toEqual({
      tone: 'breach',
      color: 'red',
      icon: 'alert',
      ariaLabel: 'Breach: model is in breach',
    })
  })

  it('returns the breach badge for DEADLINE_MISSED', () => {
    expect(regulatoryBadge('DEADLINE_MISSED').tone).toBe('breach')
  })

  it('returns the unknown badge for an unrecognised status', () => {
    expect(regulatoryBadge('GARBAGE' as RegulatoryStatus)).toEqual({
      tone: 'unknown',
      color: 'gray',
      icon: 'question',
      ariaLabel: 'Unknown regulatory status',
    })
  })

  it('uses an icon name that matches the tone (compliant -> check, warning -> clock, breach -> alert)', () => {
    expect(regulatoryBadge('APPROVED').icon).toBe('check')
    expect(regulatoryBadge('PENDING_REVIEW').icon).toBe('clock')
    expect(regulatoryBadge('IN_BREACH').icon).toBe('alert')
  })

  it('uses a colour token that matches the tone (compliant -> green, warning -> amber, breach -> red)', () => {
    expect(regulatoryBadge('APPROVED').color).toBe('green')
    expect(regulatoryBadge('PENDING_REVIEW').color).toBe('amber')
    expect(regulatoryBadge('IN_BREACH').color).toBe('red')
  })
})
