package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesDispatchedOrderProgressDTO;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;

/**
 * 精确来源计划的只读执行证据。
 */
public interface MesPlanExecutionEvidenceService extends IService<MesTaskEntity> {
    /**
     * 同一数据库快照批量读取；超安全边界拒绝，不截断归属数量。
     */
    MesPlanExecutionEvidenceDTO.Evidence query(MesPlanExecutionEvidenceDTO.Query query);

    /**
     * 按原接单号、工单主键及编码读取同一工厂的实时执行情况。
     * @param query 当前工厂及原 MES 接单身份
     * @return 当前一致性快照中的来源份额、各工序及待核原因；身份不符时拒绝读取
     */
    MesDispatchedOrderProgressDTO.View orderProgress(MesDispatchedOrderProgressDTO.Query query);
}
