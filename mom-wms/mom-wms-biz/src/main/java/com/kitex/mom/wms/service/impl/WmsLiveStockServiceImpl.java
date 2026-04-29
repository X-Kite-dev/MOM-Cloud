package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.dto.WmsLiveStockDynamicGroupDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQueryDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQuerySumDTO;
import com.kitex.mom.wms.dto.app.WmsLiveStockMoveDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.enums.YesNoEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.vo.WmsLiveStockByMaterialExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockBySupplierExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockDetailExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockImportExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 实时库存
 *
 * @author kitex
 * @date 2024-12-18 14:31:45
 */
@Service
@RequiredArgsConstructor
public class WmsLiveStockServiceImpl extends ServiceImpl<WmsLiveStockMapper, WmsLiveStockEntity> implements WmsLiveStockService {

    private final RemoteMaterialService remoteMaterialService;
    private final WmsContainerBarcodeMapper wmsContainerBarcodeMapper;
    private final WmsContainerUseHistoryMapper wmsContainerUseHistoryMapper;
    private final WmsInoutFlowMapper wmsInoutFlowMapper;
    private final RemoteWarehouseService remoteWarehouseService;
    private final RemoteDictService remoteDictService;
    private final WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;

    /**
     * 实时库存保存逻辑
     *
     * @param detail 物料详情
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void materialSaveOrUpdate(WmsDetailDTO detail) {
        WmsLiveStockEntity wmsLiveStock = Optional.ofNullable(this.getById(detail.getBillId())).orElseThrow(() -> new CheckedException("未查找到物料库存[" + detail.getBillId() + "]"));
        if (wmsLiveStock.getMatNum() < detail.getMatNum()) {
            throw new RuntimeException("库存不足");
        }
        wmsLiveStock.setMatNum(wmsLiveStock.getMatNum() - detail.getMatNum());
        if (wmsLiveStock.getMatNum() == 0) {
            this.removeById(wmsLiveStock.getId());
        } else {
            this.updateById(wmsLiveStock);
        }

        // 保存新的库存
        WmsLiveStockEntity saveEntity = new WmsLiveStockEntity();
        BeanUtils.copyProperties(detail, saveEntity);
        saveEntity.setInTime(LocalDateTime.now());
        saveEntity.setWarehouseCode(detail.getNewWarehouseCode());
        saveEntity.setWarehouseName(detail.getNewWarehouseName());
        saveEntity.setSiteCode(detail.getNewSiteCode());
        saveEntity.setSiteName(detail.getNewSiteName());
        this.save(saveEntity);

    }

    /**
     * 分页查询库存明细
     */
    @Override
    public Page<WmsLiveStockEntity> pageDetail(Page page, WmsLiveStockQueryDTO wmsLiveStock) {
        LambdaQueryWrapper<WmsLiveStockEntity> wrapper = getDetailListQueryWrapper(wmsLiveStock);
        return this.page(page, wrapper);
    }

