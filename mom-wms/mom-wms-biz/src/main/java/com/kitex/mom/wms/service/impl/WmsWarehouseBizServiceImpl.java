package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.mapper.WmsWarehouseBizMapper;
import com.kitex.mom.wms.service.WmsWarehouseBizService;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 仓库业务配置
 *
 * @author kitex
 * @date 2025-01-13 17:48:14
 */
@Service
public class WmsWarehouseBizServiceImpl extends ServiceImpl<WmsWarehouseBizMapper, WmsWarehouseBizEntity> implements WmsWarehouseBizService {

    @Override
    @Transactional
    public boolean updateAll(List<WmsWarehouseBizCheckVO> list) {
        /* 获取当前工厂编码 */
        String factoryCode = FactoryContextHolder.getFactoryCode();
        // 1. 删除全部选择项
        remove(Wrappers.<WmsWarehouseBizEntity>lambdaQuery().eq(WmsWarehouseBizEntity::getFactoryCode, factoryCode));
        // 2. 重新添加选择项
        List<WmsWarehouseBizEntity> addList = new ArrayList<>();
        for (WmsWarehouseBizCheckVO checkVO : list) {
            for (String biz : checkVO.getBizs()) {
                WmsWarehouseBizEntity bizEntity = new WmsWarehouseBizEntity();
                bizEntity.setWarehouseCode(checkVO.getWarehouseCode())
                        .setBizType(biz)
                        .setFactoryCode(factoryCode)
                ;
                addList.add(bizEntity);
            }
        }
        return saveBatch(addList);
    }
}