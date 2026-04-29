package com.kitex.mom.common.barcode.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.barcode.entity.BarcodeCheckLogEntity;
import com.kitex.mom.common.barcode.mapper.BarcodeCheckLogMapper;
import com.kitex.mom.common.barcode.service.BarcodeCheckLogService;
import org.springframework.stereotype.Service;

/**
 * 条码校验记录
 *
 * @author kitex
 * @date 2023-09-01 14:45:49
 */
@Service
public class BarcodeCheckLogServiceImpl extends ServiceImpl<BarcodeCheckLogMapper, BarcodeCheckLogEntity>
        implements BarcodeCheckLogService {

}