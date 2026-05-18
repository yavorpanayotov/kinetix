import { useMemo, useState } from 'react'
import { Bell, Plus, Trash2, AlertTriangle, AlertCircle, Info, Download, CheckCircle } from 'lucide-react'
import type { AlertRuleDto, AlertEventDto, CreateAlertRuleRequestDto } from '../types'
import { formatRelativeTime } from '../utils/format'
import { exportToCsv } from '../utils/exportCsv'
import { Card, Button, Badge, Input, Select, Spinner } from './ui'
import { ConfirmDialog } from './ui/ConfirmDialog'

type StatusFilter = 'TRIGGERED' | 'ACKNOWLEDGED' | 'ESCALATED' | 'RESOLVED' | 'ALL'

interface NotificationCenterProps {
  rules: AlertRuleDto[]
  alerts: AlertEventDto[]
  loading: boolean
  error: string | null
  onCreateRule: (request: CreateAlertRuleRequestDto) => void
  onDeleteRule: (ruleId: string) => void
}

const severityBadgeVariant: Record<string, 'critical' | 'warning' | 'info'> = {
  CRITICAL: 'critical',
  WARNING: 'warning',
  INFO: 'info',
}

const severityBorderColor: Record<string, string> = {
  CRITICAL: 'border-red-500',
  WARNING: 'border-yellow-500',
  INFO: 'border-blue-500',
}

const severityIcon: Record<string, typeof AlertTriangle> = {
  CRITICAL: AlertCircle,
  WARNING: AlertTriangle,
  INFO: Info,
}

const severityOrder: Record<string, number> = {
  CRITICAL: 0,
  WARNING: 1,
  INFO: 2,
}

