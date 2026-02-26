package com.gczm.aimeeting.task;

import com.gczm.aimeeting.config.AppProperties;
import com.gczm.aimeeting.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerScheduler {

    private final WorkerService workerService;
    private final AppProperties appProperties;

    @Scheduled(fixedDelayString = "#{${app.worker.poll-interval-sec:10} * 1000}")
    public void run() {
        if (!appProperties.getWorker().isEnabled()) {
            return;
        }
        try {
            workerService.runOnce();
        } catch (Exception ex) {
            log.error("worker scheduler run failed", ex);
        }
    }
}
