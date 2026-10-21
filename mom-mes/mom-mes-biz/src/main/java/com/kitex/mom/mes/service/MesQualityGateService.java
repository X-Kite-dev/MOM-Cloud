package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;

public interface MesQualityGateService {

    MesQualityGateDecision checkReport(String produceTaskCode);

    MesQualityGateDecision checkFinish(MesProduceTaskEntity produceTask, MesTaskEntity task);

    MesQualityGateDecision sync(String produceTaskCode);

    MesQualityGateDecision retryGeneration(Long recordId);

    MesQualityGateDecision createChangePointFirstInspection(String produceTaskCode, String sourceRequestId,
                                                            String ruleCode, String ruleName);

    MesQualityGateDecision syncChangePointInspection(Long recordId);

    MesQualityGateDecision retryChangePointInspection(Long recordId);

    /**
     * 为变化点复产后的加严监控创建一次中检任务；来源请求号永久幂等。
     */
    MesQualityGateDecision createChangePointIntensifiedInspection(String produceTaskCode, String sourceRequestId,
                                                                  String ruleCode, String ruleName);
}
