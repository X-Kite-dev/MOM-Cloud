package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesTimingExceptionEntity;
import com.kitex.mom.mes.entity.MesTimingExceptionOperationEntity;

import java.util.List;

/**
 * 当前主账与永久动作分开，不能以当前状态替代某次动作的原resultJson。
 */
public record MesTimingExceptionDetailDTO(MesTimingExceptionEntity caseInfo,
                                          List<MesTimingExceptionOperationEntity> operations) {
}

