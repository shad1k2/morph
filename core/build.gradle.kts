plugins {
    kotlin("jvm")
}

group = "io.morph"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}
