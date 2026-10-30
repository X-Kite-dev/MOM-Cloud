package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnApsLocalExecutionDTO;

/** 局部重排发布后的只读MES回执及现场执行取证，不改写任何业务状态。 */
public interface PlnApsLocalExecutionService {
    /** 发布号默认来自原PLAN业务；未知回执时允许核对原待确认号，但不据此认定已发布。 */
    PlnApsLocalExecutionDTO.Page page(Long apsCaseId, String candidateId, int current, String pendingRequestId);
}
