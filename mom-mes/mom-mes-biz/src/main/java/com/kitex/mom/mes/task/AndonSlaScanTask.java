package com.kitex.mom.mes.task;

import com.kitex.mom.mes.service.MesAndonSlaScanService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AndonSlaScanTask {

    private final MesAndonSlaScanService scanService;

    @XxlJob("mesAndonSlaScan")
    public void execute() {
        log.info("开始执行安灯SLA扫描任务");
        scanService.scanAndEscalate();
        log.info("安灯SLA扫描任务执行完成");
    }
}
