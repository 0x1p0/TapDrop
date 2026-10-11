plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Android ships org.json in the platform; the JVM integration test needs a real copy.
    compileOnly("org.json:json:20260814")
    testImplementation("org.json:json:20260814")
}

// Runs the phone-side protocol code against a live Mac app:
//   gradle :core:integration -Phost=127.0.0.1 -Pport=47913
tasks.register<JavaExec>("integration") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.tapdrop.core.IntegrationKt")
    args(
        (project.findProperty("host") ?: "127.0.0.1").toString(),
        (project.findProperty("port") ?: "47913").toString(),
        (project.findProperty("outDir") ?: layout.buildDirectory.dir("integration").get().asFile.path).toString(),
    )
}

tasks.register<JavaExec>("watchCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.tapdrop.core.WatchCheckKt")
    args(
        (project.findProperty("host") ?: "127.0.0.1").toString(),
        (project.findProperty("port") ?: "47913").toString(),
        (project.findProperty("secs") ?: "30").toString(),
    )
}

tasks.register<JavaExec>("otpCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.tapdrop.core.OtpCheckKt")
}

tasks.register<JavaExec>("timeCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.tapdrop.core.TimeCheckKt")
}

tasks.register<JavaExec>("ocrSend") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.tapdrop.core.OcrSendKt")
    args(
        (project.findProperty("host") ?: "127.0.0.1").toString(),
        (project.findProperty("port") ?: "47920").toString(),
        (project.findProperty("img") ?: "").toString(),
        (project.findProperty("n") ?: "5").toString(),
        (project.findProperty("gap") ?: "300").toString(),
    )
}
