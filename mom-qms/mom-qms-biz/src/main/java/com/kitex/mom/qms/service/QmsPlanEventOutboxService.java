package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.qms.dto.QmsPlanEventDTO;
import com.kitex.mom.qms.entity.QmsPlanEventOutboxEntity;

/**
 * QMS 质量事实 Outbox 写入与管理服务。
 *
 * <p>该接口只负责在质检业务事务内可靠保存隔离/处置事件，以及提供查询和人工重试能力。
 * 异步投递由独立处理器负责，避免写入服务依赖返工与质检任务服务而形成反向依赖。</p>
 */
public interface QmsPlanEventOutboxService {

    /**
     * 将质检隔离事实幂等写入 Outbox。
     */
    QmsPlanEventOutboxEntity enqueueIsolation(String inspectionType, Long inspectionTaskId);

    /**
     * 将质量处置事实幂等写入 Outbox。
     */
    QmsPlanEventOutboxEntity enqueueDisposition(String inspectionType, Long inspectionTaskId, Long handleRecordId);

    /**
     * 分页查询质量事件及其投递状态。
     */
    Page<QmsPlanEventOutboxEntity> page(SearchPage page, QmsPlanEventDTO.Query query);

    /**
     * 查询事件、逐计划投递目标和完整操作履历。
     */
    QmsPlanEventDTO.Detail detail(Long outboxId);

    /**
     * 在核验来源事实未变化后，将失败事件重新置为可投递状态。
     */
    boolean retry(QmsPlanEventDTO.ManualActionCommand command);
}
