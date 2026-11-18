package io.morph.app

import android.content.Context
import io.morph.core.ShellId

/**
 * Remembers which shell pack the user previewed, so the system overlay uses the
 * same style as the app. Defaults to the detected shell.
 */
object ThemePrefs {

    private const val FILE = "morph_prefs"
    private const val KEY_SHELL = "shell_override"

    fun selected(context: Context): ShellId {
        val code = context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_SHELL, -1)
        return if (code >= 0) ShellId.from(code) else ShellDetector.detect()
    }

    fun save(context: Context, shell: ShellId) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SHELL, shell.code)
            .apply()
    }
}
