// Promise timeout wrapper. Does NOT cancel the underlying native op —
// the native layer needs its own timeout — but prevents the UI from hanging forever.

export class TimeoutError extends Error {
  constructor(message = 'Operation timed out') {
    super(message)
    this.name = 'TimeoutError'
    this.code = 'TIMEOUT'
  }
}

export function withTimeout(promise, ms, label = 'operation') {
  if (!ms || ms <= 0) return promise
  let timer
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new TimeoutError(`${label} timed out after ${ms}ms`)), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}
