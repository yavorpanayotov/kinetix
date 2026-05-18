import { describe, it, expect } from 'vitest'
import {
  findNearestPoint,
  resolveSnapshotTarget,
  SNAPSHOT_PRESETS,
  type SnapshotPreset,
} from './snapshotCompare'

type Point = { timestamp: string; value: number }

const NOW = new Date('2026-03-25T15:00:00Z')

describe('findNearestPoint', () => {
  it('returns null when the series is empty', () => {
    expect(findNearestPoint<Point>([], NOW.toISOString())).toBeNull()
  })

  it('returns null when every point is strictly after the target', () => {
    const points: Point[] = [
      { timestamp: '2026-03-25T15:10:00Z', value: 100 },
      { timestamp: '2026-03-25T15:20:00Z', value: 200 },
    ]
    expect(findNearestPoint(points, NOW.toISOString())).toBeNull()
  })

  it('returns the closest point at or before the target', () => {
    const points: Point[] = [
      { timestamp: '2026-03-25T13:00:00Z', value: 100 },
      { timestamp: '2026-03-25T14:50:00Z', value: 250 },
      { timestamp: '2026-03-25T14:58:00Z', value: 275 }, // closest at-or-before 15:00
      { timestamp: '2026-03-25T15:05:00Z', value: 300 }, // strictly after — ignored
    ]
    const nearest = findNearestPoint(points, NOW.toISOString())
    expect(nearest).not.toBeNull()
    expect(nearest!.value).toBe(275)
  })

  it('handles unsorted input by considering all points', () => {
    const points: Point[] = [
      { timestamp: '2026-03-25T14:58:00Z', value: 275 },
      { timestamp: '2026-03-25T13:00:00Z', value: 100 },
    ]
    const nearest = findNearestPoint(points, NOW.toISOString())
    expect(nearest!.value).toBe(275)
  })

  it('returns the exact match when timestamps coincide', () => {
    const points: Point[] = [
      { timestamp: '2026-03-25T15:00:00Z', value: 999 },
      { timestamp: '2026-03-25T14:00:00Z', value: 100 },
    ]
    const nearest = findNearestPoint(points, NOW.toISOString())
    expect(nearest!.value).toBe(999)
  })
})

describe('resolveSnapshotTarget', () => {
  it('exposes the three supported presets', () => {
    const ids = SNAPSHOT_PRESETS.map((p) => p.id)
    expect(ids).toEqual(['-15m', '-1h', 'eod-yesterday'])
  })

  it('subtracts 15 minutes for -15m', () => {
    const target = resolveSnapshotTarget('-15m', NOW)
    expect(target).toBe(new Date('2026-03-25T14:45:00Z').toISOString())
  })

  it('subtracts 1 hour for -1h', () => {
    const target = resolveSnapshotTarget('-1h', NOW)
    expect(target).toBe(new Date('2026-03-25T14:00:00Z').toISOString())
  })

  it('returns yesterday 17:00 UTC for EOD yesterday', () => {
    const target = resolveSnapshotTarget('eod-yesterday', NOW)
    expect(target).toBe(new Date('2026-03-24T17:00:00Z').toISOString())
  })

  it('returns null for an unrecognised preset', () => {
    const target = resolveSnapshotTarget('off' as SnapshotPreset, NOW)
    expect(target).toBeNull()
  })
})
