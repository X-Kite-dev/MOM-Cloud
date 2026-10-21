package com.kitex.mom.qms.service;

/**
 * 把返工复检判定和数量处置可靠地同步回MES隔离库存。
 */
public interface QmsReworkResultPublisher {
    void publish(String inspectionType, Long qmsTaskId);

    void retry(Long reworkOrderId);
}
