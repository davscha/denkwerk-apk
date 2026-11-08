package de.davscha.denkwerk

import android.content.Context

/** Versionsnummer der installierten App (kommt aus der Build-Nummer von GitHub Actions). */
object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
