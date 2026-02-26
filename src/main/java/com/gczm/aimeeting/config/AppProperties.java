package com.gczm.aimeeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String apiPrefix = "/api/v1";
    private String publicBaseUrl = "http://localhost:8080";
    private Storage storage = new Storage();
    private Tingwu tingwu = new Tingwu();
    private Knowledge knowledge = new Knowledge();
    private Worker worker = new Worker();

    @Data
    public static class Storage {
        private String mode = "local";
        private String localStorageRoot = "./storage";
        private String ossEndpoint = "";
        private String ossBucket = "";
        private String ossAccessKeyId = "";
        private String ossAccessKeySecret = "";
        private String ossStsToken = "";
        private String ossPublicBaseUrl = "";
    }

    @Data
    public static class Tingwu {
        private String mode = "sdk";
        private String region = "cn-beijing";
        private String endpoint = "tingwu.cn-beijing.aliyuncs.com";
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String appKey = "";
        private String webhookSigningKey = "";
        private String callbackBaseUrl = "http://localhost:8080";
    }

    @Data
    public static class Knowledge {
        private String searchUrl = "";
        private int timeoutSec = 5;
    }

    @Data
    public static class Worker {
        private boolean enabled = true;
        private int pollIntervalSec = 10;
        private int processingScanIntervalSec = 30;
        private int taskTimeoutSec = 120;
    }
}
