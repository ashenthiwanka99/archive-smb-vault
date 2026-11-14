// Sanitization helpers: friendly error messages + safe object copies.

const ERROR_PATTERNS = [
  { test: /access.?denied|auth|logon|credential|password|STATUS_LOGON_FAILURE/i, msg: 'Access denied. Check your username and password.' },
  { test: /timed? ?out|TimeoutError|ETIMEDOUT/i, msg: 'The server took too long to respond.' },
  { test: /ENOTFOUND|host|dns|unknown host/i, msg: 'Could not reach the server. Check the host address.' },
  { test: /ECONNREFUSED|connection refused/i, msg: 'Server refused the connection.' },
  { test: /no route to host|network is unreachable/i, msg: 'No network route to the server.' },
  { test: /STATUS_OBJECT_NAME_NOT_FOUND|not found|does not exist/i, msg: 'File or folder not found.' },
  { test: /STATUS_OBJECT_NAME_COLLISION|already exists/i, msg: 'A file with that name already exists.' },
  { test: /STATUS_SHARING_VIOLATION|in use|locked/i, msg: 'File is in use by another process.' },
  { test: /STATUS_DISK_FULL|no space/i, msg: 'Not enough free space on the server.' }
]

export function friendlyError(err) {
  const raw = typeof err === 'string' ? err : (err?.message || '')
  if (!raw) return 'Something went wrong.'
  for (const p of ERROR_PATTERNS) {
    if (p.test.test(raw)) return p.msg
  }
  // Novel error: don't leak Java/jcifs stack noise to the user.  If the raw
  // string contains package paths or internal prefixes, collapse it to a
  // generic network error.  Raw still goes to console for debugging.
  if (/java\.|jcifs\.|smb:\/\/|at\s+\S+\(/.test(raw)) {
    console.warn('[sanitize] raw error:', raw)
    return 'Network error. Check your connection and try again.'
  }
  const firstLine = raw.split('\n')[0]
  return firstLine.length > 140 ? firstLine.slice(0, 140) + '…' : firstLine
}

// Whitelist-copy an object so persisted data can't carry __proto__ or
// unexpected keys from an untrusted source.
export function pickSafe(obj, keys) {
  if (!obj || typeof obj !== 'object') return {}
  const out = {}
  for (const k of keys) {
    if (k === '__proto__' || k === 'constructor' || k === 'prototype') continue
    if (Object.prototype.hasOwnProperty.call(obj, k)) out[k] = obj[k]
  }
  return out
}
