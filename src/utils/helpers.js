// Shared utility functions

/**
 * Format bytes into human-readable size string
 */
export function formatSize(bytes) {
  if (!bytes || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(size >= 100 ? 0 : 1)} ${units[i]}`
}

/**
 * Format timestamp into relative date string
 */
export function formatDate(timestamp) {
  if (!timestamp) return ''
  const d = new Date(timestamp)
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const diffMs = todayStart - d.getTime()
  const diffDays = Math.floor(diffMs / 86400000)

  if (d.getTime() >= todayStart) return 'Today'
  if (diffDays === 0) return 'Yesterday'
  if (diffDays === 1) return 'Yesterday'
  if (diffDays < 7) return `${diffDays + 1}d ago`

  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
}

/**
 * Get file type from filename and directory flag
 */
export function getFileType(name, isDirectory) {
  if (isDirectory) return 'folder'
  const ext = name.split('.').pop()?.toLowerCase() || ''
  const types = {
    video: ['mp4', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm4v', 'ts', 'mts', 'vob', 'm2ts', '3gp'],
    image: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico', 'tiff', 'tif', 'raw', 'heic', 'heif', 'avif'],
    audio: ['mp3', 'wav', 'flac', 'aac', 'ogg', 'wma', 'm4a', 'opus', 'aiff', 'alac', 'ape'],
    document: ['pdf', 'doc', 'docx', 'txt', 'md', 'rtf', 'xls', 'xlsx', 'ppt', 'pptx', 'csv', 'odt', 'ods', 'odp', 'pages', 'numbers', 'key'],
    code: ['json', 'xml', 'html', 'css', 'js', 'ts', 'py', 'java', 'cpp', 'c', 'h', 'sh', 'yml', 'yaml', 'toml', 'ini', 'conf', 'log'],
    archive: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'iso', 'dmg', 'pkg', 'deb', 'rpm']
  }
  for (const [type, exts] of Object.entries(types)) {
    if (exts.includes(ext)) return type
  }
  return 'unknown'
}

/**
 * Connection status constants
 */
export const CONNECTION = {
  ONLINE: 'online',
  OFFLINE: 'offline',
  CONNECTING: 'connecting',
  ERROR: 'error'
}

/**
 * View mode constants
 */
export const VIEW_MODE = {
  LIST: 'list',
  GRID: 'grid'
}

/**
 * Truncate filename for grid display
 */
export function truncateName(name, maxLen = 20) {
  if (!name) return ''
  if (name.length <= maxLen) return name
  const ext = name.includes('.') ? '.' + name.split('.').pop() : ''
  const base = name.slice(0, name.length - ext.length)
  const keep = maxLen - ext.length - 3
  return keep > 0 ? base.slice(0, keep) + '...' + ext : name.slice(0, maxLen - 3) + '...'
}

/**
 * Debounce helper
 */
export function debounce(fn, delay = 300) {
  let timer
  return (...args) => {
    clearTimeout(timer)
    timer = setTimeout(() => fn(...args), delay)
  }
}

/**
 * Validate IP address format
 */
export function isValidHost(host) {
  if (!host || !host.trim()) return false
  // IPv4
  const ipv4 = /^(\d{1,3}\.){3}\d{1,3}$/
  if (ipv4.test(host)) {
    return host.split('.').every(n => parseInt(n) >= 0 && parseInt(n) <= 255)
  }
  // Hostname
  const hostname = /^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$/
  return hostname.test(host)
}
