package com.atguigu.lease.common.minio;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
//@EnableConfigurationProperties(MinioProperties.class)
@ConfigurationPropertiesScan("com.atguigu.lease.common.minio") // 扫描包名

/**
 * MinIO 条件装配：只有当 minio.endpoint 有值时才启用。
 * <p>
 * 注意：application.yml 里 endpoint 常用 ${MINIO_ENDPOINT:}，默认会解析为空字符串，
 * 此时 {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
 * 会认为“属性存在”从而误触发装配，导致启动失败。
 */
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${minio.endpoint:}')")
public class MinioConfiguration {

//    @Value("${minio.endpoint}")
//    private String endpoint;

    @Autowired
    private MinioProperties minioProperties;


    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder().endpoint(minioProperties.getEndpoint()).
                credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey()).build();

    }
}
