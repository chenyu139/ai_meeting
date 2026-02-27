package com.gczm.aimeeting.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
class TingwuIntegrationTest {

    @Autowired
    private TingwuClient tingwuClient;

    @Test
    @Tag("integration")
    void realCreateStopAndQuery() throws InterruptedException {
        String mode = System.getenv("APP_TINGWU_MODE");
        String accessKeyId = System.getenv("APP_TINGWU_ACCESS_KEY_ID");
        String accessKeySecret = System.getenv("APP_TINGWU_ACCESS_KEY_SECRET");
        String appKey = System.getenv("APP_TINGWU_APP_KEY");

        assumeTrue(mode != null && mode.equalsIgnoreCase("sdk"), "Need APP_TINGWU_MODE=sdk");
        assumeTrue(accessKeyId != null && !accessKeyId.isBlank(), "Need APP_TINGWU_ACCESS_KEY_ID");
        assumeTrue(accessKeySecret != null && !accessKeySecret.isBlank(), "Need APP_TINGWU_ACCESS_KEY_SECRET");
        assumeTrue(appKey != null && !appKey.isBlank(), "Need APP_TINGWU_APP_KEY");

        Map<String, Object> created = tingwuClient.createRealtimeTask(
                "it-" + UUID.randomUUID(),
                "integration",
                ""
        );

        assertNotNull(created.get("task_id"));
        assertNotNull(created.get("meeting_join_url"));

        String taskId = String.valueOf(created.get("task_id"));
        Map<String, Object> stop = tingwuClient.stopTask(taskId);
        assertEquals(Boolean.TRUE, stop.get("ok"));

        String taskStatus = null;
        for (int i = 0; i < 20; i++) {
            Map<String, Object> info = tingwuClient.getTaskInfo(taskId);
            taskStatus = tingwuClient.extractTaskStatus(info);
            if (taskStatus != null && Set.of("ONGOING", "COMPLETED", "FAILED").contains(taskStatus)) {
                break;
            }
            Thread.sleep(3000L);
        }

        assertNotNull(taskStatus);
        assertTrue(Set.of("ONGOING", "COMPLETED", "FAILED").contains(taskStatus));
    }
}
