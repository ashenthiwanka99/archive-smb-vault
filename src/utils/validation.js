// Validation utilities for SMB paths, filenames, and user input.
// Client-side defense in depth — native layer should validate too.

const RESERVED_WINDOWS_NAMES = new Set([
  'CON', 'PRN', 'AUX', 'NUL',
  'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
  'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
])

const INVALID_FILENAME_CHARS = /[\\/:*?"<>|\x00-\x1f]/
const MAX_FILENAME_LENGTH = 255
const MAX_PATH_LENGTH = 4096

export function validateFilename(name) {
  if (typeof name !== 'string') return { ok: false, error: 'Name must be a string' }
  const trimmed = name.trim()
  if (!trimmed) return { ok: false, error: 'Name cannot be empty' }
  if (trimmed.length > MAX_FILENAME_LENGTH) {
    return { ok: false, error: `Name exceeds ${MAX_FILENAME_LENGTH} characters` }
  }
  if (trimmed === '.' || trimmed === '..') {
    return { ok: false, error: 'Reserved name' }
  }
  if (INVALID_FILENAME_CHARS.test(trimmed)) {
    return { ok: false, error: 'Name contains invalid characters' }
  }
  if (trimmed.endsWith('.') || trimmed.endsWith(' ')) {
    return { ok: false, error: 'Name cannot end with space or period' }
  }
  const base = trimmed.split('.')[0].toUpperCase()
  if (RESERVED_WINDOWS_NAMES.has(base)) {
    return { ok: false, error: 'Reserved system name' }
  }
  return { ok: true, value: trimmed }
}

// Relative SMB path (segments joined by '/'). No leading slash, no '..'.
export function validateRelativePath(path) {
  if (path === '' || path === null || path === undefined) return { ok: true, value: '' }
  if (typeof path !== 'string') return { ok: false, error: 'Path must be a string' }
  if (path.length > MAX_PATH_LENGTH) return { ok: false, error: 'Path too long' }
  if (path.startsWith('/') || path.startsWith('\\')) {
    return { ok: false, error: 'Path must be relative' }
  }
  if (path.includes('\x00')) return { ok: false, error: 'Null byte in path' }
  const segments = path.split('/').filter(Boolean)
  for (const seg of segments) {
    if (seg === '..' || seg === '.') {
      return { ok: false, error: 'Path traversal not allowed' }
    }
    const r = validateFilename(seg)
    if (!r.ok) return { ok: false, error: `Invalid segment "${seg}": ${r.error}` }
  }
  return { ok: true, value: segments.join('/') }
}

export function joinPath(base, name) {
  const b = (base || '').replace(/\/+$/, '')
  return b ? `${b}/${name}` : name
}
