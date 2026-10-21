package com.kitex.mom.qms.task;

import com.kitex.mom.qms.service.impl.QmsFinishedGoodsDecisionReversalExpiryProcessor;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 成品质量判定逆向授权到期治理调度入口，不承载质量业务规则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QmsFinishedGoodsDecisionReversalExpiryJob {

    private final QmsFinishedGoodsDecisionReversalExpiryProcessor processor;

    /**
     * 建议每分钟运行；每张授权独立事务关闭并形成不可变系统履历。
     */
    @XxlJob("qms_finished_goods_decision_reversal_expire")
    public void expire() {
        int expired = processor.expireDue(200);
        log.info("成品质量判定逆向授权到期治理完成，本轮关闭数量={}", expired);
    }
}
