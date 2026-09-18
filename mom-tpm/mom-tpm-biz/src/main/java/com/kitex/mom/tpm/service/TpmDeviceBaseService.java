package com.kitex.mom.tpm.service;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.tpm.vo.MdmDeviceExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;

public interface TpmDeviceBaseService {

    Page<MdmDeviceEntity> getAllPage(Page toMpPage, MdmDeviceEntity mdmDeviceEntity);

    Map<Integer, Long> getCount(MdmDeviceEntity mdmDeviceEntity);


    Page<MdmDeviceEntity> page(Page toMpPage, MdmDeviceEntity mdmDeviceEntity);

    MdmDeviceEntity getById(Long id);

    List<MdmDeviceClassEntity> getDevice();

    Boolean updateById(MdmDeviceEntity mdmDeviceEntity);

    Boolean removeBatchByIds(Long[] ids);

    Boolean saveDevice(MdmDeviceEntity mdmDevice);

    R importExcel(List<MdmDeviceExcelVO> mdmDeviceList, BindingResult bindingResult, String factoryCode);

    Boolean devicePause(String deviceCode, String moldCode);

    com.kitex.mom.tpm.dto.TpmDevicePauseReceipt devicePauseReceipt(com.kitex.mom.tpm.dto.TpmDevicePauseCommand command);

    Boolean deviceClose(String deviceCode, String moldCode);

    com.kitex.mom.tpm.dto.TpmDevicePauseReceipt deviceCloseReceipt(com.kitex.mom.tpm.dto.TpmDevicePauseCommand command);

    /**
     * 生成设备二维码
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    String print(PrintReq printReq);


}
