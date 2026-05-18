const KNOWN_CURRENCIES: Record<string, string> = {
  USD: 'en-US',
  EUR: 'en-IE',
  GBP: 'en-GB',
  JPY: 'ja-JP',
}

export function formatCurrency(value: string | number, currency = 'USD'): string {
  const num = typeof value === 'string' ? Number(value) : value
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(num)
}

export function formatMoney(amount: string, currency: string): string {
  const locale = KNOWN_CURRENCIES[currency]
  if (!locale) {
    return `${amount} ${currency}`
  }
  const rounded = Math.round(Number(amount) * 100) / 100
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
  }).format(rounded)
}

// Like formatMoney, but for signed P&L values: prefixes "+" on strictly
// positive amounts (after rounding to the cent). Negative amounts keep their
// native minus sign; zero, negative zero, NaN, and infinities are returned
// unchanged. Intended for use wherever pnlColorClass is applied, so users who
// cannot distinguish red/green still see a sign cue.
export function formatSignedMoney(amount: string, currency: string): string {
  const formatted = formatMoney(amount, currency)
  if (!(currency in KNOWN_CURRENCIES)) {
    return formatted
  }
  const rounded = Math.round(Number(amount) * 100) / 100
  if (Number.isFinite(rounded) && rounded > 0) {
    return `+${formatted}`
  }
  return formatted
}

export function formatQuantity(amount: string): string {
  const num = Number(amount)
  if (!Number.isFinite(num)) return amount
  return num.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
}

export function formatRelativeTime(isoString: string): string {
  const now = Date.now()
  const then = new Date(isoString).getTime()
  const diffMs = now - then

  if (diffMs < 0) return 'just now'

  const seconds = Math.floor(diffMs / 1000)
  if (seconds < 60) return 'just now'

  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`

  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`

  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

export function formatTimestamp(isoString: string): string {
  const date = new Date(isoString)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function formatTimeOnly(isoString: string): string {
  const date = new Date(isoString)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function formatChartTime(date: Date, rangeDays: number): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  if (rangeDays <= 1) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  return `${months[date.getMonth()]} ${pad(date.getDate())}`
}

export function formatDuration(ms: number): string {
  if (ms < 1000) {
    const seconds = ms / 1000
    return `${seconds.toFixed(1)}s`
  }
  const totalSeconds = Math.round(ms / 1000)
  if (totalSeconds < 60) {
    return `${totalSeconds}s`
  }
  const minutes = Math.floor(totalSeconds / 60)
  const remainingSeconds = totalSeconds % 60
  return `${minutes}m ${remainingSeconds}s`
}

export function formatNum(value: string | number, decimals = 2): string {
  const num = typeof value === 'string' ? Number(value) : value
  return num.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })
}

export function pnlColorClass(amount: string): string {
  const value = Number(amount)
  if (value > 0) return 'text-green-600 dark:text-green-400'
  if (value < 0) return 'text-red-600 dark:text-red-400'
  return 'text-gray-500 dark:text-gray-400'
}
