plugins {
    kotlin("jvm")
}

val damengIntegrationTestSourceSet = sourceSets.create("damengIntegrationTest")

dependencies {
    implementation(project(":common"))
    implementation("com.dameng:DmJdbcDriver11:8.1.5.45")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.10")
}

configurations[damengIntegrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[damengIntegrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

damengIntegrationTestSourceSet.compileClasspath +=
    sourceSets.main.get().output + sourceSets.test.get().output
damengIntegrationTestSourceSet.runtimeClasspath +=
    sourceSets.main.get().output + sourceSets.test.get().output

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("damengIntegrationTest") {
    description = "Runs opt-in integration tests against a dedicated Dameng instance."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.testClasses)
    testClassesDirs = damengIntegrationTestSourceSet.output.classesDirs
    classpath = damengIntegrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    onlyIf("EASYDB_DM_INTEGRATION_TEST must be true") {
        System.getenv("EASYDB_DM_INTEGRATION_TEST").equals("true", ignoreCase = true)
    }
}

kotlin {
    jvmToolchain(21)
}
