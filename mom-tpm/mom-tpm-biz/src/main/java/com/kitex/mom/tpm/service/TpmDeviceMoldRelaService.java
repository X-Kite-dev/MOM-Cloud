package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity;

public interface TpmDeviceMoldRelaService extends IService<TpmDeviceMoldRelaEntity> {


    Page<TpmDeviceMoldRelaEntity> getTpmDeviceMoldRelaPage(Page toMpPage, TpmDeviceMoldRelaEntity tpmDeviceMoldRela);

    Boolean saveRela(TpmDeviceMoldRelaEntity tpmDeviceMoldRelaEntity);
}