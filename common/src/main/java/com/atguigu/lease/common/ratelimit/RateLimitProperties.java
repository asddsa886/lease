package com.atguigu.lease.common.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 限流配置：用于把阈值从代码里抽出来，方便按环境调整。
 */
@Data
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private Admin admin = new Admin();
    private App app = new App();

    @Data
    public static class Admin {
        private Rule captcha = new Rule(30, Duration.ofSeconds(60));
        private Rule login = new Rule(20, Duration.ofSeconds(60));
    }

    @Data
    public static class App {
        private DimRule sms = new DimRule(
                new Rule(20, Duration.ofSeconds(60)),
                new Rule(3, Duration.ofSeconds(60))
        );

        private DimRule login = new DimRule(
                new Rule(30, Duration.ofSeconds(60)),
                new Rule(10, Duration.ofSeconds(60))
        );

        private Appointment appointment = new Appointment();

        @Data
        public static class Appointment {
            private Rule userId = new Rule(5, Duration.ofSeconds(60));
            private Rule ip = new Rule(60, Duration.ofSeconds(60));
        }
    }

    /**
     * ip/phone 等双维度规则。
     */
    @Data
    public static class DimRule {
        private Rule ip;
        private Rule phone;

        public DimRule() {
        }

        public DimRule(Rule ip, Rule phone) {
            this.ip = ip;
            this.phone = phone;
        }
    }

    @Data
    public static class Rule {
        private int limit;

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration window;

        public Rule() {
        }

        public Rule(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }
    }
}
