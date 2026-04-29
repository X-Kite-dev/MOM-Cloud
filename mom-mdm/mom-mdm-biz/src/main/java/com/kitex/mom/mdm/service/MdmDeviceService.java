package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.vo.excel.MdmDeviceExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmDeviceService extends IService<MdmDeviceEntity> {

    /**
     * 导入设备
     *
     * @param mdmDeviceList
     * @param bindingResult
     * @return
     */
    R importExcel(List<MdmDeviceExcelVO> mdmDeviceList, BindingResult bindingResult);

    /**
     * 新增设备
     *
     * @param mdmDevice 主体
     * @return Boolean
     */
    Boolean saveDevice(MdmDeviceEntity mdmDevice);
}
