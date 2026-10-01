rootProject.name = "ansibility"

plugins {
    // Provisions the Java 25 toolchain that IntelliJ Platform 262 requires (decision D3).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":semantics", ":plugin")
