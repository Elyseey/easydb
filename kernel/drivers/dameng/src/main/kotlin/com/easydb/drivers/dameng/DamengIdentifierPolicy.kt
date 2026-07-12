package com.easydb.drivers.dameng

import java.util.Locale

internal object DamengIdentifierPolicy {
    fun catalogName(value: String): String = value

    fun newUnquotedName(value: String): String = value.trim().uppercase(Locale.ROOT)
}
