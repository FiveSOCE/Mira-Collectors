plugins { java }
group = "gg.mira"
version = "0.1.0"
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/"); maven("https://jitpack.io") }
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { exclude(group = "org.bukkit", module = "bukkit") }
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(21) }
tasks.jar { archiveBaseName.set("MiraCollectors") }
