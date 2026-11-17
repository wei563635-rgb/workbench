const FILE_PATH = /^\/api\/files\/demo-download\/file_[a-f0-9]{32}$/i

// Older records contain an HTTP server address; use the current HTTPS origin for platform files.
export function workbenchFileUrl(value) {
  if (typeof value !== 'string' || !value) return value
  try {
    const url = new URL(value, 'https://workbench.local')
    return FILE_PATH.test(url.pathname) ? `${url.pathname}${url.search}` : value
  } catch {
    return value
  }
}
