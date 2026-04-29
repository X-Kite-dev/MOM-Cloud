package com.kitex.mom.common.barcode.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;

public interface PrintTemplateService extends IService<PrintTemplateEntity> {

    String preview(String code);

    PrintTemplateEntity getByCode(String code);

    String billPreview(PreviewObj obj);
}