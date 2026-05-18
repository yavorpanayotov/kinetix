import {
  RefreshCw,
  ExternalLink,
  Server,
  Globe,
  Briefcase,
  DollarSign,
  Shield,
  Bell,
  Percent,
  Database,
  Activity,
  GitMerge,
  type LucideIcon,
} from 'lucide-react'
import type { SystemHealthResponse } from '../api/system'
import { Card, Button, StatusDot, Spinner, ErrorCard, SectionHeading } from './ui'

interface Props {
  health: SystemHealthResponse | null
  loading: boolean
  error: string | null
  onRefresh: () => void
}

const isProduction = () =>
  typeof window !== 'undefined' && window.location.hostname.includes('kinetixrisk.ai')

const grafanaBase = () =>
  isProduction() ? 'https://grafana.kinetixrisk.ai' : 'http://localhost:3000'

const SERVICE_LABELS: Record<string, string> = {
  gateway: 'Gateway',
  'position-service': 'Position Service',
  'price-service': 'Prices',
  'risk-orchestrator': 'Risk Orchestrator',
  'notification-service': 'Notifications',
  'rates-service': 'Rates',
  'reference-data-service': 'Reference Data',
  'volatility-service': 'Volatility',
  'correlation-service': 'Correlations',
}

const SERVICE_ICONS: Record<string, LucideIcon> = {
  gateway: Globe,
  'position-service': Briefcase,
  'price-service': DollarSign,
  'risk-orchestrator': Shield,
  'notification-service': Bell,
  'rates-service': Percent,
  'reference-data-service': Database,
  'volatility-service': Activity,
  'correlation-service': GitMerge,
}

const SERVICE_DASHBOARD_PATHS: Record<string, string> = {
  gateway: '/d/kinetix-service-overview',
  'position-service': '/d/kinetix-trade-flow',
  'price-service': '/d/kinetix-prices',
  'risk-orchestrator': '/d/kinetix-risk-orchestrator',
  'notification-service': '/d/kinetix-service-overview',
  'rates-service': '/d/kinetix-service-overview',
  'reference-data-service': '/d/kinetix-service-overview',
  'volatility-service': '/d/kinetix-service-overview',
  'correlation-service': '/d/kinetix-service-overview',
}

const OBSERVABILITY_LINK_DEFS = [
  {
    name: 'System Health',
    path: '/d/kinetix-system-health',
    description: 'Request rate, error rate, latency, JVM, Kafka lag',
  },
  {
    name: 'Service Overview',
    path: '/d/kinetix-service-overview',
    description: 'Per-service request rate, errors, latency',
  },
  {
    name: 'Risk Overview',
    path: '/d/kinetix-risk-overview',
    description: 'VaR gauge, ES, component breakdown',
  },
  {
    name: 'Trade Flow',
    path: '/d/kinetix-trade-flow',
    description: 'Trade lifecycle, booking rate, amends & cancels',
  },
  {
    name: 'Database Health',
    path: '/d/kinetix-database-health',
    description: 'Connection pools, query latency, table sizes',
  },
  {
    name: 'Kafka Health',
    path: '/d/kinetix-kafka-health',
    description: 'Consumer lag, partition health, throughput',
  },
  {
    name: 'Service Logs',
    path: '/d/kinetix-service-logs',
    description: 'Log volume, errors, warnings, full log lines',
  },
  {
    name: 'Prometheus',
    path: null,
    devOnlyUrl: 'http://localhost:9090',
    description: 'Raw metrics & alert rules',
  },
  {
    name: 'Grafana',
    path: '',
    description: 'All dashboards',
  },
]

export function SystemDashboard({ health, loading, error, onRefresh }: Props) {
  if (loading) {
    return (
      <div data-testid="system-loading" className="flex items-center gap-2 text-slate-500">
        <Spinner size="sm" />
        Loading system health...
      </div>
    )
  }

  if (error) {
    return <ErrorCard message={error} onRetry={onRefresh} data-testid="system-error" />
  }

  const services = health?.services ?? {}
  const overallUp = health?.status === 'UP'

  return (
    <div data-testid="system-dashboard">
      <div
        data-testid="system-status-banner"
        className={`mb-6 rounded-lg px-4 py-3 text-sm font-medium flex items-center justify-between ${
          overallUp
            ? 'bg-green-50 text-green-800 border border-green-200'
            : 'bg-yellow-50 text-yellow-800 border border-yellow-200'
        }`}
      >
        <span>{overallUp ? 'All Systems Operational' : 'Degraded'}</span>
        <Button
          data-testid="system-refresh-btn"
          variant="secondary"
          size="sm"
          icon={<RefreshCw className="h-3 w-3" />}
          onClick={onRefresh}
        >
          Refresh
        </Button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="md:col-span-2">
          <SectionHeading as="h2" className="mb-3 flex items-center gap-2">
            <Server className="h-5 w-5 text-slate-500" />
            Service Health
          </SectionHeading>
          <div
            data-testid="service-health-grid"
            className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-4"
          >
            {Object.entries(services).map(([key, svc]) => {
              const up = svc.status === 'READY'
              const Icon = SERVICE_ICONS[key]
              const dashboardPath = SERVICE_DASHBOARD_PATHS[key]
              const dashboardUrl = dashboardPath != null ? `${grafanaBase()}${dashboardPath}` : undefined
              return (
                <Card key={key} data-testid={`service-card-${key}`}>
                  <div className="flex items-center gap-2">
                    <StatusDot
                      data-testid={`service-status-dot-${key}`}
                      status={up ? 'up' : 'down'}
                      pulse={up}
                    />
                    {Icon && <Icon className="h-4 w-4 text-slate-400" />}
                    <span className="font-medium">
                      {SERVICE_LABELS[key] ?? key}
                    </span>
                    {dashboardUrl && (
                      <a
                        href={dashboardUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        data-testid={`service-grafana-link-${key}`}
                        title="Open Grafana dashboard"
                        className="ml-auto text-slate-400 hover:text-orange-500 transition-colors"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <img src="/grafana.svg" alt="Grafana" className="h-4 w-4" />
                      </a>
                    )}
                  </div>
                  <p
                    data-testid={`service-status-text-${key}`}
                    className={`mt-1 text-sm ${up ? 'text-green-700' : 'text-red-700'}`}
                  >
                    {svc.status}
                  </p>
                </Card>
              )
            })}
          </div>
        </div>

        <div className="md:col-span-1">
          <SectionHeading as="h2" className="mb-3">Observability</SectionHeading>
          <div data-testid="observability-links" className="space-y-2">
            {OBSERVABILITY_LINK_DEFS.map((link) => {
              const url = link.path != null
                ? `${grafanaBase()}${link.path}`
                : link.devOnlyUrl ?? null

              if (url == null && isProduction()) return null

              return (
                <a
                  key={link.name}
                  href={url ?? '#'}
                  target="_blank"
                  rel="noopener noreferrer"
                  data-testid={`obs-link-${link.name.toLowerCase().replace(/\s+/g, '-')}`}
                  className="flex items-center justify-between rounded-md px-3 py-2 hover:bg-slate-50 transition-colors group"
                >
                  <div>
                    <span className="font-medium text-primary-600">{link.name}</span>
                    <p className="text-sm text-slate-500">{link.description}</p>
                  </div>
                  <ExternalLink className="h-3.5 w-3.5 flex-shrink-0 text-slate-400 group-hover:text-primary-500 transition-colors" />
                </a>
              )
            })}
          </div>
        </div>
      </div>
    </div>
  )
}