export function NotificationCenter({
  rules,
  alerts,
  loading,
  error,
  onCreateRule,
  onDeleteRule,
}: NotificationCenterProps) {
  const [name, setName] = useState('')
  const [type, setType] = useState('VAR_BREACH')
  const [threshold, setThreshold] = useState('')
  const [operator, setOperator] = useState('GREATER_THAN')
  const [severity, setSeverity] = useState('CRITICAL')
  const [channels, setChannels] = useState<string[]>(['IN_APP'])
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')

  const sortedAlerts = useMemo(() => {
    const filtered = statusFilter === 'ALL'
      ? alerts
      : alerts.filter((a) => a.status === statusFilter)
    return [...filtered].sort((a, b) => {
      const timeCompare = new Date(b.triggeredAt).getTime() - new Date(a.triggeredAt).getTime()
      if (timeCompare !== 0) return timeCompare
      return (severityOrder[a.severity] ?? 99) - (severityOrder[b.severity] ?? 99)
    })
  }, [alerts, statusFilter])

  function handleChannelToggle(ch: string) {
    setChannels((prev) =>
      prev.includes(ch) ? prev.filter((c) => c !== ch) : [...prev, ch],
    )
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    onCreateRule({
      name,
      type,
      threshold: Number(threshold),
      operator,
      severity,
      channels,
    })
    setName('')
    setThreshold('')
  }

  return (
    <Card
      data-testid="notification-center"
      header={<span className="flex items-center gap-1.5"><Bell className="h-4 w-4" />Notification Center</span>}
    >
      {loading && (
        <div data-testid="notification-loading" className="flex items-center gap-2 text-slate-500 text-sm">
          <Spinner size="sm" />
          Loading notifications...
        </div>
      )}

      {error && (
        <div data-testid="notification-error" className="text-red-600 text-sm mb-3">
          {error}
        </div>
      )}

      {/* Create Rule Form */}
      <div data-testid="create-rule-form" className="mb-4 p-3 bg-slate-50 rounded-lg">
        <h3 className="text-sm font-semibold text-slate-700 mb-2">Create Alert Rule</h3>
        <form onSubmit={handleSubmit} className="grid grid-cols-3 gap-2 text-sm">
          <Input
            data-testid="rule-name-input"
            placeholder="Rule name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
          <Select
            data-testid="rule-type-select"
            value={type}
            onChange={(e) => setType(e.target.value)}
          >
            <option value="VAR_BREACH">VaR Breach</option>
            <option value="PNL_THRESHOLD">P&amp;L Threshold</option>
            <option value="RISK_LIMIT">Risk Limit</option>
            <option value="DELTA_BREACH">Delta Breach</option>
            <option value="VEGA_BREACH">Vega Breach</option>
            <option value="CONCENTRATION">Concentration</option>
            <option value="MARGIN_BREACH">Margin Breach</option>
          </Select>
          <Input
            data-testid="rule-threshold-input"
            type="number"
            placeholder="Threshold"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            required
          />
          <Select
            data-testid="rule-operator-select"
            value={operator}
            onChange={(e) => setOperator(e.target.value)}
          >
            {/*
              EQUALS removed: "Equal to" for floating-point risk values never fires.
              Backend still accepts EQUALS for pre-existing rules, but users can no
              longer create new rules with it. See docs/plans/ui-overhaul.md §3.3.
            */}
            <option value="GREATER_THAN">Above</option>
            <option value="LESS_THAN">Below</option>
          </Select>
          <Select
            data-testid="rule-severity-select"
            value={severity}
            onChange={(e) => setSeverity(e.target.value)}
          >
            <option value="CRITICAL">CRITICAL</option>
            <option value="WARNING">WARNING</option>
            <option value="INFO">INFO</option>
          </Select>
          <div className="col-span-3 flex flex-wrap items-center gap-4">
            <span className="text-xs text-slate-500 font-medium">Channels:</span>
            {['IN_APP', 'EMAIL', 'WEBHOOK'].map((ch) => (
              <label key={ch} className="flex items-center gap-1 text-xs">
                <input
                  type="checkbox"
                  data-testid={`channel-${ch}`}
                  checked={channels.includes(ch)}
                  onChange={() => handleChannelToggle(ch)}
                />
                {ch}
              </label>
            ))}
          </div>
          <Button
            data-testid="create-rule-btn"
            type="submit"
            variant="primary"
            size="md"
            icon={<Plus className="h-3.5 w-3.5" />}
            className="col-span-3"
          >
            Create Rule
          </Button>
        </form>
      </div>

      {/* Alert Rules Table */}
      <h3 className="text-sm font-semibold text-slate-700 mb-2">Alert Rules</h3>
      <table data-testid="rules-table" className="w-full text-sm mb-4">
        <thead>
          <tr className="border-b text-left text-slate-600">
            <th className="py-2">Name</th>
            <th className="py-2">Type</th>
            <th className="py-2 text-right">Threshold</th>
            <th className="py-2">Severity</th>
            <th className="py-2">Enabled</th>
            <th className="py-2"></th>
          </tr>
        </thead>
        <tbody>
          {rules.map((rule) => (
            <tr key={rule.id} className="border-b hover:bg-slate-50 transition-colors">
              <td className="py-1.5">{rule.name}</td>
              <td className="py-1.5">{rule.type}</td>
              <td className="py-1.5 text-right">{rule.threshold.toLocaleString()}</td>
              <td className="py-1.5">
                <Badge variant={severityBadgeVariant[rule.severity] ?? 'neutral'}>
                  {rule.severity}
                </Badge>
              </td>
              <td className="py-1.5">{rule.enabled ? 'Yes' : 'No'}</td>
              <td className="py-1.5">
                <button
                  data-testid={`delete-rule-${rule.id}`}
                  onClick={() => setPendingDeleteId(rule.id)}
                  className="text-red-500 hover:text-red-700 transition-colors"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Recent Alerts */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-slate-700">Recent Alerts</h3>
          <div data-testid="alert-status-filters" className="flex gap-1">
            {(['ALL', 'TRIGGERED', 'ACKNOWLEDGED', 'ESCALATED', 'RESOLVED'] as StatusFilter[]).map((s) => (
              <button
                key={s}
                data-testid={`status-filter-${s.toLowerCase()}`}
                onClick={() => setStatusFilter(s)}
                className={`px-2 py-0.5 text-xs rounded-full transition-colors ${
                  statusFilter === s
                    ? s === 'ESCALATED'
                      ? 'bg-orange-100 text-orange-800 font-medium'
                      : 'bg-blue-100 text-blue-800 font-medium'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                }`}
              >
                {s === 'ALL' ? 'All' : s.charAt(0) + s.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
        </div>
        {sortedAlerts.length > 0 && (
          <button
            data-testid="alerts-csv-export"
            onClick={() => {
              const headers = ['Severity', 'Type', 'Message', 'Book', 'Value', 'Threshold', 'Status', 'Time']
              const rows = sortedAlerts.map((a) => [
                a.severity,
                a.type,
                a.message,
                a.bookId,
                String(a.currentValue),
                String(a.threshold),
                a.status,
                a.triggeredAt,
              ])
              exportToCsv('alerts.csv', headers, rows)
            }}
            className="inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 border border-slate-300 rounded hover:bg-slate-50 transition-colors"
          >
            <Download className="h-3.5 w-3.5" />
            Export CSV
          </button>
        )}
      </div>
      <div data-testid="alerts-list" className="space-y-2">
        {sortedAlerts.map((alert) => {
          const SevIcon = severityIcon[alert.severity] ?? Info
          return (
            <div
              key={alert.id}
              className={`flex items-start gap-2 p-2 bg-slate-50 rounded text-sm border-l-4 ${severityBorderColor[alert.severity] ?? 'border-gray-300'}`}
            >
              <SevIcon className="h-4 w-4 mt-0.5 shrink-0 text-slate-500" />
              <span
                data-testid={`severity-badge-${alert.id}`}
                className={`px-2 py-0.5 rounded text-xs font-medium ${
                  alert.severity === 'CRITICAL' ? 'bg-red-100 text-red-800' :
                  alert.severity === 'WARNING' ? 'bg-yellow-100 text-yellow-800' :
                  'bg-blue-100 text-blue-800'
                }`}
              >
                {alert.severity}
              </span>
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-slate-800">{alert.message}</span>
                  {alert.status === 'ESCALATED' && (
                    <span
                      data-testid={`escalation-badge-${alert.id}`}
                      className="px-1.5 py-0.5 text-xs font-semibold bg-orange-100 text-orange-800 rounded"
                    >
                      ESCALATED
                    </span>
                  )}
                </div>
                <div className="text-xs text-slate-500">
                  Book: {alert.bookId} | {formatRelativeTime(alert.triggeredAt)}
                  {alert.status === 'RESOLVED' && alert.resolvedAt && (
                    <span className="ml-2 text-green-600">
                      <CheckCircle className="inline h-3 w-3 mr-0.5" />
                      Resolved {formatRelativeTime(alert.resolvedAt)}
                    </span>
                  )}
                  {alert.status === 'ACKNOWLEDGED' && (
                    <span className="ml-2 text-slate-500">
                      <CheckCircle className="inline h-3 w-3 mr-0.5" />
                      Acknowledged
                    </span>
                  )}
                  {alert.status === 'ESCALATED' && alert.escalatedTo && (
                    <span className="ml-2 text-orange-700">
                      Escalated to: <span data-testid={`escalated-to-${alert.id}`}>{alert.escalatedTo}</span>
                      {alert.escalatedAt && (
                        <span data-testid={`escalated-at-${alert.id}`} className="ml-1">
                          ({formatRelativeTime(alert.escalatedAt)})
                        </span>
                      )}
                    </span>
                  )}
                </div>
              </div>
            </div>
          )
        })}
      </div>

      <ConfirmDialog
        open={pendingDeleteId !== null}
        title="Delete Alert Rule"
        message="Are you sure you want to delete this alert rule? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        variant="danger"
        onConfirm={() => {
          if (pendingDeleteId) onDeleteRule(pendingDeleteId)
          setPendingDeleteId(null)
        }}
        onCancel={() => setPendingDeleteId(null)}
      />
    </Card>
  )
}
