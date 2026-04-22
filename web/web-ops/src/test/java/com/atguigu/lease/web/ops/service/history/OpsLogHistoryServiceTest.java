package com.atguigu.lease.web.ops.service.history;

import com.atguigu.lease.web.ops.config.OpsHistoryProperties;
import com.atguigu.lease.web.ops.dto.OpsLogHistorySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpsLogHistoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSaveCurrentAndKeepLatestEffectiveHistory() {
        OpsHistoryProperties properties = new OpsHistoryProperties();
        properties.setDir(tempDir.toString());
        properties.setMaxScans(2);
        OpsLogHistoryService historyService = new OpsLogHistoryService(new ObjectMapper(), properties);

        OpsLogHistorySnapshot current = snapshot(1001L, "fingerprint-current");
        historyService.saveCurrent(current);

        assertThat(historyService.loadCurrent()).isPresent();
        assertThat(historyService.loadCurrent().get().getScanId()).isEqualTo(1001L);

        OpsLogHistorySnapshot first = snapshot(2001L, "fingerprint-a");
        OpsLogHistorySnapshot duplicate = snapshot(2002L, "fingerprint-a");
        OpsLogHistorySnapshot second = snapshot(2003L, "fingerprint-b");
        OpsLogHistorySnapshot third = snapshot(2004L, "fingerprint-c");

        assertThat(historyService.saveHistory(first, true)).isTrue();
        assertThat(historyService.saveHistory(duplicate, false)).isFalse();
        assertThat(historyService.saveHistory(second, false)).isTrue();
        assertThat(historyService.saveHistory(third, true)).isTrue();

        List<OpsLogHistorySnapshot> snapshots = historyService.listHistorySnapshots(10);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).extracting(OpsLogHistorySnapshot::getScanId)
                .containsExactly(2004L, 2003L);
    }

    private OpsLogHistorySnapshot snapshot(Long scanId, String fingerprint) {
        Date now = new Date();
        return OpsLogHistorySnapshot.builder()
                .scanId(scanId)
                .status("SUCCESS")
                .triggerType("MANUAL")
                .directories(List.of("./logs"))
                .scannedFiles(List.of("app.log"))
                .lookbackHours(72L)
                .maxFiles(20)
                .maxBytesPerFile(262144L)
                .fileCount(1)
                .issueGroupCount(1)
                .summary("test")
                .categoryCounts(java.util.Map.of("APP", 1L, "INFRA", 0L, "PERFORMANCE_DB", 0L))
                .startedAt(now)
                .finishedAt(now)
                .analysisFingerprint(fingerprint)
                .findings(List.of())
                .build();
    }
}
