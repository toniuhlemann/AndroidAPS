package app.aaps.implementation.maintenance

import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.kotlin.whenever
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MaintenanceImplTest : TestBaseWithProfile() {

    @Mock lateinit var nsClient: NsClient
    @Mock lateinit var loggerUtils: LoggerUtils
    @Mock lateinit var fileListProvider: FileListProvider
    @Mock lateinit var cloudStorageManager: CloudStorageManager

    private lateinit var sut: MaintenanceImpl

    @BeforeEach
    fun mock() {
        sut = MaintenanceImpl(context, rh, preferences, nsClient, aapsLogger, config, fileListProvider, loggerUtils, cloudStorageManager)
        whenever(loggerUtils.suffix).thenReturn(".log.zip")
        whenever(loggerUtils.logDirectory).thenReturn("src/androidHostTest/assets/logger")
    }

    @Test fun logFilesTest() {
        var logs = sut.getLogFiles(2)
        assertThat(logs.map { it.name }).containsExactly(
            "AndroidAPS.log",
            "AndroidAPS.2018-01-03_01-01-00.1.zip",
        ).inOrder()
        logs = sut.getLogFiles(10)
        assertThat(logs).hasSize(4)
    }

    /**
     * Fork: logback makes one log archive per hour and keeps 720 of them (30 days). The periodic trim
     * must not cut that short. With the upstream 30 files only about 29 hours of logs were left.
     */
    @Test fun periodicTrimKeepsThirtyDaysOfHourlyLogs(@TempDir dir: File) {
        val logback = File("../app/src/main/assets/logback.xml").readText()
        assertThat(logback).contains("AndroidAPS._%d{yyyy-MM-dd_HH}.log.zip")
        assertThat(logback).contains("<maxHistory>720</maxHistory>")

        val hour = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH")
        val start = LocalDateTime.of(2026, 9, 1, 0, 0)
        val archives = (0L until 800L).map { "AndroidAPS._" + start.plusHours(it).format(hour) + ".log.zip" }
        archives.forEach { File(dir, it).createNewFile() }
        File(dir, "AndroidAPS.log").createNewFile()
        File(dir, "fuse_state_history.jsonl").createNewFile()
        whenever(loggerUtils.logDirectory).thenReturn(dir.path)

        sut.deleteLogs(PeriodicMaintenance.KEEP_LOG_FILES)

        val kept = dir.list { _, name -> name.endsWith(".log.zip") }!!.toList()
        assertThat(kept.size).isAtLeast(29 * 24)
        assertThat(kept.size).isAtMost(30 * 24)
        assertThat(kept).containsAtLeastElementsIn(archives.takeLast(29 * 24))
        assertThat(File(dir, "AndroidAPS.log").exists()).isTrue()
        assertThat(File(dir, "fuse_state_history.jsonl").exists()).isTrue()
    }
}
