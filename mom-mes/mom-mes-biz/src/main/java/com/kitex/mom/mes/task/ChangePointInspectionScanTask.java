package com.kitex.mom.mes.task;

import com.kitex.mom.mes.service.MesChangePointHandleService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 推进变化点复产后的加严巡检监控。
 *
 * <p>任务只负责触发和同步QMS权威中检，是否重新抱闸或退出监控由变化点状态机决定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangePointInspectionScanTask {

    private final MesChangePointHandleService service;

    @XxlJob("mesChangePointInspectionScan")
    public void execute() {
        log.info("开始扫描变化点加严巡检");
        service.scanIntensifiedInspections();
        log.info("变化点加严巡检扫描完成");
    }
}
