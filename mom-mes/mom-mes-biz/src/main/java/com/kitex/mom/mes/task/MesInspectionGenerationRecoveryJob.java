package com.kitex.mom.mes.task;

import com.kitex.mom.mes.service.impl.MesInspectionGenerationDispatcher;
import com.xxl.job.core.handler.annotation.XxlJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MesInspectionGenerationRecoveryJob {
    private final MesInspectionGenerationDispatcher dispatcher;

    @XxlJob("mes_inspection_generation_recovery")
    public void recover() {
        log.info("首检原号恢复已确认生成数量={}", dispatcher.scan());
    }
}
