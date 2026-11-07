plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    // Local Compose host-rendering (design verification lane): renders
    // composables to PNGs on the JVM, no device/emulator involved.
    id("app.cash.paparazzi") version "2.0.0-alpha05.1" apply false
}
