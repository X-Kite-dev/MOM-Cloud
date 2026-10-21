package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.WmsQualityActionExecuteCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusResult;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;

public interface WmsQualityStatusService extends IService<WmsQualityStatusOperationEntity> {
    WmsQualityStatusResult apply(WmsQualityStatusCommand command);

    WmsQualityStatusResult retry(Long operationId);

    WmsQualityStatusResult execute(WmsQualityActionExecuteCommand command);
}
