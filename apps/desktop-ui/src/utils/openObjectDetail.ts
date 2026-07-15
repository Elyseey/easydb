export type OpenObjectDetailType = 'table' | 'view' | 'procedure' | 'function' | 'trigger'

export type OpenObjectDetailRequest = {
  connectionId: string
  database: string
  name: string
  objectType: OpenObjectDetailType
}

const OPENABLE_OBJECT_TYPES = new Set<OpenObjectDetailType>([
  'table',
  'view',
  'procedure',
  'function',
  'trigger',
])

export function buildOpenObjectDetailRequest(
  connectionId: string,
  database: string,
  object: { name: string; type: string },
): OpenObjectDetailRequest | null {
  if (!connectionId || !OPENABLE_OBJECT_TYPES.has(object.type as OpenObjectDetailType)) return null
  return {
    connectionId,
    database,
    name: object.name,
    objectType: object.type as OpenObjectDetailType,
  }
}
