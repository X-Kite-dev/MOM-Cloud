package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmCheckRecordEntity;

public interface TpmCheckRecordService extends IService<TpmCheckRecordEntity> {

    TpmCheckRecordEntity getRecordById(Long id);
}