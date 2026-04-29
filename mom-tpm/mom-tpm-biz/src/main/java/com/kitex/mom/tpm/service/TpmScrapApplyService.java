package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmScrapApplyEntity;

import java.util.List;

public interface TpmScrapApplyService extends IService<TpmScrapApplyEntity> {

    Boolean saveApply(TpmScrapApplyEntity tpmScrapApply);


    /**
     * 批量通过 、作废
     */
    Boolean handle(List<Long> toList, String type);
}