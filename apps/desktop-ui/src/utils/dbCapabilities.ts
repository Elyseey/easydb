import type { DbType } from '@/types'

export interface MetadataCapability {
  schemas: boolean
  schemaCreation: boolean
  schemaManagement: boolean
  schemaAlterCharset: boolean
  tables: boolean
  views: boolean
  procedures: boolean
  functions: boolean
  triggers: boolean
  ddl: boolean
  timeSeries: boolean
}

export interface SqlCapability {
  execute: boolean
  paginatedPreview: boolean
  explain: boolean
}

export interface WorkbenchCapability {
  dataPreview: boolean
  rowEdit: boolean
  tableDesigner: boolean
  tableCreate: boolean
  timeSeriesObjectCreate: boolean
  timeSeriesQuery: boolean
  timeSeriesLoadedDataExport: boolean
  tableRename: boolean
  tableDrop: boolean
  tableTruncate: boolean
  importSql: boolean
  exportData: boolean
  backup: boolean
  restore: boolean
}

export interface TaskFeatureCapability {
  migration: boolean
  sync: boolean
  structureCompare: boolean
}

export interface DiagnosticCapability {
  dataTracker: boolean
  slowQuery: boolean
}

export type DiagnosticFeature = keyof DiagnosticCapability

export interface DbCapabilities {
  metadata: MetadataCapability
  sql: SqlCapability
  workbench: WorkbenchCapability
  tasks: TaskFeatureCapability
  diagnostics: DiagnosticCapability
}

const MYSQL: DbCapabilities = {
  metadata: { schemas: true, schemaCreation: true, schemaManagement: true, schemaAlterCharset: true, tables: true, views: true, procedures: true, functions: true, triggers: true, ddl: true, timeSeries: false },
  sql: { execute: true, paginatedPreview: true, explain: true },
  workbench: { dataPreview: true, rowEdit: true, tableDesigner: true, tableCreate: true, timeSeriesObjectCreate: false, timeSeriesQuery: false, timeSeriesLoadedDataExport: false, tableRename: true, tableDrop: true, tableTruncate: true, importSql: true, exportData: true, backup: true, restore: true },
  tasks: { migration: true, sync: true, structureCompare: true },
  diagnostics: { dataTracker: true, slowQuery: true },
}

const DAMENG: DbCapabilities = {
  metadata: { schemas: true, schemaCreation: true, schemaManagement: true, schemaAlterCharset: false, tables: true, views: true, procedures: true, functions: true, triggers: true, ddl: true, timeSeries: false },
  sql: { execute: true, paginatedPreview: true, explain: false },
  workbench: { dataPreview: true, rowEdit: true, tableDesigner: true, tableCreate: true, timeSeriesObjectCreate: false, timeSeriesQuery: false, timeSeriesLoadedDataExport: false, tableRename: true, tableDrop: true, tableTruncate: true, importSql: true, exportData: true, backup: true, restore: true },
  tasks: { migration: true, sync: true, structureCompare: true },
  diagnostics: { dataTracker: false, slowQuery: false },
}

const STUB: DbCapabilities = {
  metadata: { schemas: false, schemaCreation: false, schemaManagement: false, schemaAlterCharset: false, tables: false, views: false, procedures: false, functions: false, triggers: false, ddl: false, timeSeries: false },
  sql: { execute: false, paginatedPreview: false, explain: false },
  workbench: { dataPreview: false, rowEdit: false, tableDesigner: false, tableCreate: false, timeSeriesObjectCreate: false, timeSeriesQuery: false, timeSeriesLoadedDataExport: false, tableRename: false, tableDrop: false, tableTruncate: false, importSql: false, exportData: false, backup: false, restore: false },
  tasks: { migration: false, sync: false, structureCompare: false },
  diagnostics: { dataTracker: false, slowQuery: false },
}

const CAPABILITIES: Record<DbType, DbCapabilities> = {
  mysql: MYSQL,
  dameng: DAMENG,
  tdengine: {
    metadata: { schemas: true, schemaCreation: false, schemaManagement: false, schemaAlterCharset: false, tables: true, views: false, procedures: false, functions: false, triggers: false, ddl: true, timeSeries: true },
    sql: { execute: true, paginatedPreview: true, explain: false },
    workbench: { dataPreview: true, rowEdit: false, tableDesigner: false, tableCreate: false, timeSeriesObjectCreate: true, timeSeriesQuery: true, timeSeriesLoadedDataExport: true, tableRename: false, tableDrop: false, tableTruncate: false, importSql: false, exportData: false, backup: false, restore: false },
    tasks: { migration: false, sync: false, structureCompare: false },
    diagnostics: { dataTracker: false, slowQuery: false },
  },
  postgresql: STUB,
  oracle: STUB,
  sqlserver: STUB,
  sqlite: STUB,
}

export function getDbCapabilities(dbType: DbType | null): DbCapabilities {
  if (!dbType) return STUB
  return CAPABILITIES[dbType] ?? STUB
}

export function supportsDatabaseDiagnostic(
  dbType: DbType | null,
  feature: DiagnosticFeature,
): boolean {
  return getDbCapabilities(dbType).diagnostics[feature]
}

export function filterConnectionsByDiagnosticCapability<T extends { dbType: DbType }>(
  connections: readonly T[],
  feature: DiagnosticFeature,
): T[] {
  return connections.filter((connection) => supportsDatabaseDiagnostic(connection.dbType, feature))
}
