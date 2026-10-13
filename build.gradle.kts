plugins { java }
group = "dev.trueseasons"
version = "0.1.4"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.bluecolored.de/releases/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.177-stable")
    compileOnly("de.bluecolored:bluemap-api:2.7.7")
    compileOnly("io.netty:netty-transport:4.2.16.Final")
    compileOnly("io.netty:netty-buffer:4.2.16.Final")
    testRuntimeOnly("io.papermc.paper:paper-api:26.3.build.177-stable")
}
configurations.testCompileOnly { extendsFrom(configurations.compileOnly.get()) }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.processResources { inputs.property("version", project.version) }
tasks.register<JavaExec>("coreTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.trueseasons.CoreTests")
}
tasks.check { dependsOn("coreTest") }
listOf(
    "migrationTest" to "dev.trueseasons.migration.MigrationBookkeepingTests",
    "generatedSnapshotTest" to "dev.trueseasons.migration.GeneratedChunkSnapshotTests",
    "winterCoverageTest" to "dev.trueseasons.migration.WinterCoveragePolicyTests",
    "commandPresentationTest" to "dev.trueseasons.command.CommandPresentationTests",
    "snowReplenishmentTest" to "dev.trueseasons.snow.SnowReplenishmentTests",
    "snowCompletionTest" to "dev.trueseasons.SnowCompletionTests",
    "blueMapReliabilityTest" to "dev.trueseasons.integration.BlueMapReliabilityTests",
    "blueMapRecoveryTest" to "dev.trueseasons.integration.BlueMapRecoveryTests",
    "blueMapJournalTest" to "dev.trueseasons.integration.BlueMapJournalTests"
).forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(entryPoint)
    }
    tasks.check { dependsOn(taskName) }
}
