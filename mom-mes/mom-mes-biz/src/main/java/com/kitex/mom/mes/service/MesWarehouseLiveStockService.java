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
    /** 只读可扣减候选；与真实出库相同资格/FIFO校验，不创建预留。 */
    List<MesWarehouseLiveStockEntity> previewOutCandidates(MesWarehouseLiveStockInoutDTO request);

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

    /** 人工维护不得覆盖报工质量库存事实。 */
    Boolean updateManual(MesWarehouseLiveStockEntity stock);

    /** 人工删除不得删除报工质量库存事实。 */
    Boolean removeManual(Long[] ids);
}
