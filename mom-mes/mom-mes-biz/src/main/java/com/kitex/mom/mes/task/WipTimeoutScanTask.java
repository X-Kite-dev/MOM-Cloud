package com.kitex.mom.mes.task;

import com.kitex.mom.mes.service.MesWipTimeoutHandleScanService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WipTimeoutScanTask {

    private final MesWipTimeoutHandleScanService scanService;

    @XxlJob("mesWipTimeoutScan")
    public void execute() {
        log.info("开始执行WIP超时扫描任务");
        scanService.scanAndEscalate();
        log.info("WIP超时扫描任务执行完成");
    }
}
