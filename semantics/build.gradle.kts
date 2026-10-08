// Pure Kotlin port of Ansible's own rules. No IntelliJ classes allowed here (enforced by ArchitectureTest in :plugin).
import org.gradle.api.tasks.PathSensitivity

plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // The IDE ships the Kotlin stdlib; keep it off the plugin's runtime classpath (see :plugin).
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Test-only: builds YValue trees from YAML text (SnakeYAML is YAML 1.1, like PyYAML). Never used at runtime.
    testImplementation("org.yaml:snakeyaml:2.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // YamlTextGoldenTest reads the shared PyYAML goldens from :plugin's test data; declare them so a golden-only
    // change re-runs the test instead of reusing a cached result.
    inputs.dir("../plugin/src/test/testData/yaml/golden")
        .withPropertyName("yamlGoldens")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Opt-in corpus tests (ANSIBLE_INFRA_REPO, ANSIBLE_INVENTORY_BIN) must re-run when these variables change.
    inputs.property("ansibleInfraRepo", providers.environmentVariable("ANSIBLE_INFRA_REPO").orElse(""))
    inputs.property("ansibleInventoryBin", providers.environmentVariable("ANSIBLE_INVENTORY_BIN").orElse(""))
    // Opt-in PrivateKeyToolOracleTest: a directory for throwaway keys made with openssl and ssh-keygen.
    inputs.property("ansibilityKeyTools", providers.environmentVariable("ANSIBILITY_KEY_TOOLS").orElse(""))
    // RenderOracleTest lists this many mismatches per case (default 25).
    providers.gradleProperty("oracleMismatches").orNull?.let { systemProperty("oracle.mismatches", it) }
}
