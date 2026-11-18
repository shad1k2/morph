package io.morph.app

import android.os.Build
import io.morph.core.ShellId

/** Detects the local shell; see docs/THEMES.md. */
object ShellDetector {
    fun detect(): ShellId {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            brand.contains("nothing") || prop("ro.nothing.build.version") != null -> ShellId.NOTHING_OS
            manufacturer.contains("xiaomi") || prop("ro.miui.ui.version.name") != null -> ShellId.HYPEROS
            manufacturer.contains("samsung") -> ShellId.ONE_UI
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") -> ShellId.COLOROS
            manufacturer.contains("vivo") -> ShellId.ORIGINOS
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> ShellId.HARMONYOS
            manufacturer == "google" -> ShellId.PIXEL
            else -> ShellId.UNKNOWN
        }
    }

    private fun prop(name: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val value = clazz.getMethod("get", String::class.java).invoke(null, name) as? String
        value?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }
}
