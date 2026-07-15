export const MAX_QUERY_TAB_TITLE_LENGTH = 40

export function normalizeQueryTabTitle(value: string): string | null {
  const normalized = value.trim().replace(/\s+/g, ' ')
  if (!normalized) return null
  return normalized.slice(0, MAX_QUERY_TAB_TITLE_LENGTH)
}

export function resolveWorkbenchTabKey(target: EventTarget | null): string | null {
  if (!(target instanceof Element)) return null
  return target.closest<HTMLElement>('.ant-tabs-tab')?.dataset.nodeKey ?? null
}
