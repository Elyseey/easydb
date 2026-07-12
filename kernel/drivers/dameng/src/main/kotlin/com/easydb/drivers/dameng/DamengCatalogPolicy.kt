package com.easydb.drivers.dameng

internal object DamengCatalogPolicy {
    fun mergeNames(target: MutableSet<String>, loader: () -> Iterable<String?>) {
        val names = try {
            loader()
        } catch (_: Exception) {
            return
        }
        names.mapNotNull { it?.takeIf(String::isNotBlank) }.forEach(target::add)
    }

    fun <T> visibleFirst(visibleLoader: () -> List<T>, fallbackLoader: () -> List<T>): List<T> {
        val visible = try {
            visibleLoader()
        } catch (_: Exception) {
            null
        }
        if (!visible.isNullOrEmpty()) return visible
        return try {
            fallbackLoader()
        } catch (_: Exception) {
            visible.orEmpty()
        }
    }
}
