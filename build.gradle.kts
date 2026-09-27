plugins {
    kotlin("jvm") version "2.2.10"
    application
}

group = "x86sim"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.formdev:flatlaf-fonts-inter:4.1")
    implementation("com.formdev:flatlaf-fonts-jetbrains-mono:2.304")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("x86sim.MainKt")
    applicationName = "x86learn"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("x86learn.properties") { expand("version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("x86sim.noprefs", "true") // tests never read or write the user's saved settings
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
