package com.atguigu.lease.web.ops.service.log;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpsLogAnalyzerTest {

    private final OpsLogAnalyzer analyzer = new OpsLogAnalyzer();

    @Test
    void shouldDetectAppInfraAndPerformanceIssues() {
        String log = """
                2026-04-18T01:58:18.147+08:00 ERROR 14992 --- [           main] o.s.b.SpringApplication                  : Application run failed
                org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'fileUploadController'
                Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException: No qualifying bean of type 'io.minio.MinioClient'
                2026-04-18T02:06:22.473+08:00 INFO 13860 --- [nio-8081-exec-6] c.a.l.common.filter.TraceContextFilter   : access method=POST uri=/app/order/submit status=200 costMs=3456
                2026-04-18T02:06:23.147+08:00 ERROR 14992 --- [           main] com.mysql.cj.jdbc.ConnectionImpl         : Communications link failure
                """;

        OpsLogAnalyzer.AnalysisResult result = analyzer.analyze(
                List.of(new OpsLogAnalyzer.LogSource("app.log", log)),
                2000
        );

        assertThat(result.issues()).extracting(OpsLogAnalyzer.DetectedIssue::getIssueType)
                .contains("STARTUP_FAILURE", "HIGH_REQUEST_LATENCY", "DEPENDENCY_CONNECTION_FAILURE");
        assertThat(result.issues()).extracting(OpsLogAnalyzer.DetectedIssue::getCategory)
                .contains("APP", "INFRA", "PERFORMANCE_DB");
    }

    @Test
    void shouldGroupRepeatedExceptionIntoOneIssue() {
        String log = """
                2026-04-18T01:58:18.147+08:00 ERROR 14992 --- [           main] c.a.lease.OrderService                   : request failed
                java.lang.NullPointerException: something wrong
                2026-04-18T01:58:19.147+08:00 ERROR 14992 --- [           main] c.a.lease.OrderService                   : request failed
                java.lang.NullPointerException: something wrong
                """;

        OpsLogAnalyzer.AnalysisResult result = analyzer.analyze(
                List.of(new OpsLogAnalyzer.LogSource("repeat.log", log)),
                2000
        );

        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).getOccurrenceCount()).isEqualTo(2);
        assertThat(result.issues().get(0).getIssueType()).isEqualTo("APP_EXCEPTION");
    }
}
