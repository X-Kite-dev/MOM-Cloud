package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.CreateContainerBarcodeDTO;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;

public interface WmsContainerBarcodeService extends IService<WmsContainerBarcodeEntity> {

    /**
     * 批量生成容器码
     */
    Boolean multiCreate(CreateContainerBarcodeDTO wmsContainerBarcode);
}