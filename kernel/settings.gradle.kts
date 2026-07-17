rootProject.name = "easydb-kernel"

include(
    "common",
    "api",
    "drivers:mysql",
    "drivers:dameng",
    "metadata",
    "dialect",
    "compare-engine",
    "sync-engine",
    "migration-engine",
    "tunnel",
    "task-center",
    "backup-engine",
    "launcher"
)
