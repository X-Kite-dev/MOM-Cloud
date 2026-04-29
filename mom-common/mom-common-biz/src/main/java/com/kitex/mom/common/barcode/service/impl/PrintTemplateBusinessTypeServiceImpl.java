package com.kitex.mom.common.barcode.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.barcode.entity.PrintTemplateBusinessTypeEntity;
import com.kitex.mom.common.barcode.mapper.PrintTemplateBusinessTypeMapper;
import com.kitex.mom.common.barcode.service.PrintTemplateBusinessTypeService;
import org.springframework.stereotype.Service;

/**
 * 打印模板业务类型管理
 *
 * @author kitex
 * @date 2025-08-14 11:09:06
 */
@Service
public class PrintTemplateBusinessTypeServiceImpl extends ServiceImpl<PrintTemplateBusinessTypeMapper, PrintTemplateBusinessTypeEntity> implements PrintTemplateBusinessTypeService {
}