// regulatoryBadge — RAG-badge helper for regulatory submissions (kx-1try).
//
// Compliance teams report regulatory-submission status using the classic
// red/amber/green (RAG) framing. The helper maps a status code into the
// badge tone (compliant|warning|breach|unknown), a colour token
// (green|amber|red|gray), an icon name (check|clock|alert|question), and
// an aria-label so screen readers narrate the status the same way the
// visual badge presents it.

export type RegulatoryStatus =
  | 'APPROVED'
  | 'ACTIVE'
  | 'PENDING_REVIEW'
  | 'DEADLINE_NEAR'
  | 'IN_BREACH'
  | 'DEADLINE_MISSED'

export type RegulatoryBadgeTone = 'compliant' | 'warning' | 'breach' | 'unknown'
export type RegulatoryBadgeColor = 'green' | 'amber' | 'red' | 'gray'
export type RegulatoryBadgeIcon = 'check' | 'clock' | 'alert' | 'question'

export interface RegulatoryBadge {
  tone: RegulatoryBadgeTone
  color: RegulatoryBadgeColor
  icon: RegulatoryBadgeIcon
  ariaLabel: string
}

const COMPLIANT_STATES: ReadonlySet<RegulatoryStatus> = new Set(['APPROVED', 'ACTIVE'])
const WARNING_STATES: ReadonlySet<RegulatoryStatus> = new Set([
  'PENDING_REVIEW',
  'DEADLINE_NEAR',
])
const BREACH_STATES: ReadonlySet<RegulatoryStatus> = new Set([
  'IN_BREACH',
  'DEADLINE_MISSED',
])

const COMPLIANT_LABELS: Record<string, string> = {
  APPROVED: 'Compliant: approved',
  ACTIVE: 'Compliant: active',
}
const WARNING_LABELS: Record<string, string> = {
  PENDING_REVIEW: 'Warning: pending review',
  DEADLINE_NEAR: 'Warning: deadline near',
}
const BREACH_LABELS: Record<string, string> = {
  IN_BREACH: 'Breach: model is in breach',
  DEADLINE_MISSED: 'Breach: regulatory deadline missed',
}

export function regulatoryBadge(status: RegulatoryStatus): RegulatoryBadge {
  if (COMPLIANT_STATES.has(status)) {
    return {
      tone: 'compliant',
      color: 'green',
      icon: 'check',
      ariaLabel: COMPLIANT_LABELS[status] ?? 'Compliant',
    }
  }
  if (WARNING_STATES.has(status)) {
    return {
      tone: 'warning',
      color: 'amber',
      icon: 'clock',
      ariaLabel: WARNING_LABELS[status] ?? 'Warning',
    }
  }
  if (BREACH_STATES.has(status)) {
    return {
      tone: 'breach',
      color: 'red',
      icon: 'alert',
      ariaLabel: BREACH_LABELS[status] ?? 'Breach',
    }
  }
  return {
    tone: 'unknown',
    color: 'gray',
    icon: 'question',
    ariaLabel: 'Unknown regulatory status',
  }
}
