/** Validate the container before inflation; paths are never extracted to the filesystem. */
export function inspectExchangeZip(data: Uint8Array): Map<string, { bytes: number; crc: number }> {
  const require: (v: unknown) => asserts v = v => { if (!v) throw new Error('Invalid library ZIP container.') }
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  const u16 = (at: number) => { require(at >= 0 && at + 2 <= data.length); return view.getUint16(at, true) }
  const u32 = (at: number) => { require(at >= 0 && at + 4 <= data.length); return view.getUint32(at, true) }
  const length = (at: number) => { const n = u32(at); require(n <= 0x7fffffff); return n }
  require(data.length >= 22 && data.length <= 32 * 1024 * 1024)
  const end = data.length - 22
  require(u32(end) === 0x06054b50 && u16(end + 20) === 0 && u16(end + 4) === 0 && u16(end + 6) === 0 && u16(end + 8) === u16(end + 10))
  const count = u16(end + 10), centralStart = length(end + 16)
  require(count >= 2 && count <= 512 && centralStart + length(end + 12) === end)
  let cursor = centralStart, expanded = 0
  const descriptors = new Map<string, { bytes: number; crc: number }>(), regions: [number, number][] = []
  for (let i = 0; i < count; i++) {
    require(cursor + 46 <= end && u32(cursor) === 0x02014b50)
    const flags = u16(cursor + 8), method = u16(cursor + 10), crc = u32(cursor + 16), compressed = length(cursor + 20), uncompressed = length(cursor + 24)
    require((flags & ~(0x800 | 8 | 6)) === 0 && (method === 0 || method === 8) && !(method === 0 && (flags & 8)))
    expanded += uncompressed; require(expanded <= 64 * 1024 * 1024)
    const nameLength = u16(cursor + 28), next = cursor + 46 + nameLength + u16(cursor + 30) + u16(cursor + 32), local = length(cursor + 42)
    require(next <= end && u16(cursor + 34) === 0 && ((u32(cursor + 38) >>> 16) & 0xf000) !== 0xa000)
    const nameBytes = data.subarray(cursor + 46, cursor + 46 + nameLength), name = new TextDecoder('utf-8', { fatal: true }).decode(nameBytes)
    require(/^(manifest\.json|documents\/[a-f0-9]{64}\.json|assets\/[a-f0-9]{64})$/.test(name) && !descriptors.has(name))
    const limit = name === 'manifest.json' ? 1024 * 1024 : name.startsWith('documents/') ? 8 * 1024 * 1024 : 32 * 1024 * 1024
    require(uncompressed <= limit && local + 30 <= centralStart && u32(local) === 0x04034b50 && u16(local + 6) === flags && u16(local + 8) === method && u16(local + 26) === nameLength)
    require(nameBytes.every((b, index) => data[local + 30 + index] === b))
    let regionEnd = local + 30 + nameLength + u16(local + 28) + compressed
    require(regionEnd <= centralStart)
    if (flags & 8) {
      if (u32(regionEnd) === 0x08074b50) regionEnd += 4
      require(regionEnd + 12 <= centralStart && u32(regionEnd) === crc && length(regionEnd + 4) === compressed && length(regionEnd + 8) === uncompressed); regionEnd += 12
    } else require(u32(local + 14) === crc && length(local + 18) === compressed && length(local + 22) === uncompressed)
    descriptors.set(name, { bytes: uncompressed, crc }); regions.push([local, regionEnd]); cursor = next
  }
  require(cursor === end)
  let localCursor = 0
  for (const [start, stop] of regions.sort((a, b) => a[0] - b[0])) { require(start === localCursor && stop > start); localCursor = stop }
  require(localCursor === centralStart)
  return descriptors
}

const crcTable = Uint32Array.from({ length: 256 }, (_, index) => { let value = index; for (let bit = 0; bit < 8; bit++) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1; return value >>> 0 })
export function exchangeCrc32(data: Uint8Array): number { let crc = 0xffffffff; for (const b of data) crc = crcTable[(crc ^ b) & 255] ^ (crc >>> 8); return (crc ^ 0xffffffff) >>> 0 }
