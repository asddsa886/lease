package com.atguigu.lease.web.ops.service.log;

import com.atguigu.lease.web.ops.config.OpsHistoryProperties;
import com.atguigu.lease.web.ops.config.OpsLogScanProperties;
import com.atguigu.lease.web.ops.dto.OpsLogScanReport;
import com.atguigu.lease.web.ops.service.history.OpsLogHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpsLogScanServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAnalyzeTailOfLargeLogFileAndPersistHistory() throws Exception {
        Path logDir = Files.createDirectory(tempDir.resolve("logs"));
        Path historyDir = tempDir.resolve("history-store");
        Path logFile = logDir.resolve("app.log");

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 500; index++) {
            builder.append("2026-04-22T10:00:00.000+08:00 INFO 1000 --- [main] demo.Logger : filler line ")
                    .append(index)
                    .append(System.lineSeparator());
        }
        builder.append("2026-04-22T10:05:00.000+08:00 ERROR 1000 --- [main] com.mysql.cj.jdbc.ConnectionImpl : Communications link failure")
                .append(System.lineSeparator());
        Files.writeString(logFile, builder.toString());

        OpsLogScanProperties scanProperties = new OpsLogScanProperties();
        scanProperties.setDirectories(List.of(logDir.toString()));
        scanProperties.setLookbackHours(24);
        scanProperties.setMaxFiles(5);
        scanProperties.setMaxBytesPerFile(4096);

        OpsHistoryProperties historyProperties = new OpsHistoryProperties();
        historyProperties.setDir(historyDir.toString());
        historyProperties.setMaxScans(50);

        OpsLogScanService scanService = new OpsLogScanService(
                scanProperties,
                new OpsLogHistoryService(new ObjectMapper(), historyProperties),
                new OpsLogAnalyzer()
        );

        OpsLogScanReport report = scanService.runScan("MANUAL");

        assertThat(report.getFileCount()).isEqualTo(1);
        assertThat(report.getIssueGroupCount()).isGreaterThanOrEqualTo(1);
        assertThat(report.getTopFindings()).extracting("issueType")
                .contains("DEPENDENCY_CONNECTION_FAILURE");
        assertThat(scanService.getHistoryDetail(report.getScanTaskId()).getScanId())
                .isEqualTo(report.getScanTaskId());
    }
}
