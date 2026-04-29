package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.wms.dto.CreateContainerBarcodeDTO;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import com.kitex.mom.wms.enums.YesNoEnum;
import com.kitex.mom.wms.mapper.WmsContainerBarcodeMapper;
import com.kitex.mom.wms.service.WmsContainerBarcodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器码
 *
 * @author kitex
 * @date 2025-01-02 14:57:19
 */
@Service
@RequiredArgsConstructor
public class WmsContainerBarcodeServiceImpl extends ServiceImpl<WmsContainerBarcodeMapper, WmsContainerBarcodeEntity> implements WmsContainerBarcodeService {

    private final RemoteSeqConfigService remoteSeqConfigService;

    /**
     * 批量生成容器码
     *
     * @param params 参数
     */
    @Override
    public Boolean multiCreate(CreateContainerBarcodeDTO params) {
        Integer count = params.getCount();
        List<WmsContainerBarcodeEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = remoteSeqConfigService.create("wms_container_barcode").getData();
            WmsContainerBarcodeEntity item = new WmsContainerBarcodeEntity();
            BeanUtil.copyProperties(params, item);
            item.setCode(code).setIsUsed(YesNoEnum.NO.getCode());
            list.add(item);
        }
        return saveBatch(list);
    }
}