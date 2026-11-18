package io.morph.app

/** Coarse phase shared by the in-app card and the system overlay. */
enum class TransferPhase { PROMPT, RUNNING, DONE }

fun deviceInitial(name: String): String = name.trim().take(1).uppercase()

/** Short badge for the file row: extension or a generic type. */
fun fileBadge(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return "FILE"
    val ext = name.substring(dot + 1).take(4).uppercase()
    return ext.ifBlank { "FILE" }
}
