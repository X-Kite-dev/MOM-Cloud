package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesLiveStockQueryDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockByMaterialExcelVO;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockDetailExcelVO;
import com.kitex.mom.mes.vo.MesWarehouseLiveStockVO;

import java.util.List;

public interface MesWarehouseLiveStockService extends IService<MesWarehouseLiveStockEntity> {

    List<MesWarehouseLiveStockDetailExcelVO> listDetailExcel(LambdaQueryWrapper<MesWarehouseLiveStockEntity> wrapper);

    List<MesWarehouseLiveStockByMaterialExcelVO> listByMatExcel(QueryWrapper<MesWarehouseLiveStockEntity> queryWrapperByMat);

    /**
     * 线边库实时库存入库
     */
    Boolean inStock(MesWarehouseLiveStockInoutDTO mesWarehouseLiveStock);

    /**
     * 线边库实时库存出库
     */
    Boolean outStock(MesWarehouseLiveStockInoutDTO mesWarehouseLiveStock);

    /**
     * 生产退料
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<MesWarehouseFeedDTO> getPageFeedBack(Page<MesWarehouseFeedDTO> pageParam, MesWarehouseFeedDTO query);

    /**
     * 分页查询-线边库类型（原材料、在制品、半成品、成品）
     *
     * @param page         分页对象
     * @param mesLiveStock 实时库存
     * @return
     */
    Page<MesWarehouseLiveStockVO> getWmsLiveStockPageByMatType(Page page, MesLiveStockQueryDTO mesLiveStock);
}