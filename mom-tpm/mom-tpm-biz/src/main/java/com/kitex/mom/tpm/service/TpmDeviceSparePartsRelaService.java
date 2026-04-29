package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDeviceSparePartsRelaEntity;

import java.util.ArrayList;

public interface TpmDeviceSparePartsRelaService extends IService<TpmDeviceSparePartsRelaEntity> {

    Page<TpmDeviceSparePartsRelaEntity> getTpmDeviceSparePartsRelaPage(Page toMpPage, TpmDeviceSparePartsRelaEntity tpmDeviceSparePartsRela);

    TpmDeviceSparePartsRelaEntity getRelaById(Long id);

    Boolean saveRela(TpmDeviceSparePartsRelaEntity tpmDeviceSparePartsRela);

    Boolean createApply(ArrayList<Long> toList);
}