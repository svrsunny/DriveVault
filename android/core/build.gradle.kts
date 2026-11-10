plugins { id("org.jetbrains.kotlin.jvm") }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")   // Argon2id (Argon2BytesGenerator)
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
}
tasks.test { useJUnitPlatform(); testLogging { showStandardStreams = true; events("passed", "failed") } ; systemProperty("dv.testdir", System.getProperty("dv.testdir") ?: "") }
