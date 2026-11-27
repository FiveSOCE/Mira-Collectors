import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "gg.mira"
version = "0.2.4-1.18.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

val miraCoreVersion = "0.6.1-1.18.2"
val miraCoreSha256 = "8abf9f26ea889ffe1648c6f35fcb7b20eef4decd870e8385fb1603120f363b95"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore_1.18.2.jar").asFile

val miraFactionsVersion = "0.2.33-1.18.2"
val miraFactionsSha256 = "150ffe9a488021d04177bad37fa27076203bcce490d024b05af06f4bb1728a81"
val miraFactionsJar = layout.projectDirectory.file("libs/MiraFactions_1.18.2.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
}

fun downloadVerified(url: String, target: File, expected: String) {
    if (target.exists() && sha256(target) == expected) return
    target.parentFile.mkdirs()
    URI(url).toURL().openStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    check(sha256(target) == expected) { "Downloaded dependency failed SHA-256 verification: " + target.name }
}

val downloadMiraCore by tasks.registering {
    doLast {
        downloadVerified(
            "https://github.com/FiveSOCE/Mira-core/releases/download/dev-1.18.2/MiraCore_1.18.2.jar",
            miraCoreJar,
            miraCoreSha256
        )
    }
}

val downloadMiraFactions by tasks.registering {
    doLast {
        downloadVerified(
            "https://github.com/FiveSOCE/Mira-Factions/releases/download/dev-1.18.2/MiraFactions_1.18.2.jar",
            miraFactionsJar,
            miraFactionsSha256
        )
    }
}

val paperApiVersion = providers.gradleProperty("paperApiVersion").orElse("1.18.2-R0.1-SNAPSHOT")
val compileJavaVersion = providers.gradleProperty("compileJavaVersion").map(String::toInt).orElse(17)
val bytecodeJavaVersion = providers.gradleProperty("bytecodeJavaVersion").map(String::toInt).orElse(17)

dependencies {
    compileOnly("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { exclude(group = "org.bukkit", module = "bukkit") }
    compileOnly(files(miraCoreJar))
    compileOnly(files(miraFactionsJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(compileJavaVersion.get())) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraCore, downloadMiraFactions)
    options.encoding = "UTF-8"
    options.release.set(bytecodeJavaVersion.get())
}

tasks.jar { archiveFileName.set("MiraCollectors_1.18.2.jar") }

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
