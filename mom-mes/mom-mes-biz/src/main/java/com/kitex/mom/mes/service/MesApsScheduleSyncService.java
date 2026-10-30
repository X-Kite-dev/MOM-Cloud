package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.mes.dto.MesApsPublicationProbeDTO;

import java.util.List;

public interface MesApsScheduleSyncService {
    MesApsLocalScheduleDTO.StateBundle operationStates(String planNo, String factoryCode);

    MesApsLocalScheduleDTO.ApplyReceipt apply(MesApsLocalScheduleDTO.ApplyCommand command);

    /**
     * 只读一致性采集当前计划是否仍匹配原命令，不要求工序仍未执行，也不改现场事实。
     */
    MesApsPublicationProbeDTO.CurrentSchedule verifyAppliedSchedule(MesApsLocalScheduleDTO.ApplyCommand command,
                                                                    List<String> expectedProcessCodes);

    /**
     * 新合同同时核完整节点与主工序对应，不以主工序去重掩盖重复节点。
     */
    MesApsPublicationProbeDTO.CurrentSchedule verifyAppliedSchedule(MesApsLocalScheduleDTO.ApplyCommand command,
                                                                    List<String> expectedProcessCodes, List<String> expectedSourceRouteNodeCodes);
}
