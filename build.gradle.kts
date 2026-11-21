plugins { java }
group = "dev.trueseasons"
version = "1.0.0"
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
    compileOnly("it.unimi.dsi:fastutil:8.5.18")
    testRuntimeOnly("it.unimi.dsi:fastutil:8.5.18")
    testRuntimeOnly("io.netty:netty-buffer:4.2.16.Final")
    testRuntimeOnly("io.papermc.paper:paper-api:26.3.build.177-stable")
    testImplementation("de.bluecolored:bluemap-api:2.7.7")
    testImplementation("de.bluecolored:bluemap-core:5.29")
    testImplementation("de.bluecolored:bluemap-common:5.29")
}
configurations.testCompileOnly { extendsFrom(configurations.compileOnly.get()) }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.processResources {
    inputs.property("version", project.version)
    doFirst {
        val output = destinationDir.canonicalFile
        check(output.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        project.delete(output)
    }
}
tasks.register<JavaExec>("coreTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.trueseasons.CoreTests")
}
tasks.check { dependsOn("coreTest") }
// Regression suites are standalone Java entry points, registered with check below.
tasks.test { enabled = false }
listOf(
    "terrainTargetTest" to "dev.trueseasons.state.TerrainTargetTests",
    "migrationTest" to "dev.trueseasons.migration.MigrationBookkeepingTests",
    "regionReadinessTest" to "dev.trueseasons.migration.RegionReadinessTests",
    "regionProgressTest" to "dev.trueseasons.migration.RegionProgressTests",
    "chunkLoadLeaseTest" to "dev.trueseasons.migration.ChunkLoadLeaseTests",
    "regionOrderTest" to "dev.trueseasons.migration.RegionOrderTests",
    "indexHydrationTest" to "dev.trueseasons.migration.IndexHydrationBudgetTests",
    "recentTickHeadroomTest" to "dev.trueseasons.migration.RecentTickHeadroomTests",
    "chunkCoordinateHashTest" to "dev.trueseasons.migration.ChunkCoordinateHashTests",
    "weatherDefaultsTest" to "dev.trueseasons.config.WeatherDefaultsTests",
    "climateTest" to "dev.trueseasons.climate.SeasonClimateTests",
    "groundCoverTest" to "dev.trueseasons.snow.WinterGroundCoverTests",
    "snowColumnsCodecTest" to "dev.trueseasons.snow.SnowColumnsCodecTests",
    "groundCoverCodecTest" to "dev.trueseasons.snow.GroundCoverCodecTests",
    "chunkPacketEditsTest" to "dev.trueseasons.presentation.ChunkPacketEditsTests",
    "snowOwnershipSnapshotsTest" to "dev.trueseasons.snow.SnowOwnershipSnapshotsTests",
    "snowPreviewPlanningTest" to "dev.trueseasons.snow.SnowPreviewPlanningTests",
    "snowmeltTest" to "dev.trueseasons.snow.SnowmeltTests",
    "previewSchedulingTest" to "dev.trueseasons.snow.PreviewSchedulingTests",
    "localSnowLifecycleTest" to "dev.trueseasons.snow.LocalSnowLifecycleTests",
    "snowPresentationBudgetTest" to "dev.trueseasons.presentation.SnowPresentationBudgetTests",
    "winterStormRevealTest" to "dev.trueseasons.weather.WinterStormRevealTests",
    "localWinterSnowTest" to "dev.trueseasons.snow.LocalWinterSnowTests",
    "generatedSnapshotTest" to "dev.trueseasons.migration.GeneratedChunkSnapshotTests",
    "winterExemptChunkTest" to "dev.trueseasons.migration.WinterExemptChunkTests",
    "winterCoverageTest" to "dev.trueseasons.migration.WinterCoveragePolicyTests",
    "commandPresentationTest" to "dev.trueseasons.command.CommandPresentationTests",
    "seasonAnnouncementTest" to "dev.trueseasons.notification.SeasonAnnouncementTests",
    "migrationProgressNotificationTest" to "dev.trueseasons.notification.MigrationProgressNotificationTests",
    "snowReplenishmentTest" to "dev.trueseasons.snow.SnowReplenishmentTests",
    "snowBreakCooldownTest" to "dev.trueseasons.snow.SnowBreakCooldownTests",
    "snowCompletionTest" to "dev.trueseasons.SnowCompletionTests",
    "snowMigrationIntentTest" to "dev.trueseasons.snow.SnowMigrationIntentTests",
    "blueMapReliabilityTest" to "dev.trueseasons.integration.BlueMapReliabilityTests",
    "blueMapRegionTest" to "dev.trueseasons.integration.BlueMapRegionTests",
    "blueMapPurgeTest" to "dev.trueseasons.integration.BlueMapPurgeTests",
    "blueMapRecoveryTest" to "dev.trueseasons.integration.BlueMapRecoveryTests",
    "blueMapReceiptTest" to "dev.trueseasons.integration.BlueMapReceiptTests",
    "blueMapJournalTest" to "dev.trueseasons.integration.BlueMapJournalTests",
    "blueMapSeasonalColorsTest" to "dev.trueseasons.integration.BlueMapSeasonalColorsTests",
    "blueMapVisualQueueTest" to "dev.trueseasons.integration.BlueMapVisualQueueTests",
    "autumnTreeColorsTest" to "dev.trueseasons.visual.AutumnTreeColorsTests",
    "seasonColorTransitionTest" to "dev.trueseasons.visual.SeasonColorTransitionTests"
).forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(entryPoint)
    }
    tasks.check { dependsOn(taskName) }
}
