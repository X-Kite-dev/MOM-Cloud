package com.kitex.mom.common.barcode.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.common.barcode.dto.req.PrintObj;
import com.kitex.mom.common.barcode.entity.PrintDeviceEntity;

public interface PrintDeviceService extends IService<PrintDeviceEntity> {

    void print(PrintObj obj);

}