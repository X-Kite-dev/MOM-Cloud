package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;

public interface MesQualityGateService {

    MesQualityGateDecision checkReport(String produceTaskCode);

    MesQualityGateDecision checkFinish(MesProduceTaskEntity produceTask, MesTaskEntity task);

    MesQualityGateDecision sync(String produceTaskCode);

    MesQualityGateDecision retryGeneration(Long recordId);

    /** 原号创建回执；供提交后/后台恢复，不使用人员令牌拉取质量结论。 */
    Long deliverPendingGeneration(Long recordId);

    MesQualityGateDecision createChangePointFirstInspection(
            String produceTaskCode, String sourceRequestId, String ruleCode, String ruleName);

    MesQualityGateDecision syncChangePointInspection(Long recordId);

    MesQualityGateDecision retryChangePointInspection(Long recordId);

    /** 为变化点复产后的加严监控创建一次中检任务；来源请求号永久幂等。 */
    MesQualityGateDecision createChangePointIntensifiedInspection(
            String produceTaskCode, String sourceRequestId, String ruleCode, String ruleName);

    /** 本域数量事务内只读冻结聚合；候选只供应用事务，不提前保存游标/缓存或远端pull。 */
    MesQualityGateDecision eventSnapshot(
            String produceTaskCode,
            String phase,
            Long recordId,
            Long eventId,
            java.util.Date appliedAt);
}
