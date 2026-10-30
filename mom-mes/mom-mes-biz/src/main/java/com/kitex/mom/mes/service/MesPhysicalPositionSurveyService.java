package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesPhysicalPositionSurveyDTO;
import com.kitex.mom.mes.entity.MesPhysicalPositionSurveyEntity;

/**
 * 永久位置采集与受控决策；不开放通用CRUD，不改变库存或产量。
 */
public interface MesPhysicalPositionSurveyService extends IService<MesPhysicalPositionSurveyEntity> {
    /**
     * 原号、原正文保存完整观察，服务器在同事务重读全部真实来源。
     */
    MesPhysicalPositionSurveyDTO.Receipt capture(String requestId, String rawJson);

    /**
     * 独立原操作号；异人确认/驳回或本人取消，原采集内容不可改。
     */
    MesPhysicalPositionSurveyDTO.Receipt decide(String requestId, String operationRequestId, String rawJson);

    /**
     * 独立最新原账恢复；不重投、重开或根据当前库存猜原结果。
     */
    MesPhysicalPositionSurveyDTO.Receipt receipt(String requestId);
}
