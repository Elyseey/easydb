plugins {
    kotlin("jvm")
}

val tdengineIntegrationTestSourceSet = sourceSets.create("tdengineIntegrationTest")

dependencies {
    implementation(project(":common"))
    implementation("com.taosdata.jdbc:taos-jdbcdriver:3.9.0")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.10")
}

configurations[tdengineIntegrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[tdengineIntegrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tdengineIntegrationTestSourceSet.compileClasspath +=
    sourceSets.main.get().output + sourceSets.test.get().output
tdengineIntegrationTestSourceSet.runtimeClasspath +=
    sourceSets.main.get().output + sourceSets.test.get().output

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("tdengineIntegrationTest") {
    description = "Runs opt-in integration tests against a dedicated TDengine instance."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.testClasses)
    testClassesDirs = tdengineIntegrationTestSourceSet.output.classesDirs
    classpath = tdengineIntegrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    onlyIf("EASYDB_TDENGINE_INTEGRATION_TEST must be true") {
        System.getenv("EASYDB_TDENGINE_INTEGRATION_TEST").equals("true", ignoreCase = true)
    }
}

kotlin {
    jvmToolchain(21)
}
