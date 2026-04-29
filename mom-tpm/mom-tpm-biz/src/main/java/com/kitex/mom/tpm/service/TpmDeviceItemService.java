package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;

import java.util.List;

public interface TpmDeviceItemService extends IService<TpmDeviceItemEntity> {

    Page<TpmDeviceItemEntity> pageList(Page toMpPage, TpmDeviceItemEntity tpmDeviceItem);

    List<TpmDeviceItemEntity> getDeviceItemByDeviceCode(String deviceCode, String modeCode, String type);

    Boolean saveRela(TpmDeviceItemEntity tpmDeviceItem);

}