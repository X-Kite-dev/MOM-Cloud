package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesLiveStockQueryDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.dto.MesWarehouseStockOutReceiptDTO;
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
     * 仅内部原业务事务取得真实逐库存行OUT全集；必须已有事务，不新开HTTP或客户端跳过参数。
     * 原单位/数量/必要写回读失败整笔回滚；不再次调用outStock，不按批次事后拼回来源。
     */
    MesWarehouseStockOutReceiptDTO outStockWithReceipt(MesWarehouseLiveStockInoutDTO request,
                                                       MesWarehouseStockOutReceiptDTO.Lineage lineage);

    /**
     * 只读可扣减候选；与真实出库相同资格/FIFO校验，不创建预留。
     */
    List<MesWarehouseLiveStockEntity> previewOutCandidates(MesWarehouseLiveStockInoutDTO request);

    /**
     * 投料来源全集观察，明确按来源仓库筛选；不虚构扣库数量/谱系或承诺库存预留。
     */
    List<MesWarehouseLiveStockEntity> previewFeedSources(MesWarehouseLiveStockInoutDTO request);

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

    /**
     * 人工维护不得覆盖报工质量库存事实。
     */
    Boolean updateManual(MesWarehouseLiveStockEntity stock);

    /**
     * 人工删除不得删除报工质量库存事实。
     */
    Boolean removeManual(Long[] ids);
}
