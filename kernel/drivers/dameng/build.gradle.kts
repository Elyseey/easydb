plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":common"))
    implementation("com.dameng:DmJdbcDriver11:8.1.5.45")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
