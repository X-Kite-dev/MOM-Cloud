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
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
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
import com.kitex.mom.wms.enums.InOutNoticeStateEnum;
import com.kitex.mom.wms.enums.YesNoEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsIdentifierPolicyService;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.WmsStockMoveSlice;
import com.kitex.mom.wms.util.WmsQuantityMath;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsStockOwnership;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import com.kitex.mom.wms.vo.WmsLiveStockByMaterialExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockBySupplierExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockDetailExcelVO;
import com.kitex.mom.wms.vo.WmsLiveStockImportExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.math.BigDecimal;
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
public class WmsLiveStockServiceImpl extends ServiceImpl<WmsLiveStockMapper, WmsLiveStockEntity>
        implements WmsLiveStockService {

    private final RemoteMaterialService remoteMaterialService;
    private final WmsContainerBarcodeMapper wmsContainerBarcodeMapper;
    private final WmsContainerUseHistoryMapper wmsContainerUseHistoryMapper;
    private final WmsInoutFlowMapper wmsInoutFlowMapper;
    private final RemoteWarehouseService remoteWarehouseService;
    private final RemoteDictService remoteDictService;
    private final WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;
    private final WmsMaterialReservationService wmsMaterialReservationService;
    private final WmsSalesStockReservationService wmsSalesStockReservationService;
    private final WmsIdentifierPolicyService identifierPolicyService;

    /**
     * 实时库存保存逻辑
     *
     * @param detail 物料详情
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsStockMoveSlice materialSaveOrUpdate(WmsDetailDTO detail) {
        if (detail == null || detail.getMatNum() == null || !Double.isFinite(detail.getMatNum())
                || detail.getMatNum() <= 0) {
            throw new CheckedException("库存移动数量必须为大于0的有限数值");
        }
        WmsLiveStockEntity wmsLiveStock = Optional.ofNullable(baseMapper.selectOne(
                        new LambdaQueryWrapper<WmsLiveStockEntity>()
                                .eq(WmsLiveStockEntity::getId, detail.getBillId()).last("FOR UPDATE"),
                        false))
                .orElseThrow(() -> new CheckedException("未查找到物料库存[" + detail.getBillId() + "]"));
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || StrUtil.isBlank(factory)
                || !Objects.equals(tenant, wmsLiveStock.getTenantId())
                || !Objects.equals(factory, wmsLiveStock.getFactoryCode())
                || !Objects.equals(detail.getOldWarehouseCode(), detail.getNewWarehouseCode())
                || !Objects.equals(detail.getOldWarehouseCode(), wmsLiveStock.getWarehouseCode())
                || !Objects.equals(detail.getOldSiteCode(), wmsLiveStock.getSiteCode())
                || StrUtil.isBlank(detail.getNewSiteCode())) {
            throw new CheckedException("移库源库存范围已变化，请刷新后重试");
        }
        if (unreservedNum(wmsLiveStock) < detail.getMatNum()) {
            throw new CheckedException("可移动库存不足；该库存行存在生产物料预留，预留数量禁止移库");
        }
        WmsSerialStockGuard.requireWhole(wmsLiveStock, BigDecimal.valueOf(detail.getMatNum()));
        WmsLiveStockEntity sourceSnapshot = new WmsLiveStockEntity();
        BeanUtils.copyProperties(wmsLiveStock, sourceSnapshot);
        wmsLiveStock.setMatNum(WmsQuantityMath.subtract(wmsLiveStock.getMatNum(), detail.getMatNum()));
        if (wmsLiveStock.getMatNum() == 0) {
            this.removeById(wmsLiveStock.getId());
        } else {
            this.updateById(wmsLiveStock);
        }

        // 从被移动的库存行拆分新行，不从请求DTO重建库存证据。
        // 这样可保留原采购订单行、收料批次、质量和生产日期等全部来源属性。
        WmsLiveStockEntity saveEntity = new WmsLiveStockEntity();
        BeanUtils.copyProperties(wmsLiveStock, saveEntity);
        saveEntity.setId(null);
        saveEntity.setMatNum(detail.getMatNum());
        saveEntity.setWarehouseCode(detail.getNewWarehouseCode());
        saveEntity.setWarehouseName(detail.getNewWarehouseName());
        saveEntity.setSiteCode(detail.getNewSiteCode());
        saveEntity.setSiteName(detail.getNewSiteName());
        if (!this.save(saveEntity)) {
            throw new CheckedException("目标库存新增未确认");
        }
        return new WmsStockMoveSlice(sourceSnapshot, saveEntity.getId(), detail.getMatNum());
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
     * @param wmsLiveStock 查询条件
     * @param ids          id集合
     */
    @Override
    public List<WmsLiveStockDetailExcelVO> listDetail(WmsLiveStockQueryDTO wmsLiveStock, String[] ids) {
        LambdaQueryWrapper<WmsLiveStockEntity> wrapper = getDetailListQueryWrapper(wmsLiveStock);
        if (ids != null && ids.length > 0) {
            wrapper.in(BaseEntity::getId, ids);
        }
        List<WmsLiveStockEntity> list = list(wrapper);
        // 获取单位字典
        List<SysDictItemEntity> material_unit = Optional
                .ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(
                Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
        List<WmsLiveStockDetailExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsLiveStockEntity liveStock = list.get(i);
            WmsLiveStockDetailExcelVO excelVO = new WmsLiveStockDetailExcelVO();
            BeanUtil.copyProperties(liveStock, excelVO);
            excelVO.setRowNo(i + 1);
            excelVO.setStatus(
                    Optional.ofNullable(YesNoEnum.getByCode(liveStock.getStatus())).orElse(YesNoEnum.NO).getDesc());
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
        List<SysDictItemEntity> material_unit = Optional
                .ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(
                Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
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
        List<SysDictItemEntity> material_unit = Optional
                .ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(
                Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
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
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        wrapper.eq(tenant != null, WmsLiveStockEntity::getTenantId, tenant)
                .eq(StrUtil.isNotBlank(factory), WmsLiveStockEntity::getFactoryCode, factory);
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
        wrapper.eq(wmsLiveStock.getStockId() != null, WmsLiveStockEntity::getId, wmsLiveStock.getStockId())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getWarehouseCode()), WmsLiveStockEntity::getWarehouseCode,
                        wmsLiveStock.getWarehouseCode())
                .in(CollUtil.isNotEmpty(wmsLiveStock.getSiteCode()), WmsLiveStockEntity::getSiteCode,
                        wmsLiveStock.getSiteCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatCode()), WmsLiveStockEntity::getMatCode,
                        wmsLiveStock.getMatCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatName()), WmsLiveStockEntity::getMatName,
                        wmsLiveStock.getMatName())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatBatch()), WmsLiveStockEntity::getMatBatch,
                        wmsLiveStock.getMatBatch())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getMatBarcode()), WmsLiveStockEntity::getMatBarcode,
                        wmsLiveStock.getMatBarcode())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getStockOwnerType()), WmsLiveStockEntity::getStockOwnerType,
                        wmsLiveStock.getStockOwnerType())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getStockOwnerCode()), WmsLiveStockEntity::getStockOwnerCode,
                        wmsLiveStock.getStockOwnerCode())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getSourceBusinessNo()), WmsLiveStockEntity::getSourceBusinessNo,
                        wmsLiveStock.getSourceBusinessNo())
                .like(StrUtil.isNotBlank(wmsLiveStock.getSupplierCode()), WmsLiveStockEntity::getSupplierCode,
                        wmsLiveStock.getSupplierCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getSupplierName()), WmsLiveStockEntity::getSupplierName,
                        wmsLiveStock.getSupplierName())
                .ne(WmsLiveStockEntity::getMatNum, 0)
                .orderByDesc(BaseCore::getCreateTime)
                .orderByDesc(WmsLiveStockEntity::getId);
        return wrapper;
    }

    /**
     * 出库减库存 推荐出库物料时已修改：推荐出库物料时就已按照先入先出排过序了， 这里可以保留先入先出的逻辑，查找到的物料可能是明确的【物料编码、 供应商、库位、容器、批次】
     *
     * @param detail 出库单明细
     * @return 削减的库存集合
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WmsLiveStockEntity> reduceStockWhenOut(WmsOutBillDetailEntity detail) {
        if (detail == null || detail.getMatNum() == null
                || !Double.isFinite(detail.getMatNum()) || detail.getMatNum() <= 0) {
            throw new CheckedException("出库标准数量必须为大于0的有限数值");
        }
        if (StrUtil.startWith(detail.getReservationNo(), WmsSalesStockReservationService.PREFIX)) {
            List<WmsLiveStockEntity> reduced = wmsSalesStockReservationService.consume(detail);
            reduced.stream().filter(stock -> baseMapper.selectById(stock.getId()) == null)
                    .forEach(this::unUsedContainer);
            return reduced;
        }
        if (StrUtil.isNotBlank(detail.getReservationNo())) {
            List<WmsLiveStockEntity> reduced = wmsMaterialReservationService.consume(detail);
            reduced.stream().filter(stock -> baseMapper.selectById(stock.getId()) == null)
                    .forEach(this::unUsedContainer);
            return reduced;
        }
        if (detail.getSourceStockId() != null) {
            return reduceSpecificStock(detail);
        }
        // 查询物料是否配置先入先出逻辑
        WmsLiveStockEntity wmsLiveStockEntity = new WmsLiveStockEntity();
        BeanUtils.copyProperties(detail, wmsLiveStockEntity);
        List<WmsLiveStockEntity> liveStocks = this.searchLiveStockList(wmsLiveStockEntity).stream()
                .filter(WmsStockAvailability::isUsable).toList();
        // 修改实时库存
        // 出库数量
        double outNum = detail.getMatNum();
        Double liveNum = liveStocks.stream().map(this::unreservedNum).reduce(0d, Double::sum);
        if (outNum > liveNum) {
            throw new CheckedException(String.format("物料：[%s]出库数量大于库存数量。供应商：%s，批次：%s，库位：%s，容器：%s",
                    detail.getMatCode(), detail.getSupplierCode(), detail.getMatBatch(), detail.getSiteCode(),
                    detail.getContainerCode()));
        }
        List<WmsLiveStockEntity> reduceList = new ArrayList<>();
        for (WmsLiveStockEntity liveStock : liveStocks) {
            double available = unreservedNum(liveStock);
            if (available <= 0) {
                continue;
            }
            WmsStockOwnership.validateOutbound(detail.getBillType(), detail, liveStock);
            double take = Math.min(available, outNum);
            WmsSerialStockGuard.requireWhole(liveStock, BigDecimal.valueOf(take));
            double balance = liveStock.getMatNum() - take;
            if (balance == 0d) {
                if (baseMapper.deleteById(liveStock.getId()) != 1) {
                    throw new CheckedException("出库源库存删除未确认");
                }
                unUsedContainer(liveStock);
            } else {
                if (baseMapper.update(new LambdaUpdateWrapper<WmsLiveStockEntity>()
                        .set(WmsLiveStockEntity::getMatNum, balance)
                        .eq(WmsLiveStockEntity::getId, liveStock.getId())) != 1) {
                    throw new CheckedException("出库源库存扣减未确认");
                }
            }
            liveStock.setMatNum(take);
            reduceList.add(liveStock);
            outNum -= take;
            if (outNum <= 0) {
                break;
            }
        }
        if (outNum > 0) {
            throw new CheckedException("出库分配未足额完成，库存扣减整体回滚");
        }
        return reduceList;
    }

    /**
     * 按审批时冻结的库存行出库。
     *
     * <p>
     * 受控退供不能在执行时被 FIFO 悄悄换成另一批、另一库位或另一供应商库存； 因此先锁定主键，再核对完整物流属性和扣除两类活动预留后的可退量。
     * </p>
     */
    private List<WmsLiveStockEntity> reduceSpecificStock(WmsOutBillDetailEntity detail) {
        WmsLiveStockEntity stock = Optional.ofNullable(baseMapper.selectOne(
                        new LambdaQueryWrapper<WmsLiveStockEntity>()
                                .eq(WmsLiveStockEntity::getId, detail.getSourceStockId()).last("FOR UPDATE"),
                        false))
                .orElseThrow(() -> new CheckedException("冻结库存行[" + detail.getSourceStockId() + "]已不存在"));
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || StrUtil.isBlank(factory)
                || !Objects.equals(tenant, stock.getTenantId())
                || !Objects.equals(factory, stock.getFactoryCode())
                || !Objects.equals(factory, detail.getFactoryCode())) {
            throw new CheckedException("冻结库存行不属于当前租户和工厂");
        }
        if (!Objects.equals(stock.getMatCode(), detail.getMatCode())
                || !Objects.equals(stock.getWarehouseCode(), detail.getWarehouseCode())
                || !Objects.equals(stock.getSiteCode(), detail.getSiteCode())
                || !Objects.equals(StrUtil.nullToEmpty(stock.getContainerCode()),
                StrUtil.nullToEmpty(detail.getContainerCode()))
                || !Objects.equals(StrUtil.nullToEmpty(stock.getMatBatch()),
                StrUtil.nullToEmpty(detail.getMatBatch()))
                || !Objects.equals(StrUtil.nullToEmpty(stock.getSupplierCode()),
                StrUtil.nullToEmpty(detail.getSupplierCode()))) {
            throw new CheckedException("冻结库存行的物料、供应商、批次、仓库、库位或容器已经变化");
        }
        WmsStockOwnership.validateOutbound(detail.getBillType(), detail, stock);
        if (!WmsStockAvailability.isPhysicallyUsable(stock)) {
            throw new CheckedException("冻结库存行已被锁定、过期、待检或质量隔离，禁止出库");
        }
        double requested = Optional.ofNullable(detail.getMatNum()).orElse(0d);
        if (requested <= 0 || unreservedNum(stock) < requested) {
            throw new CheckedException("冻结库存行扣除生产和销售预留后的可用数量不足");
        }
        WmsSerialStockGuard.requireWhole(stock, BigDecimal.valueOf(requested));
        WmsLiveStockEntity proof = new WmsLiveStockEntity();
        BeanUtils.copyProperties(stock, proof);
        proof.setMatNum(requested);
        double balance = BigDecimal.valueOf(stock.getMatNum()).subtract(BigDecimal.valueOf(requested)).doubleValue();
        if (BigDecimal.valueOf(balance).compareTo(BigDecimal.ZERO) == 0) {
            if (baseMapper.deleteById(stock.getId()) != 1) {
                throw new CheckedException("冻结出库源库存删除未确认");
            }
            unUsedContainer(stock);
        } else {
            if (baseMapper.update(new LambdaUpdateWrapper<WmsLiveStockEntity>()
                    .set(WmsLiveStockEntity::getMatNum, balance)
                    .eq(WmsLiveStockEntity::getId, stock.getId())) != 1) {
                throw new CheckedException("冻结出库源库存扣减未确认");
            }
        }
        return List.of(proof);
    }

    private Double unreservedNum(WmsLiveStockEntity stock) {
        BigDecimal physical = stock == null || stock.getMatNum() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(stock.getMatNum());
        return physical.subtract(wmsMaterialReservationService.activeReservedNum(stock.getId()))
                .subtract(wmsSalesStockReservationService.activeReservedNums(Set.of(stock.getId()))
                        .getOrDefault(stock.getId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO).doubleValue();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsLiveStockEntity transferOut(Long stockId, Double quantity, String expectedWarehouseCode) {
        if (stockId == null || quantity == null || !Double.isFinite(quantity) || quantity <= 0) {
            throw new CheckedException("调拨源库存和数量不能为空，且数量必须大于0");
        }
        WmsLiveStockEntity stock = Optional.ofNullable(baseMapper.selectOne(
                        new LambdaQueryWrapper<WmsLiveStockEntity>()
                                .eq(WmsLiveStockEntity::getId, stockId).last("FOR UPDATE"),
                        false))
                .orElseThrow(() -> new CheckedException("调拨源库存[" + stockId + "]不存在或已被移动"));
        if (!Objects.equals(expectedWarehouseCode, stock.getWarehouseCode())) {
            throw new CheckedException("调拨源库存[" + stockId + "]不属于所选源仓库");
        }
        if (!WmsStockAvailability.isUsable(stock)) {
            throw new CheckedException("物料[" + stock.getMatCode() + "]库存被冻结、过期、待检或质量状态不可用，禁止调拨");
        }
        BigDecimal requested = BigDecimal.valueOf(quantity);
        BigDecimal movable = BigDecimal.valueOf(unreservedNum(stock));
        if (movable.compareTo(requested) < 0) {
            throw new CheckedException("物料[" + stock.getMatCode() + "]扣除生产预留后仅可调拨"
                    + movable.stripTrailingZeros().toPlainString());
        }
        WmsSerialStockGuard.requireWhole(stock, requested);
        WmsLiveStockEntity snapshot = new WmsLiveStockEntity();
        BeanUtils.copyProperties(stock, snapshot);
        snapshot.setMatNum(quantity);
        double balance = BigDecimal.valueOf(stock.getMatNum()).subtract(requested).doubleValue();
        if (BigDecimal.valueOf(balance).compareTo(BigDecimal.ZERO) == 0) {
            baseMapper.deleteById(stock.getId());
        } else {
            baseMapper.update(new LambdaUpdateWrapper<WmsLiveStockEntity>()
                    .eq(WmsLiveStockEntity::getId, stock.getId())
                    .set(WmsLiveStockEntity::getMatNum, balance));
        }
        return snapshot;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferIn(WmsInoutFlowEntity sourceFlow, String warehouseCode, String warehouseName,
                           String siteCode, String siteName, String containerCode) {
        if (sourceFlow == null || sourceFlow.getMatNum() == null
                || !Double.isFinite(sourceFlow.getMatNum()) || sourceFlow.getMatNum() <= 0) {
            throw new CheckedException("调拨入库缺少有效发起流水");
        }
        WmsLiveStockEntity stock = new WmsLiveStockEntity();
        copySourceLineage(sourceFlow, stock);
        stock.setMatCode(sourceFlow.getMatCode());
        stock.setMatName(sourceFlow.getMatName());
        stock.setMatBarcode(sourceFlow.getMatBarcode());
        stock.setSerialManaged(sourceFlow.getSerialManaged());
        stock.setMatGroup(sourceFlow.getMatGroup());
        stock.setMatGroupName(sourceFlow.getMatGroupName());
        stock.setMatSpec(sourceFlow.getMatSpec());
        stock.setMatBatch(sourceFlow.getMatBatch());
        stock.setMatPackSpec(sourceFlow.getMatPackSpec());
        stock.setSupplierCode(sourceFlow.getSupplierCode());
        stock.setSupplierName(sourceFlow.getSupplierName());
        stock.setWarehouseCode(warehouseCode);
        stock.setWarehouseName(warehouseName);
        stock.setSiteCode(siteCode);
        stock.setSiteName(siteName);
        stock.setContainerCode(containerCode);
        stock.setInTime(sourceFlow.getStockInTime() == null ? LocalDateTime.now() : sourceFlow.getStockInTime());
        stock.setMatNum(sourceFlow.getMatNum());
        stock.setMatUnit(sourceFlow.getMatUnit());
        stock.setMatProduceDate(sourceFlow.getMatProduceDate());
        stock.setMatValidPeriod(sourceFlow.getMatValidPeriod());
        stock.setMatValidEndDate(sourceFlow.getMatValidEndDate());
        stock.setQualityResult(sourceFlow.getQualityResult());
        stock.setQualityStatus(sourceFlow.getQualityStatus());
        stock.setQualityHoldNo(sourceFlow.getQualityHoldNo());
        stock.setQualitySourceType(sourceFlow.getQualitySourceType());
        stock.setQualitySourceId(sourceFlow.getQualitySourceId());
        stock.setIsQualityTest(sourceFlow.getIsQualityTest());
        stock.setQualityTaskId(sourceFlow.getQualityTaskId());
        stock.setStatus(0);
        stock.setExpansion(sourceFlow.getExpansion());
        stock.setExpirationStatus(sourceFlow.getExpirationStatus());
        stock.setSlowMaterialStatus(sourceFlow.getSlowMaterialStatus());
        stock.setFactoryCode(sourceFlow.getFactoryCode());
        WmsSerialStockGuard.requireWhole(stock, BigDecimal.valueOf(stock.getMatNum()));
        if (!save(stock)) {
            throw new CheckedException("物料[" + sourceFlow.getMatCode() + "]调拨入库写入失败");
        }
        occupyContainer(stock);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsLiveStockEntity restoreSalesShipment(WmsInoutFlowEntity sourceFlow) {
        if (sourceFlow == null || sourceFlow.getId() == null
                || !"SALES_SHIPMENT".equals(sourceFlow.getStockProofType())
                || sourceFlow.getSourceStockId() == null || sourceFlow.getBillDetailId() == null
                || sourceFlow.getMatNum() == null || !Double.isFinite(sourceFlow.getMatNum())
                || sourceFlow.getMatNum() <= 0) {
            throw new CheckedException("销售发运库存证据不完整，禁止自动恢复");
        }
        WmsLiveStockEntity stock = new WmsLiveStockEntity();
        copySourceLineage(sourceFlow, stock);
        stock.setMatCode(sourceFlow.getMatCode());
        stock.setMatName(sourceFlow.getMatName());
        stock.setMatBarcode(sourceFlow.getMatBarcode());
        stock.setSerialManaged(sourceFlow.getSerialManaged());
        stock.setMatGroup(sourceFlow.getMatGroup());
        stock.setMatGroupName(sourceFlow.getMatGroupName());
        stock.setMatSpec(sourceFlow.getMatSpec());
        stock.setMatBatch(sourceFlow.getMatBatch());
        stock.setMatPackSpec(sourceFlow.getMatPackSpec());
        stock.setSupplierCode(sourceFlow.getSupplierCode());
        stock.setSupplierName(sourceFlow.getSupplierName());
        stock.setWarehouseCode(sourceFlow.getWarehouseCode());
        stock.setWarehouseName(sourceFlow.getWarehouseName());
        stock.setSiteCode(sourceFlow.getSiteCode());
        stock.setSiteName(sourceFlow.getSiteName());
        stock.setContainerCode(sourceFlow.getContainerCode());
        stock.setInTime(sourceFlow.getStockInTime());
        stock.setMatNum(sourceFlow.getMatNum());
        stock.setMatUnit(sourceFlow.getMatUnit());
        stock.setMatProduceDate(sourceFlow.getMatProduceDate());
        stock.setMatValidPeriod(sourceFlow.getMatValidPeriod());
        stock.setMatValidEndDate(sourceFlow.getMatValidEndDate());
        stock.setQualityResult(sourceFlow.getQualityResult());
        stock.setQualityStatus(sourceFlow.getQualityStatus());
        stock.setQualityHoldNo(sourceFlow.getQualityHoldNo());
        stock.setQualitySourceType(sourceFlow.getQualitySourceType());
        stock.setQualitySourceId(sourceFlow.getQualitySourceId());
        stock.setIsQualityTest(sourceFlow.getIsQualityTest());
        stock.setQualityTaskId(sourceFlow.getQualityTaskId());
        stock.setStatus(0);
        stock.setExpansion(sourceFlow.getExpansion());
        stock.setExpirationStatus(sourceFlow.getExpirationStatus());
        stock.setSlowMaterialStatus(sourceFlow.getSlowMaterialStatus());
        stock.setFactoryCode(sourceFlow.getFactoryCode());
        if (stock.getInTime() == null || StrUtil.isBlank(stock.getWarehouseCode())
                || StrUtil.isBlank(stock.getSiteCode()) || StrUtil.isBlank(stock.getMatCode())
                || StrUtil.isBlank(stock.getFactoryCode())) {
            throw new CheckedException("销售发运库存证据缺少原入库时间、仓库、库位、物料或工厂");
        }
        WmsSerialStockGuard.requireWhole(stock, BigDecimal.valueOf(stock.getMatNum()));
        if (!save(stock)) {
            throw new CheckedException("销售发运红冲恢复库存失败");
        }
        occupyContainer(stock);
        return stock;
    }

    /**
     * 保留调拨、发运纠错前的首次入库来源。 采购来源决定退供资格，MES成品来源决定实收入库能否安全红冲，两类证据都不能因库存移动而丢失。
     */
    private void copySourceLineage(WmsInoutFlowEntity source, WmsLiveStockEntity target) {
        target.setStockOwnerType(StrUtil.blankToDefault(source.getStockOwnerType(), WmsStockOwnership.OWNED));
        target.setStockOwnerCode(source.getStockOwnerCode());
        target.setSourceBusinessNo(source.getSourceBusinessNo());
        target.setSourceOrderNo(source.getSourceOrderNo());
        target.setSourceOrderDetailId(source.getSourceOrderDetailId());
        target.setSourceReceiptId(source.getSourceReceiptId());
        target.setSourceReceiptDetailId(source.getSourceReceiptDetailId());
        target.setSourceInBillId(source.getSourceInBillId());
        target.setSourceInBillDetailId(source.getSourceInBillDetailId());
        target.setSourceMesInboundRequestId(source.getSourceMesInboundRequestId());
        target.setSourceQualityTaskIds(source.getSourceQualityTaskIds());
        target.setSourceQualityEvidenceFingerprint(source.getSourceQualityEvidenceFingerprint());
        target.setSourceQualityEvidenceSnapshot(source.getSourceQualityEvidenceSnapshot());
    }

    @Override
    public void releaseTransferSourceContainer(WmsInoutFlowEntity sourceFlow) {
        if (sourceFlow != null && StrUtil.isNotBlank(sourceFlow.getContainerCode())) {
            WmsLiveStockEntity source = new WmsLiveStockEntity();
            source.setContainerCode(sourceFlow.getContainerCode());
            source.setMatCode(sourceFlow.getMatCode());
            unUsedContainer(source);
        }
    }

    /**
     * 库存变化
     *
     * @param wmsLiveStockMoveDTO 库存
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean billStockMove(WmsLiveStockMoveDTO wmsLiveStockMoveDTO) {
        return !billStockMoveWithEvidence(wmsLiveStockMoveDTO).isEmpty();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WmsStockMoveSlice> billStockMoveWithEvidence(WmsLiveStockMoveDTO wmsLiveStockMoveDTO) {
        if (wmsLiveStockMoveDTO == null || wmsLiveStockMoveDTO.getOperateNum() == null
                || !Double.isFinite(wmsLiveStockMoveDTO.getOperateNum()) || wmsLiveStockMoveDTO.getOperateNum() <= 0) {
            throw new CheckedException("库存移动数量必须为大于0的有限数值");
        }

        // 查询物料是否配置先入先出逻辑
        WmsLiveStockEntity wmsLiveStock = new WmsLiveStockEntity();
        BeanUtils.copyProperties(wmsLiveStockMoveDTO, wmsLiveStock);
        wmsLiveStock.setWarehouseCode(wmsLiveStockMoveDTO.getOldWarehouseCode());
        wmsLiveStock.setSiteCode(wmsLiveStockMoveDTO.getOldSiteCode());
        wmsLiveStock.setContainerCode(wmsLiveStockMoveDTO.getOldContainerCode());
        List<WmsLiveStockEntity> liveStocks = searchLiveStockList(wmsLiveStock);
        Long selectedStockId = wmsLiveStockMoveDTO.getSourceStockId();
        if (selectedStockId != null) {
            liveStocks = liveStocks.stream().filter(stock -> Objects.equals(selectedStockId, stock.getId())).toList();
            if (liveStocks.size() != 1) {
                throw new CheckedException("扫码选择的来源库存行已变化，请重新读取实物");
            }
            if (StrUtil.isNotBlank(wmsLiveStockMoveDTO.getMatBarcode())
                    && !Objects.equals(wmsLiveStockMoveDTO.getMatBarcode(), liveStocks.get(0).getMatBarcode())) {
                throw new CheckedException("来源库存行与扫描实物条码不一致");
            }
        }
        if (liveStocks.stream().anyMatch(stock -> Boolean.TRUE.equals(stock.getSerialManaged()))
                && (selectedStockId == null || StrUtil.isBlank(wmsLiveStockMoveDTO.getMatBarcode())
                || !Objects.equals(wmsLiveStockMoveDTO.getMatBarcode(), liveStocks.get(0).getMatBarcode()))) {
            throw new CheckedException("单件序列号库存必须精确选择来源库存行并核对实物条码");
        }
        requireConsistentMovementIdentity(liveStocks);

        // 出库数量
        double outNum = wmsLiveStockMoveDTO.getOperateNum();
        double liveNum = liveStocks.stream().map(this::unreservedNum).reduce(0d, WmsQuantityMath::add);
        if (outNum > liveNum) {
            throw new CheckedException(String.format("物料：[%s]出库数量大于库存数量。供应商：%s，批次：%s，库位：%s，容器：%s",
                    wmsLiveStockMoveDTO.getMatCode(), wmsLiveStockMoveDTO.getSupplierCode(),
                    wmsLiveStockMoveDTO.getMatBatch(),
                    wmsLiveStockMoveDTO.getOldSiteCode(), wmsLiveStockMoveDTO.getOldContainerCode()));
        }
        List<WmsStockMoveSlice> slices = new ArrayList<>();
        for (WmsLiveStockEntity liveStock : liveStocks) {
            double available = unreservedNum(liveStock);
            if (available <= 0) {
                continue;
            }
            double moveNum = Math.min(available, outNum);
            if (Boolean.TRUE.equals(wmsLiveStockMoveDTO.getAggregateFlow())
                    && Boolean.TRUE.equals(liveStock.getSerialManaged())) {
                throw new CheckedException("单件序列号库存请使用逐件留痕的调拨作业");
            }
            WmsSerialStockGuard.requireWhole(liveStock, BigDecimal.valueOf(moveNum));
            double balance = WmsQuantityMath.subtract(liveStock.getMatNum(), moveNum);
            Long originalId = liveStock.getId();
            if (balance == 0d) {
                if (baseMapper.deleteById(liveStock.getId()) != 1) {
                    throw new CheckedException("源库存移除未确认");
                }
                unUsedContainer(liveStock);
            } else {
                if (baseMapper.update(new LambdaUpdateWrapper<WmsLiveStockEntity>()
                        .set(WmsLiveStockEntity::getMatNum, balance)
                        .eq(WmsLiveStockEntity::getId, originalId)) != 1) {
                    throw new CheckedException("源库存扣减未确认");
                }
            }
            WmsLiveStockEntity targetStock = this.liveStockInsert(liveStock, wmsLiveStockMoveDTO, moveNum);
            WmsLiveStockEntity sourceSnapshot = new WmsLiveStockEntity();
            BeanUtils.copyProperties(liveStock, sourceSnapshot);
            slices.add(new WmsStockMoveSlice(sourceSnapshot, targetStock.getId(), moveNum));
            outNum = WmsQuantityMath.subtract(outNum, moveNum);
            if (outNum <= 0) {
                break;
            }
        }
        if (outNum > 0) {
            throw new CheckedException("实际可移动库存不足，未生成完整移库记录");
        }
        return slices;
    }

    private void requireConsistentMovementIdentity(List<WmsLiveStockEntity> candidates) {
        Long tenantId = TenantContextHolder.getTenantId();
        String factoryCode = FactoryContextHolder.getFactoryCode();
        WmsLiveStockEntity first = null;
        int serialRows = 0;
        for (WmsLiveStockEntity stock : candidates) {
            if (stock == null || !Objects.equals(tenantId, stock.getTenantId())
                    || !Objects.equals(factoryCode, stock.getFactoryCode())
                    || stock.getMatNum() == null || !Double.isFinite(stock.getMatNum())) {
                throw new CheckedException("移动范围存在无效或跨组织库存行");
            }
            if (stock.getMatNum() < 0) {
                throw new CheckedException("移动范围存在负数库存行");
            }
            if (stock.getMatNum() == 0) {
                continue;
            }
            if (first == null) {
                first = stock;
            } else if (!Objects.equals(first.getWarehouseCode(), stock.getWarehouseCode())
                    || !Objects.equals(first.getSiteCode(), stock.getSiteCode())
                    || !Objects.equals(first.getMatCode(), stock.getMatCode())
                    || !Objects.equals(first.getMatBatch(), stock.getMatBatch())
                    || !Objects.equals(first.getSupplierCode(), stock.getSupplierCode())
                    || !Objects.equals(first.getMatUnit(), stock.getMatUnit())
                    || !Objects.equals(StrUtil.nullToEmpty(first.getContainerCode()),
                    StrUtil.nullToEmpty(stock.getContainerCode()))
                    || !Objects.equals(StrUtil.blankToDefault(first.getStockOwnerType(), WmsStockOwnership.OWNED),
                    StrUtil.blankToDefault(stock.getStockOwnerType(), WmsStockOwnership.OWNED))
                    || !Objects.equals(StrUtil.nullToEmpty(first.getStockOwnerCode()),
                    StrUtil.nullToEmpty(stock.getStockOwnerCode()))
                    || !Objects.equals(first.getSourceBusinessNo(), stock.getSourceBusinessNo())
                    || !Objects.equals(Boolean.TRUE.equals(first.getSerialManaged()),
                    Boolean.TRUE.equals(stock.getSerialManaged()))) {
                throw new CheckedException("移动范围含不同批次、容器、单位、货权或来源，须分开作业");
            }
            if (Boolean.TRUE.equals(stock.getSerialManaged()) && ++serialRows > 1) {
                throw new CheckedException("单件序列号库存不能按物料批量移动");
            }
        }
    }

    /**
     * 库存新增
     */
    private WmsLiveStockEntity liveStockInsert(WmsLiveStockEntity sourceStock,
                                               WmsLiveStockMoveDTO move, double quantity) {
        WmsLiveStockEntity targetStock = new WmsLiveStockEntity();
        BeanUtils.copyProperties(sourceStock, targetStock);
        targetStock.setId(null);
        targetStock.setMatNum(quantity);
        targetStock.setWarehouseCode(move.getNewWarehouseCode());
        targetStock.setWarehouseName(move.getNewWarehouseName());
        targetStock.setSiteCode(move.getNewSiteCode());
        targetStock.setSiteName(move.getNewSiteName());
        targetStock.setContainerCode(move.getNewContainerCode());
        if (baseMapper.insert(targetStock) != 1) {
            throw new CheckedException("目标库存新增未确认");
        }
        occupyContainer(targetStock);
        return targetStock;
    }

    private void occupyContainer(WmsLiveStockEntity stock) {
        if (stock == null || StrUtil.isBlank(stock.getContainerCode())) {
            return;
        }
        WmsContainerBarcodeEntity container = wmsContainerBarcodeMapper
                .selectOne(new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
                        .eq(WmsContainerBarcodeEntity::getCode, stock.getContainerCode()).last("LIMIT 1"));
        if (container == null) {
            throw new CheckedException("容器[" + stock.getContainerCode() + "]不存在");
        }
        wmsContainerBarcodeMapper.update(new LambdaUpdateWrapper<WmsContainerBarcodeEntity>()
                .set(WmsContainerBarcodeEntity::getIsUsed, YesNoEnum.YES.getCode())
                .eq(WmsContainerBarcodeEntity::getId, container.getId()));
        Long activeHistory = wmsContainerUseHistoryMapper.selectCount(
                new LambdaQueryWrapper<WmsContainerUseHistoryEntity>()
                        .eq(WmsContainerUseHistoryEntity::getCode, container.getCode())
                        .eq(WmsContainerUseHistoryEntity::getMatCode, stock.getMatCode())
                        .isNull(WmsContainerUseHistoryEntity::getEndTime));
        if (activeHistory == 0) {
            WmsContainerUseHistoryEntity history = new WmsContainerUseHistoryEntity()
                    .setCode(container.getCode())
                    .setSiteCode(stock.getSiteCode())
                    .setSiteName(stock.getSiteName())
                    .setStartTime(LocalDateTime.now())
                    .setMatCode(stock.getMatCode())
                    .setMatName(stock.getMatName())
                    .setFactoryCode(stock.getFactoryCode());
            wmsContainerUseHistoryMapper.insert(history);
        }
    }

    /*
     * 查询物料是否配置先入先出逻辑
     */
    private List<WmsLiveStockEntity> searchLiveStockList(WmsLiveStockEntity liveStock) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || StrUtil.isBlank(factory)
                || (StrUtil.isNotBlank(liveStock.getFactoryCode())
                && !Objects.equals(factory, liveStock.getFactoryCode()))) {
            throw new CheckedException("库存分配必须具有一致的租户和工厂上下文");
        }
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
                        .eq("tenant_id", tenant)
                        .eq("factory_code", factory)
                        .eq("warehouse_code", liveStock.getWarehouseCode())
                        .eq("mat_code", liveStock.getMatCode())
                        .eq(StrUtil.isNotBlank(liveStock.getMatBatch()), "mat_batch", liveStock.getMatBatch())
                        .eq("site_code", liveStock.getSiteCode())
                        .eq(StrUtil.isNotBlank(liveStock.getSupplierCode()), "supplier_code",
                                liveStock.getSupplierCode())
                        .eq(StrUtil.isNotBlank(liveStock.getContainerCode()), "container_code",
                                liveStock.getContainerCode())
                        .eq("status", YesNoEnum.NO.getCode())
                        .orderByAsc(orderByField)
                        .last("FOR UPDATE"));
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
        requireScopedWarehouse(wmsLiveStock.getWarehouseCode());
        List<String> matCodes = null;
        Map<String, Long> idMap = new HashMap<>();
        if (wmsLiveStock.getNoticeId() != null) {
            List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper
                    .selectSelectableStockDetails(wmsLiveStock.getNoticeId(), TenantContextHolder.getTenantId(),
                            FactoryContextHolder.getFactoryCode(), wmsLiveStock.getWarehouseCode(),
                            InOutNoticeStateEnum.READY.getCode(), InOutNoticeStateEnum.RUNNING.getCode());
            if (noticeDetails.isEmpty()) {
                throw new CheckedException("出库通知单不存在、状态不可用或当前仓库无可选物料");
            }
            matCodes = noticeDetails.stream().map(WmsOutNoticeDetailEntity::getMatCode).distinct().toList();
            idMap = noticeDetails.stream().collect(Collectors.toMap(WmsOutNoticeDetailEntity::getMatCode,
                    WmsOutNoticeDetailEntity::getId, (a, b) -> a));
        }
        if (Boolean.TRUE.equals(wmsLiveStock.getExactStock())) {
            LambdaQueryWrapper<WmsLiveStockEntity> exactQuery = exactOutboundQuery(wmsLiveStock);
            exactQuery.in(matCodes != null, WmsLiveStockEntity::getMatCode, matCodes);
            return this.page(page, exactQuery);
        }
        QueryWrapper<WmsLiveStockEntity> wrapper = new QueryWrapper<>();
        wrapper.select("mat_code", "mat_name", "supplier_code", "mat_batch", "mat_spec", "container_code", "site_code",
                "site_name", "mat_unit", "quality_result", "sum(mat_num) as mat_num");
        wrapper.lambda()
                .eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsLiveStockEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatCode()), WmsLiveStockEntity::getMatCode,
                        wmsLiveStock.getMatCode())
                .in(matCodes != null, WmsLiveStockEntity::getMatCode, matCodes)
                .like(StrUtil.isNotBlank(wmsLiveStock.getMatName()), WmsLiveStockEntity::getMatName,
                        wmsLiveStock.getMatName())
                .eq(StrUtil.isNotBlank(wmsLiveStock.getWarehouseCode()), WmsLiveStockEntity::getWarehouseCode,
                        wmsLiveStock.getWarehouseCode())
                .eq(WmsLiveStockEntity::getStatus, YesNoEnum.NO.getCode())
                .and(owner -> owner.isNull(WmsLiveStockEntity::getStockOwnerType)
                        .or().eq(WmsLiveStockEntity::getStockOwnerType, "")
                        .or().eq(WmsLiveStockEntity::getStockOwnerType, WmsStockOwnership.OWNED))
                .and(quality -> quality.in(WmsLiveStockEntity::getQualityResult,
                                List.of("QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE", "CONCESSION"))
                        .or(noQuality -> noQuality
                                .and(required -> required.isNull(WmsLiveStockEntity::getIsQualityTest)
                                        .or().eq(WmsLiveStockEntity::getIsQualityTest, "")
                                        .or().eq(WmsLiveStockEntity::getIsQualityTest, "0"))
                                .and(resultStatus -> resultStatus.isNull(WmsLiveStockEntity::getQualityResult)
                                        .or().eq(WmsLiveStockEntity::getQualityResult, ""))))
                .and(status -> status.isNull(WmsLiveStockEntity::getQualityStatus)
                        .or().eq(WmsLiveStockEntity::getQualityStatus, "")
                        .or().in(WmsLiveStockEntity::getQualityStatus,
                                List.of("QUALIFIED", "CONCESSION", "RELEASED")))
                .and(valid -> valid.isNull(WmsLiveStockEntity::getMatValidEndDate)
                        .or().ge(WmsLiveStockEntity::getMatValidEndDate, java.time.LocalDate.now()))
                .groupBy(WmsLiveStockEntity::getMatCode, WmsLiveStockEntity::getMatName,
                        WmsLiveStockEntity::getMatBatch, WmsLiveStockEntity::getMatSpec,
                        WmsLiveStockEntity::getSupplierCode, WmsLiveStockEntity::getContainerCode,
                        WmsLiveStockEntity::getSiteCode, WmsLiveStockEntity::getSiteName,
                        WmsLiveStockEntity::getMatUnit, WmsLiveStockEntity::getQualityResult)
                .orderByAsc(WmsLiveStockEntity::getMatBatch, WmsLiveStockEntity::getSupplierCode,
                        WmsLiveStockEntity::getSiteCode, WmsLiveStockEntity::getContainerCode)
                .gt(WmsLiveStockEntity::getMatNum, 0);
        Page<WmsLiveStockEntity> result = this.page(page, wrapper);
        // 给物料明细赋值通知单明细id
        if (wmsLiveStock.getNoticeId() != null) {
            for (WmsLiveStockEntity record : result.getRecords()) {
                record.setNoticeDetailId(idMap.get(record.getMatCode()));
            }
        }
        return result;
    }

    LambdaQueryWrapper<WmsLiveStockEntity> exactOutboundQuery(WmsLiveStockQueryDTO query) {
        return getDetailListQueryWrapper(query)
                .eq(WmsLiveStockEntity::getStatus, YesNoEnum.NO.getCode())
                .gt(WmsLiveStockEntity::getMatNum, 0)
                .and(quality -> quality.in(WmsLiveStockEntity::getQualityResult,
                                List.of("QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE", "CONCESSION"))
                        .or(noQuality -> noQuality
                                .and(required -> required.isNull(WmsLiveStockEntity::getIsQualityTest)
                                        .or().eq(WmsLiveStockEntity::getIsQualityTest, "")
                                        .or().eq(WmsLiveStockEntity::getIsQualityTest, "0"))
                                .and(result -> result.isNull(WmsLiveStockEntity::getQualityResult)
                                        .or().eq(WmsLiveStockEntity::getQualityResult, ""))))
                .and(status -> status.isNull(WmsLiveStockEntity::getQualityStatus)
                        .or().eq(WmsLiveStockEntity::getQualityStatus, "")
                        .or().in(WmsLiveStockEntity::getQualityStatus,
                                List.of("QUALIFIED", "CONCESSION", "RELEASED")))
                .and(valid -> valid.isNull(WmsLiveStockEntity::getMatValidEndDate)
                        .or().ge(WmsLiveStockEntity::getMatValidEndDate, java.time.LocalDate.now()));
    }

    /**
     * 查询扫码范围的实物量和扣除活动预留后的可移动量。
     * 结果只代表读取时点，正式移动时仍按锁定库存重新校验。
     *
     * @param params 源仓、库位和物料条件
     */
    @Override
    public WmsLiveStockEntity getSumByParams(WmsLiveStockQuerySumDTO params) {
        requireScopedWarehouse(params.getWarehouseCode());
        // 移动端按扫码结果取实物量时，不能将不同批次、容器、单位或货权合成一笔作业。
        List<String> identityColumns = List.of("warehouse_code", "site_code", "mat_code", "supplier_code",
                "mat_batch", "container_code", "mat_unit", "stock_owner_type", "stock_owner_code",
                "source_business_no", "serial_managed");
        QueryWrapper<WmsLiveStockEntity> wrapper = new QueryWrapper<>();
        wrapper.select("mat_unit", "stock_owner_type", "stock_owner_code", "source_business_no",
                "serial_managed", "sum(mat_num) as mat_num");
        wrapper.lambda().eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsLiveStockEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(WmsLiveStockEntity::getWarehouseCode, params.getWarehouseCode())
                .eq(WmsLiveStockEntity::getSiteCode, params.getSiteCode())
                .eq(WmsLiveStockEntity::getMatCode, params.getMatCode())
                .eq(WmsLiveStockEntity::getStatus, YesNoEnum.NO.getCode())
                .gt(WmsLiveStockEntity::getMatNum, 0)
                .eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsLiveStockEntity::getSupplierCode,
                        params.getSupplierCode())
                .eq(StrUtil.isNotBlank(params.getMatBatch()), WmsLiveStockEntity::getMatBatch, params.getMatBatch())
                .eq(StrUtil.isNotBlank(params.getContainerCode()), WmsLiveStockEntity::getContainerCode,
                        params.getContainerCode());
        wrapper.groupBy(identityColumns).last("LIMIT 2");
        List<WmsLiveStockEntity> list = this.list(wrapper);
        WmsLiveStockEntity res = new WmsLiveStockEntity();
        BeanUtil.copyProperties(params, res);
        if (list.isEmpty()) {
            res.setMatNum(0d);
            res.setOperationAvailableNum(BigDecimal.ZERO);
            return res;
        }
        List<WmsLiveStockEntity> stockRows = this.list(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .select(WmsLiveStockEntity::getId, WmsLiveStockEntity::getTenantId,
                        WmsLiveStockEntity::getFactoryCode, WmsLiveStockEntity::getWarehouseCode,
                        WmsLiveStockEntity::getWarehouseName, WmsLiveStockEntity::getSiteCode,
                        WmsLiveStockEntity::getSiteName, WmsLiveStockEntity::getMatCode,
                        WmsLiveStockEntity::getMatName, WmsLiveStockEntity::getMatBatch,
                        WmsLiveStockEntity::getSupplierCode, WmsLiveStockEntity::getSupplierName,
                        WmsLiveStockEntity::getContainerCode, WmsLiveStockEntity::getMatUnit,
                        WmsLiveStockEntity::getMatNum, WmsLiveStockEntity::getStockOwnerType,
                        WmsLiveStockEntity::getStockOwnerCode, WmsLiveStockEntity::getSourceBusinessNo,
                        WmsLiveStockEntity::getSerialManaged, WmsLiveStockEntity::getMatBarcode)
                .eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsLiveStockEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(WmsLiveStockEntity::getWarehouseCode, params.getWarehouseCode())
                .eq(WmsLiveStockEntity::getSiteCode, params.getSiteCode())
                .eq(WmsLiveStockEntity::getMatCode, params.getMatCode())
                .eq(WmsLiveStockEntity::getStatus, YesNoEnum.NO.getCode())
                .gt(WmsLiveStockEntity::getMatNum, 0)
                .eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsLiveStockEntity::getSupplierCode,
                        params.getSupplierCode())
                .eq(StrUtil.isNotBlank(params.getMatBatch()), WmsLiveStockEntity::getMatBatch, params.getMatBatch())
                .eq(StrUtil.isNotBlank(params.getContainerCode()), WmsLiveStockEntity::getContainerCode,
                        params.getContainerCode())
                .orderByDesc(WmsLiveStockEntity::getInTime)
                .orderByDesc(WmsLiveStockEntity::getId));
        if (stockRows.isEmpty()) {
            throw new CheckedException("扫码期间源库存发生变化，请重新读取");
        }
        boolean serialManaged = stockRows.stream().anyMatch(stock -> Boolean.TRUE.equals(stock.getSerialManaged()));
        if (serialManaged) {
            String scannedBarcode = StrUtil.trim(params.getMatBarcode());
            if (StrUtil.isBlank(scannedBarcode)) {
                throw new CheckedException("单件序列号库存必须扫描精确实物条码");
            }
            stockRows = stockRows.stream().filter(stock -> scannedBarcode.equals(stock.getMatBarcode())).toList();
            if (stockRows.size() != 1) {
                throw new CheckedException("实物条码未唯一匹配当前源库存，请核对仓库、库位和条码");
            }
            WmsSerialStockGuard.requireWhole(stockRows.get(0), BigDecimal.ONE);
        } else if (list.size() > 1) {
            throw new CheckedException("当前物料库位存在不同批次、容器、单位、货权或来源，不能合并为一笔扫码作业");
        }
        requireConsistentMovementIdentity(stockRows);
        if (serialManaged && !Boolean.TRUE.equals(stockRows.get(0).getSerialManaged())) {
            throw new CheckedException("实物条码匹配到非单件库存，请重新读取");
        }
        Set<Long> stockIds = stockRows.stream().map(WmsLiveStockEntity::getId).collect(Collectors.toSet());
        if (stockIds.contains(null) || stockIds.size() != stockRows.size()) {
            throw new CheckedException("扫码范围库存行标识无效");
        }
        Map<Long, BigDecimal> productionReserved = wmsMaterialReservationService.activeReservedNums(stockIds);
        Map<Long, BigDecimal> salesReserved = wmsSalesStockReservationService.activeReservedNums(stockIds);
        if (productionReserved == null || salesReserved == null) {
            throw new CheckedException("预留状态读取失败，请重新读取库存");
        }
        BigDecimal physical = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        for (WmsLiveStockEntity stock : stockRows) {
            BigDecimal quantity = BigDecimal.valueOf(stock.getMatNum());
            BigDecimal production = productionReserved.getOrDefault(stock.getId(), BigDecimal.ZERO);
            BigDecimal sales = salesReserved.getOrDefault(stock.getId(), BigDecimal.ZERO);
            if (production == null || sales == null || production.signum() < 0 || sales.signum() < 0) {
                throw new CheckedException("预留数量无效，请重新读取库存");
            }
            physical = physical.add(quantity);
            available = available.add(quantity.subtract(production).subtract(sales).max(BigDecimal.ZERO));
        }
        if (!Double.isFinite(physical.doubleValue())) {
            throw new CheckedException("扫码范围库存汇总数量无效");
        }
        WmsLiveStockEntity first = stockRows.get(0);
        res.setMatName(first.getMatName());
        res.setSupplierName(StrUtil.isNotBlank(params.getSupplierCode()) ? first.getSupplierName() : null);
        res.setMatUnit(first.getMatUnit());
        res.setWarehouseName(first.getWarehouseName());
        res.setSiteName(first.getSiteName());
        res.setMatNum(physical.doubleValue());
        res.setOperationAvailableNum(available);
        res.setStockOwnerType(first.getStockOwnerType());
        res.setStockOwnerCode(first.getStockOwnerCode());
        res.setSourceBusinessNo(first.getSourceBusinessNo());
        res.setSerialManaged(serialManaged);
        if (serialManaged) {
            res.setMatBarcode(first.getMatBarcode());
            res.setSourceStockId(String.valueOf(first.getId()));
        } else {
            res.setMatBarcode(null);
        }
        return res;
    }

    private void requireScopedWarehouse(String warehouseCode) {
        if (StrUtil.isBlank(warehouseCode) || warehouseCode.contains("*")) {
            throw new CheckedException("查询库存须先指定有效仓库");
        }
        if (TenantContextHolder.getTenantId() == null
                || StrUtil.isBlank(FactoryContextHolder.getFactoryCode())
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("库存查询必须具有租户和工厂上下文");
        }
    }

    /**
     * 容器解除占用 如果还有其他库存使用这个 (目前使用历史 同容器同物料是一条记录)
     *
     * @param liveStock 要删除的实时库存
     */
    public void unUsedContainer(WmsLiveStockEntity liveStock) {
        if (liveStock == null || StrUtil.isBlank(liveStock.getContainerCode())) {
            return;
        }
        // 是否还有别的库存明细使用这个容器，如果有，不需要修改状态
        List<WmsLiveStockEntity> liveStocks = baseMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getContainerCode, liveStock.getContainerCode()));
        if (liveStocks.size() == 0) {
            wmsContainerBarcodeMapper.update(new LambdaUpdateWrapper<WmsContainerBarcodeEntity>()
                    .set(WmsContainerBarcodeEntity::getIsUsed, YesNoEnum.NO.getCode())
                    .eq(WmsContainerBarcodeEntity::getCode, liveStock.getContainerCode()));
            // 使用历史设置结束时间
            wmsContainerUseHistoryMapper.update(new LambdaUpdateWrapper<WmsContainerUseHistoryEntity>()
                    .set(WmsContainerUseHistoryEntity::getEndTime, LocalDateTime.now())
                    .eq(WmsContainerUseHistoryEntity::getCode, liveStock.getContainerCode()));
        } else {
            // 是否有这个物料， 如果有，使用历史不需要设置结束时间
            List<WmsLiveStockEntity> matLiveStocks = liveStocks.stream()
                    .filter(it -> StrUtil.equals(it.getMatCode(), liveStock.getMatCode())).toList();
            if (matLiveStocks.size() == 0) {
                // 使用历史设置结束时间
                wmsContainerUseHistoryMapper.update(new LambdaUpdateWrapper<WmsContainerUseHistoryEntity>()
                        .set(WmsContainerUseHistoryEntity::getEndTime, LocalDateTime.now())
                        .eq(WmsContainerUseHistoryEntity::getCode, liveStock.getContainerCode())
                        .eq(WmsContainerUseHistoryEntity::getMatCode, liveStock.getMatCode()));
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
        List<WmsLiveStockEntity> stocks = this.list(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(StrUtil.isNotBlank(warehouseCode), WmsLiveStockEntity::getWarehouseCode, warehouseCode)
                .in(WmsLiveStockEntity::getMatCode, productCodeSet));
        Map<Long, BigDecimal> reservedByStock = wmsMaterialReservationService.activeReservedNums(
                stocks.stream().map(WmsLiveStockEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, BigDecimal> salesReservedByStock = wmsSalesStockReservationService.activeReservedNums(
                stocks.stream().map(WmsLiveStockEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet()));
        stocks.forEach(stock -> stock.setPlanAvailableNum(BigDecimal
                .valueOf(Optional.ofNullable(stock.getMatNum()).orElse(0D))
                .subtract(reservedByStock.getOrDefault(stock.getId(), BigDecimal.ZERO))
                .subtract(salesReservedByStock.getOrDefault(stock.getId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO)));
        return stocks;
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
    @Transactional(rollbackFor = Exception.class)
    public R importExcel(List<WmsLiveStockImportExcelVO> excelVOS, BindingResult bindingResult, String warehouseCode,
                         String factoryCode) {
        // 通用校验获取失败的数据
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
                entity.setMatValidEndDate(
                        excelVO.getMatProduceDate().plus(excelVO.getMatValidPeriod(), ChronoUnit.DAYS));
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
            R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(warehouseCode);
            MdmWarehouseEntity warehouse = response == null ? null : response.getData();
            if (warehouse == null || StrUtil.isBlank(warehouse.getWarehouseName())) {
                throw new CheckedException("导入仓库不存在或不可用");
            }
            entities.forEach(it -> it.setWarehouseCode(warehouseCode).setWarehouseName(warehouse.getWarehouseName()));
        }
        if (CollUtil.isEmpty(errorMessageList)) {
            for (WmsLiveStockEntity stock : entities) {
                WmsInBillDetailEntity detail = new WmsInBillDetailEntity();
                BeanUtil.copyProperties(stock, detail);
                stock.setSerialManaged(identifierPolicyService.validateInboundForPosting(
                        InBillTypeEnum.INIT_IMPORT.getCode(), detail));
                stock.setMatBatch(detail.getMatBatch());
                stock.setMatBarcode(detail.getMatBarcode());
            }
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
        wrapper.lambda()
                .in(ObjUtil.isNotEmpty(temporaryPlanQueryDTO.getMatCodes()), WmsLiveStockEntity::getMatCode,
                        temporaryPlanQueryDTO.getMatCodes())
                .like(StrUtil.isNotBlank(temporaryPlanQueryDTO.getMatName()), WmsLiveStockEntity::getSupplierCode,
                        temporaryPlanQueryDTO.getMatName())
                .eq(WmsLiveStockEntity::getStockOwnerType, WmsStockOwnership.OWNED)
                .groupBy(WmsLiveStockEntity::getWarehouseCode, WmsLiveStockEntity::getMatCode);
        List<WmsLiveStockEntity> list = this.list(wrapper);
        // 使用Map进行分组
        List<Map<String, Object>> resultMap = list.stream()
                .collect(Collectors.groupingBy(WmsLiveStockEntity::getMatCode, Collectors.toMap(
                        WmsLiveStockEntity::getWarehouseCode,
                        WmsLiveStockEntity::getMatNum)))
                .entrySet().stream().map(entry -> {
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
        Set<String> allowedGroupFields = Set.of("warehouse_code", "mat_code", "site_code",
                "mat_batch", "supplier_code");
        if (CollUtil.isNotEmpty(queryDTO.getGroupFields())
                && queryDTO.getGroupFields().stream()
                .anyMatch(field -> field == null || !allowedGroupFields.contains(field))) {
            throw new CheckedException("库存分组字段不受支持");
        }

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
                .eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsLiveStockEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
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
