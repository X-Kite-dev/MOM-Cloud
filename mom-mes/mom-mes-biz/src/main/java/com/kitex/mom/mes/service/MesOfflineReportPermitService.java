package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesOfflineReportPermitDTO;
import com.kitex.mom.mes.dto.MesOfflineReportReplayDTO;
import com.kitex.mom.mes.entity.MesOfflineReportPermitEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;

/** 移动端弱网报工授权和受控重放服务。 */
public interface MesOfflineReportPermitService extends IService<MesOfflineReportPermitEntity> {

    /** 为一次在线预览结果签发短时、最小范围授权。 */
    MesOfflineReportPermitDTO issue(MesTaskArrangeEntity arrange, String submittedIdentifier,
                                    MesExecutionContractDTO contract);

    /** 按本地顺序逐条重放；单条失败不阻断同批其他命令。 */
    MesOfflineReportReplayDTO.BatchResult replay(MesOfflineReportReplayDTO.BatchRequest request);
}
