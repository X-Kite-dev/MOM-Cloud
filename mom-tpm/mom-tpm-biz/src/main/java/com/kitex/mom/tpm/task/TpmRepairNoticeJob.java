package com.kitex.mom.tpm.task;

import com.kitex.mom.tpm.service.impl.TpmRepairNoticeService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 每分钟追赶到期提醒；消息无幂等收件时异常转人工核对，不自动重发。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TpmRepairNoticeJob {
    private final TpmRepairNoticeService notices;

    @XxlJob("tpm_repair_notice_dispatch")
    public void dispatch() {
        log.info("维修持久提醒到期投递完成，确认送达数量={}", notices.dispatchDue(100));
    }
}
