package com.kitex.mom.common.barcode.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.barcode.entity.BarcodeBusinessFieldEntity;
import com.kitex.mom.common.barcode.mapper.BarcodeBusinessFieldMapper;
import com.kitex.mom.common.barcode.service.BarcodeBusinessFieldService;
import org.springframework.stereotype.Service;

@Service
public class BarcodeBusinessFieldServiceImpl extends ServiceImpl<BarcodeBusinessFieldMapper, BarcodeBusinessFieldEntity>
        implements BarcodeBusinessFieldService {

}
