package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;

import java.util.List;

public interface WmsWarehouseBizService extends IService<WmsWarehouseBizEntity> {

    boolean updateAll(List<WmsWarehouseBizCheckVO> list);
}