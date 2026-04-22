package com.atguigu.lease.web.ops.service.log;

import com.atguigu.lease.web.ops.config.OpsLogScanProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OpsLogStartupRunner implements ApplicationRunner {

    private final OpsLogScanProperties properties;
    private final OpsLogScanService logScanService;

    public OpsLogStartupRunner(OpsLogScanProperties properties, OpsLogScanService logScanService) {
        this.properties = properties;
        this.logScanService = logScanService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isStartupEnabled()) {
            return;
        }
        try {
            log.info("Ops log startup scan begin");
            logScanService.runScan("STARTUP");
            log.info("Ops log startup scan completed");
        } catch (Exception e) {
            log.warn("Ops log startup scan failed", e);
        }
    }
}
