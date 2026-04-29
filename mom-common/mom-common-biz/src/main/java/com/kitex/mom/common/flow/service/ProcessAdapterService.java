package com.kitex.mom.common.flow.service;

import com.kitex.flow.task.dto.ProcessCompleteNotifyDTO;
import com.kitex.mom.common.flow.dto.StartFlowDataDTO;

public interface ProcessAdapterService {

    Boolean startFlow(StartFlowDataDTO startFlowDataDTO);

    Boolean backProcessComplete(ProcessCompleteNotifyDTO params);

}