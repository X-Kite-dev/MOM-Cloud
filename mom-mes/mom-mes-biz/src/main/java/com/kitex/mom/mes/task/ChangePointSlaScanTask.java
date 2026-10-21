package com.kitex.mom.mes.task;

import com.kitex.mom.mes.service.MesChangePointHandleService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChangePointSlaScanTask {
    private final MesChangePointHandleService service;

    @XxlJob("mesChangePointSlaScan")
    public void execute() {
        log.info("开始扫描变化点SLA");
        service.scanSla();
        log.info("变化点SLA扫描完成");
    }
}