    /**
     * 查询明细列表
     *
     * @param wmsLiveStock 插叙条件
     * @param ids          id集合
     */
    @Override
    public List<WmsLiveStockDetailExcelVO> listDetail(WmsLiveStockQueryDTO wmsLiveStock, String[] ids) {
        LambdaQueryWrapper<WmsLiveStockEntity> wrapper = new LambdaQueryWrapper<>();
        if (ids != null) {
            wrapper.in(BaseEntity::getId, ids);
        } else {
            wrapper = getDetailListQueryWrapper(wmsLiveStock);
        }
        List<WmsLiveStockEntity> list = list(wrapper);
        // 获取单位字典
        List<SysDictItemEntity> material_unit = Optional.ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
        List<WmsLiveStockDetailExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsLiveStockEntity liveStock = list.get(i);
            WmsLiveStockDetailExcelVO excelVO = new WmsLiveStockDetailExcelVO();
            BeanUtil.copyProperties(liveStock, excelVO);
            excelVO.setRowNo(i + 1);
            excelVO.setStatus(Optional.ofNullable(YesNoEnum.getByCode(liveStock.getStatus())).orElse(YesNoEnum.NO).getDesc());
            excelVO.setMatUnit(Optional.ofNullable(unitMap.get(liveStock.getMatUnit())).orElse(liveStock.getMatUnit()));
            result.add(excelVO);
        }
        return result;
    }

    /**
     * 查询物料级列表
     */
    @Override
    public List<WmsLiveStockByMaterialExcelVO> listByMat(QueryWrapper<WmsLiveStockEntity> wrapper) {
        List<WmsLiveStockEntity> list = list(wrapper);
        // 获取单位字典
        List<SysDictItemEntity> material_unit = Optional.ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
        List<WmsLiveStockByMaterialExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsLiveStockEntity liveStock = list.get(i);
            WmsLiveStockByMaterialExcelVO excelVO = new WmsLiveStockByMaterialExcelVO();
            BeanUtil.copyProperties(liveStock, excelVO);
            excelVO.setRowNo(i + 1);
            excelVO.setMatUnit(Optional.ofNullable(unitMap.get(liveStock.getMatUnit())).orElse(liveStock.getMatUnit()));
            result.add(excelVO);
        }
        return result;
    }

    /**
     * 查询物料级列表
     */
    @Override
    public List<WmsLiveStockBySupplierExcelVO> listBySupplier(QueryWrapper<WmsLiveStockEntity> wrapper) {
        List<WmsLiveStockEntity> list = list(wrapper);
        // 获取单位字典
        List<SysDictItemEntity> material_unit = Optional.ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
        List<WmsLiveStockBySupplierExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsLiveStockEntity liveStock = list.get(i);
            WmsLiveStockBySupplierExcelVO excelVO = new WmsLiveStockBySupplierExcelVO();
            BeanUtil.copyProperties(liveStock, excelVO);
            excelVO.setRowNo(i + 1);
            excelVO.setMatUnit(Optional.ofNullable(unitMap.get(liveStock.getMatUnit())).orElse(liveStock.getMatUnit()));
            result.add(excelVO);
        }
        return result;
    }

    public LambdaQueryWrapper<WmsLiveStockEntity> getDetailListQueryWrapper(WmsLiveStockQueryDTO wmsLiveStock) {
        LambdaQueryWrapper<WmsLiveStockEntity> wrapper = Wrappers.lambdaQuery();
        if (CollUtil.isNotEmpty(wmsLiveStock.getInTime())) {
            wrapper.ge(WmsLiveStockEntity::getInTime, wmsLiveStock.getInTime().get(0));
            if (wmsLiveStock.getInTime().size() > 1) {
                wrapper.le(WmsLiveStockEntity::getInTime, wmsLiveStock.getInTime().get(1));
            }
        }
        if (CollUtil.isNotEmpty(wmsLiveStock.getMatProduceDate())) {
            wrapper.ge(WmsLiveStockEntity::getMatProduceDate, wmsLiveStock.getMatProduceDate().get(0));
            if (wmsLiveStock.getMatProduceDate().size() > 1) {
                wrapper.le(WmsLiveStockEntity::getMatProduceDate, wmsLiveStock.getMatProduceDate().get(1));
            }
        }
        if (CollUtil.isNotEmpty(wmsLiveStock.getMatValidEndDate())) {
            wrapper.ge(WmsLiveStockEntity::getMatValidEndDate, wmsLiveStock.getMatValidEndDate().get(0));
            if (wmsLiveStock.getMatValidEndDate().size() > 1) {
                wrapper.le(WmsLiveStockEntity::getMatValidEndDate, wmsLiveStock.getMatValidEndDate().get(1));
            }
        }
        wrapper.eq(StrUtil.isNotBlank(wmsLiveStock.getWarehouseCode()), WmsLiveStockEntity::getWarehouseCode, wmsLiveStock.getWarehouseCode())
                .in(CollUtil.isNotEmpty(wmsLiveStock.getSiteCode()), WmsLiveStockEntity::getSiteCode, wmsLiveStock.getSiteCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatCode()), WmsLiveStockEntity::getMatCode, wmsLiveStock.getMatCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatName()), WmsLiveStockEntity::getMatName, wmsLiveStock.getMatName())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatBatch()), WmsLiveStockEntity::getMatBatch, wmsLiveStock.getMatBatch())
                .like(StrUtil.isNotBlank(wmsLiveStock.getSupplierCode()), WmsLiveStockEntity::getSupplierCode, wmsLiveStock.getSupplierCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getSupplierName()), WmsLiveStockEntity::getSupplierName, wmsLiveStock.getSupplierName())
                .ne(WmsLiveStockEntity::getMatNum, 0)
                .orderByDesc(BaseCore::getCreateTime);
        return wrapper;
    }

    /**
     * 出库减库存
     * 推荐出库物料时已修改：推荐出库物料时就已按照先入先出排过序了，
     * 这里可以保留先入先出的逻辑，查找到的物料可能是明确的【物料编码、 供应商、库位、容器、批次】
     *
     * @param detail 出库单明细
     * @return 削减的库存集合
     */
    @Override
    public List<WmsLiveStockEntity> reduceStockWhenOut(WmsOutBillDetailEntity detail) {
        // 查询物料是否配置先入先出逻辑
        WmsLiveStockEntity wmsLiveStockEntity = new WmsLiveStockEntity();
        BeanUtils.copyProperties(detail, wmsLiveStockEntity);
        List<WmsLiveStockEntity> liveStocks = this.searchLiveStockList(wmsLiveStockEntity);
        // 修改实时库存
        // 出库数量
        double outNum = detail.getMatNum();
        Double liveNum = liveStocks.stream().map(WmsLiveStockEntity::getMatNum).reduce(0d, Double::sum);
        if (outNum > liveNum) {
            throw new CheckedException(String.format("物料：[%s]出库数量大于库存数量。供应商：%s，批次：%s，库位：%s，容器：%s",
                    detail.getMatCode(), detail.getSupplierCode(), detail.getMatBatch(), detail.getSiteCode(), detail.getContainerCode()));
        }
        List<WmsLiveStockEntity> reduceList = new ArrayList<>();
        for (WmsLiveStockEntity liveStock : liveStocks) {
            Double matNum = liveStock.getMatNum();
            if (matNum < outNum) {
                // 这条不够，删除库存后继续循环
                baseMapper.deleteById(liveStock.getId());
                // 容器解除占用
                unUsedContainer(liveStock);
                outNum -= matNum;
                reduceList.add(liveStock);
            } else if (matNum == outNum) {
                // 刚好够数，删除后结束循环
                baseMapper.deleteById(liveStock.getId());
                // 容器解除占用
                unUsedContainer(liveStock);
                reduceList.add(liveStock);
                break;
            } else {
                // 这条库存够数了，修改数量后结束循环
                baseMapper.update(new LambdaUpdateWrapper<WmsLiveStockEntity>()
                        .set(WmsLiveStockEntity::getMatNum, matNum - outNum)
                        .eq(WmsLiveStockEntity::getId, liveStock.getId())
                );
                liveStock.setMatNum(outNum);
                reduceList.add(liveStock);
                break;
            }
        }
        return reduceList;
    }

    /**
     * 库存变化
     *
     * @param wmsLiveStockMoveDTO 库存
     */
    @Override
    public Boolean billStockMove(WmsLiveStockMoveDTO wmsLiveStockMoveDTO) {

        // 查询物料是否配置先入先出逻辑
        WmsLiveStockEntity wmsLiveStock = new WmsLiveStockEntity();
        BeanUtils.copyProperties(wmsLiveStockMoveDTO, wmsLiveStock);
        wmsLiveStock.setWarehouseCode(wmsLiveStockMoveDTO.getOldWarehouseCode());
        wmsLiveStock.setSiteCode(wmsLiveStockMoveDTO.getOldSiteCode());
        wmsLiveStock.setContainerCode(wmsLiveStockMoveDTO.getOldContainerCode());
        List<WmsLiveStockEntity> liveStocks = searchLiveStockList(wmsLiveStock);

        // 出库数量
        double outNum = wmsLiveStockMoveDTO.getOperateNum();
        Double liveNum = liveStocks.stream().map(WmsLiveStockEntity::getMatNum).reduce(0d, Double::sum);
        if (outNum > liveNum) {
            throw new CheckedException(String.format("物料：[%s]出库数量大于库存数量。供应商：%s，批次：%s，库位：%s，容器：%s",
                    wmsLiveStockMoveDTO.getMatCode(), wmsLiveStockMoveDTO.getSupplierCode(), wmsLiveStockMoveDTO.getMatBatch(),
                    wmsLiveStockMoveDTO.getOldSiteCode(), wmsLiveStockMoveDTO.getOldContainerCode()));
        }
        for (WmsLiveStockEntity liveStock : liveStocks) {
            Double matNum = liveStock.getMatNum();
            if (matNum < outNum) {
                // 这条不够，删除库存后继续循环
                baseMapper.deleteById(liveStock.getId());

                this.liveStockInsert(liveStock, wmsLiveStockMoveDTO, matNum);
                outNum -= matNum;
            } else if (matNum == outNum) {
                // 刚好够数，删除后结束循环
                baseMapper.deleteById(liveStock.getId());
                this.liveStockInsert(liveStock, wmsLiveStockMoveDTO, matNum);
                break;
            } else {
                // 这条库存够数了，修改数量后结束循环
                baseMapper.update(new LambdaUpdateWrapper<WmsLiveStockEntity>()
                        .set(WmsLiveStockEntity::getMatNum, matNum - outNum)
                        .eq(WmsLiveStockEntity::getId, liveStock.getId())
                );
                this.liveStockInsert(liveStock, wmsLiveStockMoveDTO, outNum);
                break;
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 库存新增
     */
    private void liveStockInsert(WmsLiveStockEntity liveStock, WmsLiveStockMoveDTO wmsLiveStockMoveDTO, double num) {
        liveStock.setId(null);
        liveStock.setMatNum(num);
//		liveStock.setOperateNum(num);
        liveStock.setWarehouseCode(wmsLiveStockMoveDTO.getNewWarehouseCode());
        liveStock.setWarehouseName(wmsLiveStockMoveDTO.getNewWarehouseName());
        liveStock.setSiteCode(wmsLiveStockMoveDTO.getNewSiteCode());
        liveStock.setSiteName(wmsLiveStockMoveDTO.getNewSiteName());
        liveStock.setContainerCode(wmsLiveStockMoveDTO.getNewContainerCode());
        baseMapper.insert(liveStock);
    }

    /*
     * 查询物料是否配置先入先出逻辑
     */
    private List<WmsLiveStockEntity> searchLiveStockList(WmsLiveStockEntity liveStock) {
        // 查询物料是否配置先入先出逻辑
        MdmMaterialEntity mdmMaterial = remoteMaterialService.getDetails(liveStock.getMatCode()).getData();
        // 决定排序字段
        String orderByField = Optional.ofNullable(mdmMaterial)
                .map(MdmMaterialEntity::getFifo)
                .filter(Boolean.TRUE::equals)
                .map(fifo -> mdmMaterial.getFifoField())
                .orElse("in_time");
        // 查找此物料的库存
        List<WmsLiveStockEntity> liveStocks = baseMapper.selectList(
                new QueryWrapper<WmsLiveStockEntity>()
                        .eq("warehouse_code", liveStock.getWarehouseCode())
                        .eq("mat_code", liveStock.getMatCode())
                        .eq(StrUtil.isNotBlank(liveStock.getMatBatch()), "mat_batch", liveStock.getMatBatch())
                        .eq("site_code", liveStock.getSiteCode())
                        .eq(StrUtil.isNotBlank(liveStock.getSupplierCode()), "supplier_code", liveStock.getSupplierCode())
                        .eq(StrUtil.isNotBlank(liveStock.getContainerCode()), "container_code", liveStock.getContainerCode())
                        .eq("status", YesNoEnum.NO.getCode())
                        .orderByAsc(orderByField)
        );
        return liveStocks;
    }

    /**
     * 分页查询库存-出库单新增时
     *
     * @param page
     * @param wmsLiveStock
     */
    @Override
    public Page<WmsLiveStockEntity> pageWhenOut(Page page, WmsLiveStockQueryDTO wmsLiveStock) {
        List<String> matCodes = null;
        Map<String, Long> idMap = new HashMap<>();
        if (wmsLiveStock.getNoticeId() != null) {
            // 查找出库通知单明细
            List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
                    .eq(WmsOutNoticeDetailEntity::getNoticeId, wmsLiveStock.getNoticeId())
            );
            matCodes = noticeDetails.stream().map(WmsOutNoticeDetailEntity::getMatCode).distinct().toList();
            idMap = noticeDetails.stream().collect(Collectors.toMap(WmsOutNoticeDetailEntity::getMatCode, WmsOutNoticeDetailEntity::getId, (a, b) -> a));
        }
        QueryWrapper<WmsLiveStockEntity> wrapper = new QueryWrapper<>();
        wrapper.select("mat_code", "mat_name", "supplier_code", "mat_batch", "mat_spec", "container_code", "site_code", "site_name", "mat_unit", "quality_result", "sum(mat_num) as mat_num");
        wrapper.lambda().like(StrUtil.isNotBlank(wmsLiveStock.getMatCode()), WmsLiveStockEntity::getMatCode, wmsLiveStock.getMatCode())
                .in(matCodes != null, WmsLiveStockEntity::getMatCode, matCodes)
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatName()), WmsLiveStockEntity::getMatName, wmsLiveStock.getMatName())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getWarehouseCode()), WmsLiveStockEntity::getWarehouseCode, wmsLiveStock.getWarehouseCode())
                .eq(WmsLiveStockEntity::getStatus, YesNoEnum.NO.getCode())
                .groupBy(WmsLiveStockEntity::getMatCode, WmsLiveStockEntity::getMatName, WmsLiveStockEntity::getMatBatch, WmsLiveStockEntity::getMatSpec, WmsLiveStockEntity::getSupplierCode, WmsLiveStockEntity::getContainerCode, WmsLiveStockEntity::getSiteCode, WmsLiveStockEntity::getSiteName, WmsLiveStockEntity::getMatUnit, WmsLiveStockEntity::getQualityResult)
                .orderByAsc(WmsLiveStockEntity::getMatBatch, WmsLiveStockEntity::getSupplierCode, WmsLiveStockEntity::getSiteCode, WmsLiveStockEntity::getContainerCode)
                .ne(WmsLiveStockEntity::getMatNum, 0);
        Page<WmsLiveStockEntity> result = this.page(page, wrapper);
        // 给物料明细赋值通知单明细id
        if (wmsLiveStock.getNoticeId() != null) {
            for (WmsLiveStockEntity record : result.getRecords()) {
                record.setNoticeDetailId(idMap.get(record.getMatCode()));
            }
        }
        return result;
    }

    /**
     * 查询汇总一条数据
     *
     * @param params
     */
    @Override
    public WmsLiveStockEntity getSumByParams(WmsLiveStockQuerySumDTO params) {
        List<String> cols = new ArrayList<>();
        cols.add("warehouse_code");
        cols.add("site_code");
        cols.add("mat_code");
        if (StrUtil.isNotBlank(params.getSupplierCode())) {
            cols.add("supplier_code");
        }
        if (StrUtil.isNotBlank(params.getMatBatch())) {
            cols.add("mat_batch");
        }
        if (StrUtil.isNotBlank(params.getContainerCode())) {
            cols.add("container_code");
        }
        QueryWrapper<WmsLiveStockEntity> wrapper = new QueryWrapper<>();
        wrapper.select("sum(mat_num) as mat_num");
        wrapper.lambda().eq(WmsLiveStockEntity::getWarehouseCode, params.getWarehouseCode())
                .eq(WmsLiveStockEntity::getSiteCode, params.getSiteCode())
                .eq(WmsLiveStockEntity::getMatCode, params.getMatCode())
                .eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsLiveStockEntity::getSupplierCode, params.getSupplierCode())
                .eq(StrUtil.isNotBlank(params.getMatBatch()), WmsLiveStockEntity::getMatBatch, params.getMatBatch())
                .eq(StrUtil.isNotBlank(params.getContainerCode()), WmsLiveStockEntity::getContainerCode, params.getContainerCode());
        wrapper.groupBy(cols);
        List<WmsLiveStockEntity> list = this.list(wrapper);
        WmsLiveStockEntity res = new WmsLiveStockEntity();
        BeanUtil.copyProperties(params, res);
        WmsLiveStockEntity newOne = Optional.ofNullable(this.getOne(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getWarehouseCode, params.getWarehouseCode())
                .eq(WmsLiveStockEntity::getSiteCode, params.getSiteCode())
                .eq(WmsLiveStockEntity::getMatCode, params.getMatCode())
                .eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsLiveStockEntity::getSupplierCode, params.getSupplierCode())
                .eq(StrUtil.isNotBlank(params.getMatBatch()), WmsLiveStockEntity::getMatBatch, params.getMatBatch())
                .eq(StrUtil.isNotBlank(params.getContainerCode()), WmsLiveStockEntity::getContainerCode, params.getContainerCode())
                .orderByDesc(WmsLiveStockEntity::getInTime)
                .last("limit 1")
        )).orElse(new WmsLiveStockEntity());
        res.setMatName(newOne.getMatName())
                .setSupplierName(StrUtil.isNotBlank(params.getSupplierCode()) ? newOne.getSupplierName() : null)
                .setMatUnit(newOne.getMatUnit())
                .setWarehouseName(newOne.getWarehouseName())
                .setSiteName(newOne.getSiteName());
        if (list.size() > 0) {
            res.setMatNum(list.get(0).getMatNum());
        } else {
            res.setMatNum(0d);
        }
        return res;
    }

    /**
     * 容器解除占用 如果还有其他库存使用这个
     * (目前使用历史 同容器同物料是一条记录)
     *
     * @param liveStock 要删除的实时库存
     */
    public void unUsedContainer(WmsLiveStockEntity liveStock) {
        // 是否还有别的库存明细使用这个容器，如果有，不需要修改状态
        List<WmsLiveStockEntity> liveStocks = baseMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getContainerCode, liveStock.getContainerCode())
        );
        if (liveStocks.size() == 0) {
            wmsContainerBarcodeMapper.update(new LambdaUpdateWrapper<WmsContainerBarcodeEntity>()
                    .set(WmsContainerBarcodeEntity::getIsUsed, YesNoEnum.NO.getCode())
                    .eq(WmsContainerBarcodeEntity::getCode, liveStock.getContainerCode())
            );
            // 使用历史设置结束时间
            wmsContainerUseHistoryMapper.update(new LambdaUpdateWrapper<WmsContainerUseHistoryEntity>()
                    .set(WmsContainerUseHistoryEntity::getEndTime, LocalDateTime.now())
                    .eq(WmsContainerUseHistoryEntity::getCode, liveStock.getContainerCode())
            );
        } else {
            // 是否有这个物料， 如果有，使用历史不需要设置结束时间
            List<WmsLiveStockEntity> matLiveStocks = liveStocks.stream().filter(it -> StrUtil.equals(it.getMatCode(), liveStock.getMatCode())).toList();
            if (matLiveStocks.size() == 0) {
                // 使用历史设置结束时间
                wmsContainerUseHistoryMapper.update(new LambdaUpdateWrapper<WmsContainerUseHistoryEntity>()
                        .set(WmsContainerUseHistoryEntity::getEndTime, LocalDateTime.now())
                        .eq(WmsContainerUseHistoryEntity::getCode, liveStock.getContainerCode())
                        .eq(WmsContainerUseHistoryEntity::getMatCode, liveStock.getMatCode())
                );
            }
        }
    }


    /**
     * 根据产品编码集合查询实时库存
     */
    @Override
    public List<WmsLiveStockEntity> getLiveStockByProductCodeList(Set<String> productCodeSet, String warehouseCode) {
        if (CollUtil.isEmpty(productCodeSet)) {
            throw new RuntimeException("产品编码集合为空，实时库存查询失败！");
        }
        return this.list(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(StrUtil.isNotBlank(warehouseCode), WmsLiveStockEntity::getWarehouseCode, warehouseCode)
                .in(WmsLiveStockEntity::getMatCode, productCodeSet));
    }

    /**
     * 期初导入
     *
     * @param excelVOS      文件内容
     * @param bindingResult 错误信息
     * @param warehouseCode 仓库编码
     * @param factoryCode   工厂编码
     * @return
     */
    @Override
    public R importExcel(List<WmsLiveStockImportExcelVO> excelVOS, BindingResult bindingResult, String warehouseCode, String factoryCode) {
        //通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 校验错误集合
        Set<String> errorMsg = new HashSet<>();
        // 查找该仓库是否有库存
        List<String> warehouseList = new ArrayList<>();
        if (StrUtil.isNotBlank(warehouseCode)) {
            warehouseList.add(warehouseCode);
        } else {
            warehouseList = excelVOS.stream().map(WmsLiveStockImportExcelVO::getWarehouseCode).distinct().toList();
        }
        for (String code : warehouseList) {
            errorMsg = new HashSet<>();
            WmsLiveStockQueryDTO wmsLiveStock = new WmsLiveStockQueryDTO().setWarehouseCode(code);
            long count = this.count(getDetailListQueryWrapper(wmsLiveStock));
            if (count > 0) {
                errorMsg.add(String.format("仓库【%s】存在库存，不可导入！", code));
                errorMessageList.add(new ErrorMessage(errorMsg));
            }
        }
        if (errorMessageList.size() > 0) {
            return R.failed(errorMessageList);
        }
        // 转换实体
        List<WmsLiveStockEntity> entities = new ArrayList<>();
        for (int i = 0; i < excelVOS.size(); i++) {
            WmsLiveStockImportExcelVO excelVO = excelVOS.get(i);
            errorMsg = new HashSet<>();
            WmsLiveStockEntity entity = new WmsLiveStockEntity();
            BeanUtil.copyProperties(excelVO, entity);
            // 计算有效截止日期
            if (excelVO.getMatProduceDate() != null && excelVO.getMatValidPeriod() != null) {
                entity.setMatValidEndDate(excelVO.getMatProduceDate().plus(excelVO.getMatValidPeriod(), ChronoUnit.DAYS));
            }
            if (excelVO.getInTime() == null) {
                errorMsg.add("入库时间为空");
            }
            entity.setFactoryCode(factoryCode);
            entities.add(entity);
            if (errorMsg.size() > 0) {
                errorMessageList.add(new ErrorMessage((long) (i + 1), errorMsg));
            }
        }
        // 如果参数仓库编码不为空，将文件里的仓库编码和仓库名称都设置为参数值
        if (StrUtil.isNotBlank(warehouseCode)) {
            MdmWarehouseEntity warehouse = remoteWarehouseService.getByCode(warehouseCode).getData();
            if (warehouse != null && StrUtil.isNotBlank(warehouse.getWarehouseName())) {
                entities.forEach(it -> it.setWarehouseCode(warehouseCode).setWarehouseName(warehouse.getWarehouseName()));
            }
        }
        if (CollUtil.isEmpty(errorMessageList)) {
            saveBatch(entities);
            // 入库流水
            List<WmsInoutFlowEntity> flowEntities = new ArrayList<>();
            for (WmsLiveStockEntity stock : entities) {
                WmsInoutFlowEntity inoutFlow = new WmsInoutFlowEntity();
                BeanUtil.copyProperties(stock, inoutFlow, "id");
                inoutFlow.setBillType(InBillTypeEnum.INIT_IMPORT.getCode());
                inoutFlow.setOperateType(InOutEnum.IN.getCode());
                flowEntities.add(inoutFlow);
            }
            wmsInoutFlowMapper.insert(flowEntities);
            return R.ok();
        } else {
            return R.failed(errorMessageList);
        }
    }

    /**
     * 查询各仓库对应的物料数量
     *
     * @param temporaryPlanQueryDTO 查询DTO
     * @return
     */
    @Override
    public List<Map<String, Object>> getWarehouseLiveStock(TemporaryPlanQueryDTO temporaryPlanQueryDTO) {
        Map<String, Object> matMap = remoteMaterialService.getList().getData().stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode,
                        entity -> Objects.requireNonNullElse(entity.getMinStock(), ""),
                        (a, b) -> a));

        QueryWrapper<WmsLiveStockEntity> wrapper = new QueryWrapper<>();
        wrapper.select("warehouse_code", "mat_code", "sum(mat_num) as mat_num");
        wrapper.lambda().in(ObjUtil.isNotEmpty(temporaryPlanQueryDTO.getMatCodes()), WmsLiveStockEntity::getMatCode, temporaryPlanQueryDTO.getMatCodes())
                .like(StrUtil.isNotBlank(temporaryPlanQueryDTO.getMatName()), WmsLiveStockEntity::getSupplierCode, temporaryPlanQueryDTO.getMatName())
                .groupBy(WmsLiveStockEntity::getWarehouseCode, WmsLiveStockEntity::getMatCode);
        List<WmsLiveStockEntity> list = this.list(wrapper);
        // 使用Map进行分组
        List<Map<String, Object>> resultMap = list.stream().collect(Collectors.groupingBy(WmsLiveStockEntity::getMatCode, Collectors.toMap(
                        WmsLiveStockEntity::getWarehouseCode,
                        WmsLiveStockEntity::getMatNum
                )
        )).entrySet().stream().map(entry -> {
            Map<String, Object> map = new HashMap<>();
            map.put("productCode", entry.getKey());
            map.put("warehouse", entry.getValue());
            map.put("warehouseStock", matMap.get(entry.getKey()));
            return map;
        }).collect(Collectors.toList());
        return resultMap;
    }

    @Override
    public Page<Map<String, Object>> queryByDynamicGroup(Page page, WmsLiveStockDynamicGroupDTO queryDTO) {

        QueryWrapper<WmsLiveStockEntity> wrapper = new QueryWrapper<>();

        // 当分组字段为空时，返回所有字段
        if (CollUtil.isEmpty(queryDTO.getGroupFields())) {
            wrapper.select("*, mat_num as total_stock");
        } else {
            // 构建select字段，包含分组字段和sum(mat_num)
            StringBuilder selectBuilder = new StringBuilder();
            for (String field : queryDTO.getGroupFields()) {
                if (selectBuilder.length() > 0) {
                    selectBuilder.append(", ");
                }
                // 设置名称和编码对应的
                if ("warehouse_code".equals(field)) {
                    selectBuilder.append("warehouse_code as warehouse_code, warehouse_name as warehouse_name");
                } else if ("mat_code".equals(field)) {
                    selectBuilder.append("mat_code as mat_code, mat_name as mat_name, mat_spec as mat_spec");
                } else if ("site_code".equals(field)) {
                    selectBuilder.append("site_code as site_code, site_name as site_name");
                } else if ("supplier_code".equals(field)) {
                    selectBuilder.append("supplier_code as supplier_code, supplier_name as supplier_name");
                } else {
                    selectBuilder.append(field);
                }
            }
            selectBuilder.append(", sum(mat_num) as total_stock");
            wrapper.select(selectBuilder.toString());
        }

        // 添加查询条件
        wrapper.lambda()
                .eq(StrUtil.isNotBlank(queryDTO.getWarehouseCode()),
                        WmsLiveStockEntity::getWarehouseCode, queryDTO.getWarehouseCode())
                .like(StrUtil.isNotBlank(queryDTO.getMatCode()),
                        WmsLiveStockEntity::getMatCode, queryDTO.getMatCode())
                .like(StrUtil.isNotBlank(queryDTO.getMatName()),
                        WmsLiveStockEntity::getMatName, queryDTO.getMatName())
                .in(CollUtil.isNotEmpty(queryDTO.getSiteCode()),
                        WmsLiveStockEntity::getSiteCode, queryDTO.getSiteCode())
                .ne(WmsLiveStockEntity::getMatNum, 0);

        // 只有在有分组字段时才添加分组
        if (CollUtil.isNotEmpty(queryDTO.getGroupFields())) {
            List<String> groupByFields = new java.util.ArrayList<>();
            for (String field : queryDTO.getGroupFields()) {
                if ("warehouse_code".equals(field)) {
                    groupByFields.add("warehouse_code");
                    groupByFields.add("warehouse_name");
                } else if ("mat_code".equals(field)) {
                    groupByFields.add("mat_code");
                    groupByFields.add("mat_name");
                    groupByFields.add("mat_spec");
                } else if ("site_code".equals(field)) {
                    groupByFields.add("site_code");
                    groupByFields.add("site_name");
                } else if ("supplier_code".equals(field)) {
                    groupByFields.add("supplier_code");
                    groupByFields.add("supplier_name");
                } else {
                    groupByFields.add(field);
                }
            }
            wrapper.groupBy(groupByFields);
        }

        // 执行分页查询
        return this.baseMapper.selectMapsPage(page, wrapper);
    }
}