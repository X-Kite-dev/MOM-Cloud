package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;

/** 精确来源计划的只读执行证据。 */
public interface MesPlanExecutionEvidenceService extends IService<MesTaskEntity> {
    /** 同一数据库快照批量读取；超安全边界拒绝，不截断归属数量。 */
    MesPlanExecutionEvidenceDTO.Evidence query(MesPlanExecutionEvidenceDTO.Query query);
}
