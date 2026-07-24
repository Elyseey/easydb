import type { TaskInfo, TimeSeriesCsvPreview } from '@/types'

/** Sample conversion failures continue into the background task and error receipt. */
export function canStartTimeSeriesCsvImport(preview: TimeSeriesCsvPreview | null): boolean {
  return !!preview && preview.blockingErrors.length === 0
}

/** Partial commits and one-time child creation must refresh even on failed/cancelled tasks. */
export function shouldRefreshAfterCsvImport(task: TaskInfo): boolean {
  return (task.successCount ?? 0) > 0 || task.payload?.createdChildTable === 'true'
}
