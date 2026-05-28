import type { DbType } from '@/types'

export interface MetadataCapability {
  schemas: boolean
  schemaCreation: boolean
  schemaManagement: boolean
  tables: boolean
  views: boolean
  procedures: boolean
  functions: boolean
  triggers: boolean
  ddl: boolean
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

export interface DbCapabilities {
  metadata: MetadataCapability
  sql: SqlCapability
  workbench: WorkbenchCapability
  tasks: TaskFeatureCapability
  diagnostics: DiagnosticCapability
}

const MYSQL: DbCapabilities = {
  metadata: { schemas: true, schemaCreation: true, schemaManagement: true, tables: true, views: true, procedures: true, functions: true, triggers: true, ddl: true },
  sql: { execute: true, paginatedPreview: true, explain: true },
  workbench: { dataPreview: true, rowEdit: true, tableDesigner: true, importSql: true, exportData: true, backup: true, restore: true },
  tasks: { migration: true, sync: true, structureCompare: true },
  diagnostics: { dataTracker: true, slowQuery: true },
}

const DAMENG: DbCapabilities = {
  metadata: { schemas: true, schemaCreation: true, schemaManagement: false, tables: true, views: true, procedures: false, functions: false, triggers: false, ddl: true },
  sql: { execute: true, paginatedPreview: true, explain: false },
  workbench: { dataPreview: true, rowEdit: false, tableDesigner: false, importSql: false, exportData: false, backup: false, restore: false },
  tasks: { migration: false, sync: false, structureCompare: false },
  diagnostics: { dataTracker: false, slowQuery: false },
}

const STUB: DbCapabilities = {
  metadata: { schemas: false, schemaCreation: false, schemaManagement: false, tables: false, views: false, procedures: false, functions: false, triggers: false, ddl: false },
  sql: { execute: false, paginatedPreview: false, explain: false },
  workbench: { dataPreview: false, rowEdit: false, tableDesigner: false, importSql: false, exportData: false, backup: false, restore: false },
  tasks: { migration: false, sync: false, structureCompare: false },
  diagnostics: { dataTracker: false, slowQuery: false },
}

const CAPABILITIES: Record<DbType, DbCapabilities> = {
  mysql: MYSQL,
  dameng: DAMENG,
  postgresql: STUB,
  oracle: STUB,
  sqlserver: STUB,
  sqlite: STUB,
}

export function getDbCapabilities(dbType: DbType | null): DbCapabilities {
  if (!dbType) return MYSQL
  return CAPABILITIES[dbType] ?? MYSQL
}
