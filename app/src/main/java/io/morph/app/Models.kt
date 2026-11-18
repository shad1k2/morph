package io.morph.app

import io.morph.core.ShellId

/** A peer discovered nearby. Transport discovery is not wired yet. */
data class NearbyDevice(
    val id: String,
    val name: String,
    val model: String,
    val shell: ShellId,
    val distanceMeters: Int,
    val signal: Int,
)

/** What the overlay is currently showing. */
sealed interface TransferUi {
    val device: NearbyDevice
    val fileName: String

    data class Incoming(
        override val device: NearbyDevice,
        override val fileName: String,
        val totalFiles: Int,
        val sizeLabel: String,
    ) : TransferUi

    data class Outgoing(
        override val device: NearbyDevice,
        override val fileName: String,
        val sizeLabel: String,
    ) : TransferUi
}

enum class TransferPhase { PROMPT, RUNNING, DONE }

val demoDevices = listOf(
    NearbyDevice("1", "Pixel 9 Pro", "Pixel 9 Pro · Android 16", ShellId.PIXEL, 2, 4),
    NearbyDevice("2", "Xiaomi 15", "Xiaomi 15 · HyperOS 2", ShellId.HYPEROS, 4, 3),
    NearbyDevice("3", "Galaxy S25", "Galaxy S25 · One UI 7", ShellId.ONE_UI, 6, 2),
)

fun deviceInitial(name: String): String = name.trim().take(1).uppercase()
