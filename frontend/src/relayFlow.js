// Aggregate each adjacent pair once; all three columns conserve token totals.
// O(F + N log N), where F is flow count and N is distinct nodes.
export function layoutRelayFlow(flows = []) {
  const fields = ['department', 'tool', 'model']
  const columns = fields.map(() => new Map())
  const pairs = [new Map(), new Map()]
  for (const row of flows) {
    const value = Number(row.tokens)
    if (!Number.isFinite(value) || value <= 0) continue
    fields.forEach((field, i) => columns[i].set(row[field], (columns[i].get(row[field]) || 0) + value))
    for (let i = 0; i < 2; i++) {
      const key = JSON.stringify([row[fields[i]], row[fields[i + 1]]])
      pairs[i].set(key, (pairs[i].get(key) || 0) + value)
    }
  }
  const count = Math.max(1, ...columns.map(c => c.size))
  const height = Math.max(240, count * 46)
  const total = [...columns[0].values()].reduce((a, b) => a + b, 0)
  const scale = total ? (height - 32 - (count - 1) * 24) / total : 0
  const nodes = [], lookup = new Map()
  columns.forEach((column, i) => {
    let y = 16
    for (const [label, value] of [...column].sort((a, b) => b[1] - a[1] || String(a[0]).localeCompare(String(b[0])))) {
      const id = JSON.stringify([i, label]), node = { id, label, column: i, value, x: [12, 392, 762][i], y, height: value * scale, incoming: 0, outgoing: 0 }
      nodes.push(node); lookup.set(id, node); y += node.height + 24
    }
  })
  const links = []
  pairs.forEach((pair, i) => {
    for (const [key, value] of pair) {
      const [a, b] = JSON.parse(key), from = lookup.get(JSON.stringify([i, a])), to = lookup.get(JSON.stringify([i + 1, b]))
      const width = value * scale, x1 = from.x + 12, x2 = to.x, y1 = from.y + from.outgoing + width / 2, y2 = to.y + to.incoming + width / 2
      links.push({ id: `${i}:${key}`, from: a, to: b, value, width, path: `M${x1},${y1} C${(x1 + x2) / 2},${y1} ${(x1 + x2) / 2},${y2} ${x2},${y2}` })
      from.outgoing += width; to.incoming += width
    }
  })
  return { nodes, links, height, total }
}
