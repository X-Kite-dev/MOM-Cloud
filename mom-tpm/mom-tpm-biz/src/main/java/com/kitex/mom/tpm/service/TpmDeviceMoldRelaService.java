package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity;

import java.util.List;

public interface TpmDeviceMoldRelaService extends IService<TpmDeviceMoldRelaEntity> {


    Page<TpmDeviceMoldRelaEntity> getTpmDeviceMoldRelaPage(Page toMpPage, TpmDeviceMoldRelaEntity tpmDeviceMoldRela);

    Boolean saveRela(TpmDeviceMoldRelaEntity tpmDeviceMoldRelaEntity);

    List<TpmDeviceMoldRelaEntity> getListByDeviceCodes(List<String> deviceCodes);

    Boolean removeScoped(List<Long> ids);

    /**
     * 报废流程仅清理当前明确组织的模具关系。
     */
    Boolean removeByMoldCodes(List<String> moldCodes);
}
