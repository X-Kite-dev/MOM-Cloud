package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.dto.WmsLiveStockDynamicGroupDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQueryDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQuerySumDTO;
import com.kitex.mom.wms.dto.app.WmsLiveStockMoveDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.vo.WmsLiveStockByMaterialExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockBySupplierExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockDetailExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockImportExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface WmsLiveStockService extends IService<WmsLiveStockEntity> {

    /**
     * 实时库存保存逻辑
     *
     * @param detail 物料详情
     * @return R
     */
    void materialSaveOrUpdate(WmsDetailDTO detail);

    /**
     * 分页查询库存明细
     */
    Page<WmsLiveStockEntity> pageDetail(Page page, WmsLiveStockQueryDTO wmsLiveStock);

    /**
     * 查询明细列表
     *
     * @param wmsLiveStock 插叙条件
     * @param ids          id集合
     * @return
     */
    List<WmsLiveStockDetailExcelVO> listDetail(WmsLiveStockQueryDTO wmsLiveStock, String[] ids);

    /**
     * 查询物料级列表
     */
    List<WmsLiveStockByMaterialExcelVO> listByMat(QueryWrapper<WmsLiveStockEntity> wrapper);

    /**
     * 查询物料级列表
     */
    List<WmsLiveStockBySupplierExcelVO> listBySupplier(QueryWrapper<WmsLiveStockEntity> wrapper);

    /**
     * 根据产品编码集合查询实时库存
     */
    List<WmsLiveStockEntity> getLiveStockByProductCodeList(Set<String> productCodeSet, String warehouseCode);

    /**
     * 出库减库存
     *
     * @param detail 出库单明细
     */
    List<WmsLiveStockEntity> reduceStockWhenOut(WmsOutBillDetailEntity detail);

    /**
     * 库存变化
     *
     * @param wmsLiveStockMoveDTO 库存
     */
    Boolean billStockMove(WmsLiveStockMoveDTO wmsLiveStockMoveDTO);

    /**
     * 分页查询库存-出库单新增时
     */
    Page<WmsLiveStockEntity> pageWhenOut(Page page, WmsLiveStockQueryDTO wmsLiveStock);

    /**
     * 查询汇总一条数据
     */
    WmsLiveStockEntity getSumByParams(WmsLiveStockQuerySumDTO params);

    /**
     * 期初导入
     *
     * @param excelVOS      文件内容
     * @param bindingResult 错误信息
     * @param warehouseCode 仓库编码
     * @param factoryCode   工厂编码
     * @return
     */
    R importExcel(List<WmsLiveStockImportExcelVO> excelVOS, BindingResult bindingResult, String warehouseCode, String factoryCode);

    /**
     * 查询各仓库对应的物料数量
     *
     * @param temporaryPlanQueryDTO 查询DTO
     * @return
     */
    List<Map<String, Object>> getWarehouseLiveStock(TemporaryPlanQueryDTO temporaryPlanQueryDTO);

    /**
     * 动态分组查询库存
     *
     * @param queryDTO 查询条件
     * @return 分组后的库存汇总数据，带分页
     */
    Page<Map<String, Object>> queryByDynamicGroup(Page page, WmsLiveStockDynamicGroupDTO queryDTO);
}