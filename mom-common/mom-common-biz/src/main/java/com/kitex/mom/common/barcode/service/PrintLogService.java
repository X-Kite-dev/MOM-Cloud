package com.kitex.mom.common.barcode.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.common.barcode.entity.PrintLogEntity;

public interface PrintLogService extends IService<PrintLogEntity> {

    String preview(Long id);

    void reprint(Long id);

}