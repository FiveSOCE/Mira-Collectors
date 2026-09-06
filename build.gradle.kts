import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "gg.mira"
version = "0.2.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

val miraCoreVersion = "0.5.2"
val miraCoreSha256 = "857611b2951a7a026ac7a9ec734e37f7764e33d84f05dec97a6736861d3af170"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore-$miraCoreVersion.jar").asFile

val miraFactionsVersion = "0.2.21"
val miraFactionsSha256 = "c640c8b868b7dcdafb2174614669d8e16deba8d0789e3d6862f8bd3c45c1114e"
val miraFactionsJar = layout.projectDirectory.file("libs/MiraFactions-$miraFactionsVersion.jar").asFile

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
            "https://github.com/FiveSOCE/Mira-core/releases/download/v$miraCoreVersion/MiraCore-$miraCoreVersion.jar",
            miraCoreJar,
            miraCoreSha256
        )
    }
}

val downloadMiraFactions by tasks.registering {
    doLast {
        downloadVerified(
            "https://github.com/FiveSOCE/Mira-Factions/releases/download/v$miraFactionsVersion/MiraFactions-$miraFactionsVersion.jar",
            miraFactionsJar,
            miraFactionsSha256
        )
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { exclude(group = "org.bukkit", module = "bukkit") }
    compileOnly(files(miraCoreJar))
    compileOnly(files(miraFactionsJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraCore, downloadMiraFactions)
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar { archiveFileName.set("MiraCollectors-${project.version}.jar") }

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
