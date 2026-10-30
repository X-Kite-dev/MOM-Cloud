package com.kitex.mom.wms.service.outNotice;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.util.WmsQuantityMath;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsStockOwnership;
import com.kitex.mom.wms.vo.WmsOutNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutNoticeExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 出库通知单处理器抽象实现 提供通用的业务逻辑实现，子类可以通过继承该类来实现特定类型的出库通知单处理
 *
 * @author Cascade
 * @since 2024-12-16
 */
public abstract class AbstractWmsOutNoticeHandler
        implements WmsOutNoticeHandler<WmsOutNoticeEntity, WmsOutNoticeDetailEntity> {

    @Autowired
    private WmsOutNoticeMapper wmsOutNoticeMapper;

    @Autowired
    private WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;

    @Autowired
    private WmsLiveStockMapper wmsLiveStockMapper;

    @Autowired
    private WmsOutBillMapper wmsOutBillMapper;

    @Autowired
    private RemoteMaterialService remoteMaterialService;

    @Autowired
    private WmsMaterialReservationService materialReservationService;

    @Autowired
    private WmsSalesStockReservationService salesStockReservationService;

    @Autowired
    private WmsWarehouseBusinessGuard warehouseBusinessGuard;

    @Override
    public List<WmsOutNoticeEntity> getNoticeList(List<Long> ids) {
        List<WmsOutNoticeEntity> wmsOutNoticeEntities = wmsOutNoticeMapper.selectByIds(ids);

        List<WmsOutNoticeDetailEntity> wmsOutNoticeDetailEntities = wmsOutNoticeDetailMapper
                .selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
                        .in(WmsOutNoticeDetailEntity::getNoticeId, ids));
        Map<Long, List<WmsOutNoticeDetailEntity>> detailsMap = wmsOutNoticeDetailEntities.stream()
                .collect(Collectors.groupingBy(WmsOutNoticeDetailEntity::getNoticeId, Collectors.toList()));
        wmsOutNoticeEntities.forEach(item -> {
            List<WmsOutNoticeDetailEntity> detailList = detailsMap.get(item.getId());
            if (CollUtil.isNotEmpty(detailList)) {
                item.setDetailList(detailList);
            }
        });
        return wmsOutNoticeEntities;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(WmsOutNoticeEntity wmsOutNotice) {
        // 1. 校验数据完整性
        checkData(wmsOutNotice);

        // 2. 校验仓库是否支持该业务
        checkWarehouse(wmsOutNotice.getWarehouseCode(), wmsOutNotice.getBillType());
        wmsOutNotice.setFactoryCode(FactoryContextHolder.getFactoryCode());
        wmsOutNotice.setTenantId(TenantContextHolder.getTenantId());
        validateExactStockSelections(wmsOutNotice, wmsOutNotice.getTenantId(), wmsOutNotice.getFactoryCode());

        // 3. 设置默认状态 和 数量
        setDefaultState(wmsOutNotice);
        wmsOutNotice.setId(null);
        // 设置初始数量
        List<WmsOutNoticeDetailEntity> details = wmsOutNotice.getDetailList();
        double sum = validatedNoticeTotal(details);
        wmsOutNotice.setNoticeOutNum(sum);
        wmsOutNotice.setAllNotOutNum(sum);
        wmsOutNotice.setAllOutNum(0.0);

        // 4. 保存主表数据
        int result = wmsOutNoticeMapper.insert(wmsOutNotice);
        if (result <= 0) {
            throw new CheckedException("保存出库通知单失败");
        }

        // 5. 保存明细数据
        if (CollUtil.isNotEmpty(details)) {
            // 补齐明细归属和初始数量。
            for (WmsOutNoticeDetailEntity detail : details) {
                detail.setId(null);
                detail.setNoticeId(wmsOutNotice.getId());
                detail.setWorkshop(wmsOutNotice.getWorkshop());
                detail.setNotOutNum(detail.getNoticeOutNum());
                detail.setOutNum(0.0);
                detail.setWarehouseCode(wmsOutNotice.getWarehouseCode());
                detail.setWarehouseName(wmsOutNotice.getWarehouseName());
                detail.setFactoryCode(wmsOutNotice.getFactoryCode());
                detail.setTenantId(wmsOutNotice.getTenantId());
            }
            for (WmsOutNoticeDetailEntity detail : details) {
                if (wmsOutNoticeDetailMapper.insert(detail) != 1) {
                    throw new CheckedException("保存出库通知单明细失败");
                }
            }
        }

        return Boolean.TRUE;
    }

    @Override
    public void setDefaultState(WmsOutNoticeEntity wmsOutNotice) {
        // 默认状态为待审核
        wmsOutNotice.setState(InOutNoticeStateEnum.READY.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(WmsOutNoticeEntity wmsOutNotice) {
        // 1. 校验数据完整性
        Assert.notNull(wmsOutNotice, "出库通知单不能为空");
        Assert.notNull(wmsOutNotice.getId(), "出库通知单ID不能为空");
        checkData(wmsOutNotice);

        // 2. 校验当前状态是否允许修改
        wmsOutNoticeMapper.lockNotice(wmsOutNotice.getId());
        WmsOutNoticeEntity existingNotice = wmsOutNoticeMapper.selectById(wmsOutNotice.getId());
        Assert.notNull(existingNotice, "出库通知单不存在");

        NoticeStateEnum currentState = NoticeStateEnum.getByCode(existingNotice.getState());
        Assert.notNull(currentState, "出库通知单状态无效");
        Assert.isTrue(currentState.isEditable(), "当前状态不允许修改");
        Assert.isTrue(existingNotice.getAllOutNum() == null || existingNotice.getAllOutNum() == 0D,
                "通知单已开始出库，不能修改");
        if (existingNotice.getDocumentId() != null) {
            throw new CheckedException("来源单据生成的出库通知单须由来源业务调整");
        }
        Assert.isTrue(Objects.equals(existingNotice.getBillType(), wmsOutNotice.getBillType()),
                "通知单业务类型不能修改");
        checkWarehouse(wmsOutNotice.getWarehouseCode(), wmsOutNotice.getBillType());
        Assert.isTrue(wmsOutBillMapper.selectCount(Wrappers.<WmsOutBillEntity>lambdaQuery()
                        .eq(WmsOutBillEntity::getNoticeId, existingNotice.getId())) == 0,
                "通知单已关联出库单，不能修改");
        validateExactStockSelections(wmsOutNotice, existingNotice.getTenantId(), existingNotice.getFactoryCode());

        double total = validatedNoticeTotal(wmsOutNotice.getDetailList());
        existingNotice.setWarehouseCode(wmsOutNotice.getWarehouseCode());
        existingNotice.setWarehouseName(wmsOutNotice.getWarehouseName());
        existingNotice.setWorkshop(wmsOutNotice.getWorkshop());
        existingNotice.setRemark(wmsOutNotice.getRemark());
        existingNotice.setNoticeOutNum(total);
        existingNotice.setAllNotOutNum(total);
        int result = wmsOutNoticeMapper.updateById(existingNotice);
        if (result <= 0) {
            throw new CheckedException("更新出库通知单失败");
        }

        return replaceDetails(existingNotice, wmsOutNotice.getDetailList());
    }

    @Override
    public Page<WmsOutNoticeEntity> page(Page page, QueryWrapper<WmsOutNoticeEntity> wrapper) {
        return wmsOutNoticeMapper.selectPage(page, wrapper);
    }

    @Override
    public Map<String, Long> getCount(QueryWrapper<WmsOutNoticeEntity> wrapper) {
        return wmsOutNoticeMapper.selectList(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsOutNoticeEntity::getState, Collectors.counting()));
    }

    @Override
    public Page<WmsOutNoticeDetailEntity> pageDetails(Page page, WmsInNoticeDetailQueryDTO params) {
        return wmsOutNoticeDetailMapper.fetchDetails(page, params, null);
    }

    @Override
    public WmsOutNoticeEntity selectById(Long id) {
        // 1. 查询主表数据
        WmsOutNoticeEntity notice = wmsOutNoticeMapper.selectById(id);
        if (notice == null) {
            return null;
        }

        // 2. 查询明细数据
        List<WmsOutNoticeDetailEntity> details = wmsOutNoticeDetailMapper
                .selectList(
                        Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery().eq(WmsOutNoticeDetailEntity::getNoticeId, id));
        notice.setDetailList(details);

        return notice;
    }

    /**
     * 根据通知单id查询实时库存
     */
    @Override
    public WmsOutBillEntity getLiveStockByNoticeId(Long id) {
        WmsOutNoticeEntity notice = Optional.ofNullable(selectById(id))
                .orElseThrow(() -> new CheckedException("出库通知单查找失败，请确认单号！"));
        List<WmsOutNoticeDetailEntity> detailList = notice.getDetailList();
        List<WmsOutBillDetailEntity> billDetails = new ArrayList<>();
        Map<Long, BigDecimal> recommendedFreeByStock = new HashMap<>();
        Map<String, Map<Long, BigDecimal>> recommendedReservedByNo = new HashMap<>();
        for (WmsOutNoticeDetailEntity detail : detailList) {
            Double requested = detail.getNotOutNum();
            if (requested == null || !Double.isFinite(requested) || requested < 0) {
                throw new CheckedException("通知未出库数量无效，无法推荐库存");
            }
            if (requested == 0) {
                // 这个物料明细完成了就不要查了
                continue;
            }
            List<WmsLiveStockEntity> liveStocks;
            if (StrUtil.isNotBlank(detail.getReservationNo())) {
                List<WmsLiveStockEntity> reservedStocks;
                if (StrUtil.startWith(detail.getReservationNo(), WmsSalesStockReservationService.PREFIX)) {
                    reservedStocks = salesStockReservationService.availableReservedStocks(
                            detail.getReservationNo(), detail.getMatCode(), detail.getWarehouseCode());
                } else {
                    reservedStocks = materialReservationService.availableReservedStocks(
                            detail.getReservationNo(), detail.getMatCode(), detail.getWarehouseCode());
                }
                liveStocks = reservedStocks.stream()
                        .filter(stock -> matchesOrdinaryNoticeScope(notice, detail, stock)
                                && (detail.getSourceStockId() == null
                                || Objects.equals(detail.getSourceStockId(), stock.getId())))
                        .toList();
            } else if (detail.getSourceStockId() != null) {
                WmsLiveStockEntity exact = wmsLiveStockMapper.selectById(detail.getSourceStockId());
                if (exact == null || !Objects.equals(exact.getWarehouseCode(), detail.getWarehouseCode())
                        || !Objects.equals(exact.getMatCode(), detail.getMatCode())
                        || !Objects.equals(exact.getTenantId(), notice.getTenantId())
                        || !Objects.equals(exact.getFactoryCode(), notice.getFactoryCode())
                        || !Objects.equals(exact.getTenantId(), detail.getTenantId())
                        || !Objects.equals(exact.getFactoryCode(), detail.getFactoryCode())
                        || !WmsStockAvailability.isPhysicallyUsable(exact)) {
                    throw new CheckedException("物料[" + detail.getMatCode() + "]冻结库存行已不存在或不再可用");
                }
                WmsOutBillDetailEntity identity = new WmsOutBillDetailEntity();
                identity.setStockOwnerType(detail.getStockOwnerType());
                identity.setStockOwnerCode(detail.getStockOwnerCode());
                identity.setSourceStockId(detail.getSourceStockId());
                WmsStockOwnership.validateOutbound(notice.getBillType(), identity, exact);
                BigDecimal available = BigDecimal.valueOf(Optional.ofNullable(exact.getMatNum()).orElse(0d))
                        .subtract(materialReservationService.activeReservedNum(exact.getId()))
                        .subtract(salesStockReservationService.activeReservedNums(Set.of(exact.getId()))
                                .getOrDefault(exact.getId(), BigDecimal.ZERO))
                        .max(BigDecimal.ZERO);
                exact.setMatNum(available.doubleValue());
                liveStocks = available.signum() > 0 ? List.of(exact) : List.of();
            } else {
                MdmMaterialEntity mdmMaterial = remoteMaterialService.getDetails(detail.getMatCode()).getData();
                String orderByField = Optional.ofNullable(mdmMaterial)
                        .map(MdmMaterialEntity::getFifo)
                        .filter(Boolean.TRUE::equals)
                        .map(fifo -> mdmMaterial.getFifoField())
                        .orElse("in_time");
                liveStocks = wmsLiveStockMapper.selectList(new QueryWrapper<WmsLiveStockEntity>()
                        .eq("warehouse_code", detail.getWarehouseCode())
                        .eq("mat_code", detail.getMatCode())
                        .eq("status", YesNoEnum.NO.getCode())
                        .gt("mat_num", 0)
                        .orderByAsc(orderByField));
                liveStocks = liveStocks.stream()
                        .filter(stock -> matchesOrdinaryNoticeScope(notice, detail, stock)
                                && WmsStockAvailability.isUsable(stock)).toList();
                Set<Long> stockIds = liveStocks.stream().map(WmsLiveStockEntity::getId).collect(Collectors.toSet());
                Map<Long, BigDecimal> salesReserved = Optional.ofNullable(
                        salesStockReservationService.activeReservedNums(stockIds)).orElseGet(Map::of);
                liveStocks.forEach(stock -> stock.setMatNum(BigDecimal.valueOf(stock.getMatNum())
                        .subtract(materialReservationService.activeReservedNum(stock.getId()))
                        .subtract(salesReserved.getOrDefault(stock.getId(), BigDecimal.ZERO))
                        .max(BigDecimal.ZERO).doubleValue()));
                liveStocks = liveStocks.stream().filter(stock -> stock.getMatNum() > 0).toList();
            }
            // 正式预留与未预留余额是不同份额；同一预留号内部仍须防止重复推荐。
            Map<Long, BigDecimal> recommendedByStock = StrUtil.isBlank(detail.getReservationNo())
                    ? recommendedFreeByStock
                    : recommendedReservedByNo.computeIfAbsent(detail.getReservationNo(), ignored -> new HashMap<>());
            BigDecimal remaining = BigDecimal.valueOf(requested);
            // 进行推荐物料
            for (WmsLiveStockEntity liveStock : liveStocks) {
                if (liveStock.getId() == null || liveStock.getMatNum() == null
                        || !Double.isFinite(liveStock.getMatNum()) || liveStock.getMatNum() < 0) {
                    throw new CheckedException("候选库存数量或身份无效，无法推荐出库");
                }
                BigDecimal available = BigDecimal.valueOf(liveStock.getMatNum())
                        .subtract(recommendedByStock.getOrDefault(liveStock.getId(), BigDecimal.ZERO))
                        .max(BigDecimal.ZERO);
                BigDecimal take = remaining.min(available);
                if (take.signum() <= 0) {
                    continue;
                }
                WmsOutBillDetailEntity liveDetail = new WmsOutBillDetailEntity();
                BeanUtil.copyProperties(detail, liveDetail, "id");
                liveDetail.setSupplierCode(liveStock.getSupplierCode());
                liveDetail.setSupplierName(liveStock.getSupplierName());
                liveDetail.setMatBatch(liveStock.getMatBatch());
                liveDetail.setMatSpec(liveStock.getMatSpec());
                liveDetail.setContainerCode(liveStock.getContainerCode());
                liveDetail.setSiteCode(liveStock.getSiteCode());
                liveDetail.setSiteName(liveStock.getSiteName());
                liveDetail.setSourceStockId(liveStock.getId());
                liveDetail.setStockOwnerType(
                        StrUtil.blankToDefault(liveStock.getStockOwnerType(), WmsStockOwnership.OWNED));
                liveDetail.setStockOwnerCode(liveStock.getStockOwnerCode());
                liveDetail.setSourceBusinessNo(liveStock.getSourceBusinessNo());
                // 当前库存数量
                liveDetail.setMaxOutNum(available.doubleValue());
                liveDetail.setMatNum(take.doubleValue());
                liveDetail.setOperateUnit(liveDetail.getMatUnit());
                liveDetail.setOperateNum(liveDetail.getMatNum());
                liveDetail.setNoticeDetailId(detail.getId());
                billDetails.add(liveDetail);
                recommendedByStock.merge(liveStock.getId(), take, BigDecimal::add);
                remaining = remaining.subtract(take);
                if (remaining.signum() == 0) {
                    break;
                }
            }
            if (remaining.signum() > 0) {
                String source = StrUtil.isNotBlank(detail.getReservationNo())
                        ? "预留[" + detail.getReservationNo() + "]余额"
                        : "未预留可用库存";
                throw new CheckedException("物料[" + detail.getMatCode() + "]" + source + "不足，无法完整生成出库单");
            }
            // todo 查询供应商名称
            // todo 查询库位名称
        }
        WmsOutBillEntity outBill = new WmsOutBillEntity();
        BeanUtil.copyProperties(notice, outBill, "id");
        outBill.setNoticeId(id);
        outBill.setDetails(billDetails);
        return outBill;
    }

    private static boolean matchesOrdinaryNoticeScope(WmsOutNoticeEntity notice,
                                                       WmsOutNoticeDetailEntity detail, WmsLiveStockEntity stock) {
        return stock != null && Objects.equals(notice.getTenantId(), detail.getTenantId())
                && Objects.equals(notice.getFactoryCode(), detail.getFactoryCode())
                && Objects.equals(notice.getWarehouseCode(), detail.getWarehouseCode())
                && Objects.equals(stock.getTenantId(), notice.getTenantId())
                && Objects.equals(stock.getFactoryCode(), notice.getFactoryCode())
                && Objects.equals(stock.getWarehouseCode(), notice.getWarehouseCode())
                && Objects.equals(stock.getMatCode(), detail.getMatCode())
                && WmsStockOwnership.isCompanyOwned(stock);
    }

    /**
     * 根据通知单id查询实时库存-移动端
     */
    @Override
    public WmsOutBillEntity getLiveStockByNoticeIdApp(Long id) {
        WmsOutBillEntity res = getLiveStockByNoticeId(id);
        List<WmsOutNoticeDetailEntity> details = wmsOutNoticeDetailMapper
                .selectList(
                        Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery().eq(WmsOutNoticeDetailEntity::getNoticeId, id));
        // 受控退供的通知明细指向唯一库存行，把实际批次/库位/容器返回PDA，禁止移动端仅按物料猜测。
        Map<Long, WmsOutBillDetailEntity> exactByNoticeDetail = Optional.ofNullable(res.getDetails())
                .orElseGet(List::of).stream().filter(row -> row.getSourceStockId() != null)
                .collect(Collectors.toMap(WmsOutBillDetailEntity::getNoticeDetailId, Function.identity(),
                        (left, right) -> left));
        details.forEach(detail -> {
            // 普通通知的推荐库存仍在details中，不把推荐身份伪装成数据库冻结身份。
            if (detail.getSourceStockId() == null) {
                return;
            }
            WmsOutBillDetailEntity exact = exactByNoticeDetail.get(detail.getId());
            if (exact == null) {
                return;
            }
            detail.setSourceStockId(exact.getSourceStockId());
            detail.setMatBatch(exact.getMatBatch());
            detail.setSupplierCode(exact.getSupplierCode());
            detail.setSupplierName(exact.getSupplierName());
            detail.setSiteCode(exact.getSiteCode());
            detail.setSiteName(exact.getSiteName());
            detail.setContainerCode(exact.getContainerCode());
        });
        res.setNoticeDetails(details);
        // 根据通知单号查询出库单号（app操作时需要用到）
        List<WmsOutBillEntity> outBills = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getNoticeId, id)
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
                .orderByDesc(BaseCore::getCreateTime)
                .last("limit 1"));
        if (outBills.size() > 0) {
            res.setBillNo(outBills.get(0).getBillNo());
        }
        return res;
    }

    @Override
    public void checkData(WmsOutNoticeEntity wmsOutNotice) {
        if (wmsOutNotice == null || CollUtil.isEmpty(wmsOutNotice.getDetailList())) {
            throw new CheckedException("明细数据不能为空");
        }
        if (StrUtil.isBlank(wmsOutNotice.getWarehouseCode()) || StrUtil.isBlank(wmsOutNotice.getBillType())) {
            throw new CheckedException("仓库编码和单据类型不能为空");
        }
        Assert.isTrue(
                OutBillTypeEnum.isValid(wmsOutNotice.getBillType())
                        || OtherOutBillTypeEnum.isValid(wmsOutNotice.getBillType()),
                "无效的单据类型：" + wmsOutNotice.getBillType());
        validatedNoticeTotal(wmsOutNotice.getDetailList());
        wmsOutNotice.getDetailList().forEach(detail ->
                WmsStockOwnership.validateOutboundNotice(wmsOutNotice.getBillType(), detail));
    }

    private double validatedNoticeTotal(List<WmsOutNoticeDetailEntity> details) {
        double total = 0D;
        for (WmsOutNoticeDetailEntity detail : details) {
            if (detail == null || StrUtil.isBlank(detail.getMatCode()) || StrUtil.isBlank(detail.getMatUnit())) {
                throw new CheckedException("出库明细物料和单位不能为空");
            }
            Double quantity = detail.getNoticeOutNum();
            if (quantity == null || !Double.isFinite(quantity) || quantity <= 0D) {
                throw new CheckedException("通知出库数量必须为正数");
            }
            total = WmsQuantityMath.add(total, quantity);
        }
        if (!Double.isFinite(total)) {
            throw new CheckedException("通知出库总数超出有效范围");
        }
        return total;
    }

    private void validateExactStockSelections(WmsOutNoticeEntity notice, Long tenantId, String factoryCode) {
        Map<Long, WmsLiveStockEntity> selected = new HashMap<>();
        Map<Long, BigDecimal> requested = new HashMap<>();
        for (WmsOutNoticeDetailEntity detail : notice.getDetailList()) {
            Long stockId = detail.getSourceStockId();
            if (stockId == null) {
                continue;
            }
            WmsLiveStockEntity stock = selected.computeIfAbsent(stockId, wmsLiveStockMapper::selectById);
            if (stock == null || !Objects.equals(stock.getTenantId(), tenantId)
                    || !Objects.equals(stock.getFactoryCode(), factoryCode)
                    || !Objects.equals(stock.getWarehouseCode(), notice.getWarehouseCode())
                    || !Objects.equals(stock.getMatCode(), detail.getMatCode())
                    || !Objects.equals(stock.getMatUnit(), detail.getMatUnit())
                    || !WmsStockAvailability.isPhysicallyUsable(stock)
                    || stock.getMatNum() == null || !Double.isFinite(stock.getMatNum())
                    || stock.getMatNum() <= 0D) {
                throw new CheckedException("库存行[" + stockId + "]与通知单不匹配或已不可用");
            }
            WmsOutBillDetailEntity identity = new WmsOutBillDetailEntity();
            identity.setStockOwnerType(detail.getStockOwnerType());
            identity.setStockOwnerCode(detail.getStockOwnerCode());
            identity.setSourceStockId(stockId);
            WmsStockOwnership.validateOutbound(notice.getBillType(), identity, stock);
            BigDecimal total = requested.merge(stockId, BigDecimal.valueOf(detail.getNoticeOutNum()), BigDecimal::add);
            if (total.compareTo(BigDecimal.valueOf(stock.getMatNum())) > 0) {
                throw new CheckedException("库存行[" + stockId + "]的通知数量超过现存数量");
            }
        }
    }

    @Override
    public Boolean updateState(Long id, String state) {
        throw new CheckedException("出库通知单状态须通过作废或作业完成等业务操作变更");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDetails(Long id, List<WmsOutNoticeDetailEntity> details) {
        Assert.notNull(id, "通知单ID不能为空");
        wmsOutNoticeMapper.lockNotice(id);
        WmsOutNoticeEntity notice = wmsOutNoticeMapper.selectById(id);
        Assert.notNull(notice, "出库通知单不存在");
        WmsOutNoticeEntity input = new WmsOutNoticeEntity();
        input.setId(id);
        input.setBillType(notice.getBillType());
        input.setWarehouseCode(notice.getWarehouseCode());
        input.setWarehouseName(notice.getWarehouseName());
        input.setWorkshop(notice.getWorkshop());
        input.setRemark(notice.getRemark());
        input.setDetailList(details);
        return updateDeep(input);
    }

    private Boolean replaceDetails(WmsOutNoticeEntity notice, List<WmsOutNoticeDetailEntity> details) {
        Long id = notice.getId();
        List<WmsOutNoticeDetailEntity> replacements = new ArrayList<>(details.size());
        for (WmsOutNoticeDetailEntity detail : details) {
            Assert.notNull(detail, "出库明细不能为空");
            detail.setId(null);
            detail.setNoticeId(id);
            detail.setTenantId(notice.getTenantId());
            detail.setFactoryCode(notice.getFactoryCode());
            detail.setWarehouseCode(notice.getWarehouseCode());
            detail.setWarehouseName(notice.getWarehouseName());
            detail.setOutNum(0D);
            detail.setNotOutNum(detail.getNoticeOutNum());
            replacements.add(detail);
        }
        wmsOutNoticeDetailMapper.delete(Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery()
                .eq(WmsOutNoticeDetailEntity::getNoticeId, id));
        for (WmsOutNoticeDetailEntity replacement : replacements) {
            if (wmsOutNoticeDetailMapper.insert(replacement) != 1) {
                throw new CheckedException("保存出库通知单明细失败");
            }
        }

        return Boolean.TRUE;
    }

    @Override
    public Boolean checkWarehouse(String warehouseCode, String billType) {
        return warehouseBusinessGuard.check(warehouseCode, billType, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(String[] ids) {
        if (ids == null || ids.length == 0) {
            return Boolean.TRUE;
        }
        List<Long> noticeIds = new ArrayList<>(ids.length);
        for (String id : ids) {
            if (StrUtil.isBlank(id) || !StrUtil.isNumeric(id)) {
                throw new CheckedException("出库通知单ID无效");
            }
            noticeIds.add(Long.valueOf(id));
        }
        noticeIds = noticeIds.stream().distinct().sorted().toList();
        List<WmsOutNoticeEntity> notices = wmsOutNoticeMapper.selectList(
                Wrappers.<WmsOutNoticeEntity>lambdaQuery().in(WmsOutNoticeEntity::getId, noticeIds)
                        .orderByAsc(WmsOutNoticeEntity::getId).last("FOR UPDATE"));
        if (notices.size() != noticeIds.size()) {
            throw new CheckedException("存在已删除或无权访问的出库通知单");
        }
        for (WmsOutNoticeEntity notice : notices) {
            NoticeStateEnum state = NoticeStateEnum.getByCode(notice.getState());
            Assert.notNull(state, "出库通知单状态无效");
            if (!state.isEditable() || (notice.getAllOutNum() != null && notice.getAllOutNum() != 0D)) {
                throw new CheckedException("已开始出库的通知单必须保留业务记录");
            }
            if (notice.getDocumentId() != null) {
                throw new CheckedException("来源单据生成的出库通知单请通过业务作废流程处理");
            }
        }
        if (wmsOutBillMapper.selectCount(Wrappers.<WmsOutBillEntity>lambdaQuery()
                .in(WmsOutBillEntity::getNoticeId, noticeIds)) > 0) {
            throw new CheckedException("通知单已关联出库单，不能删除");
        }
        wmsOutNoticeDetailMapper.delete(Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery()
                .in(WmsOutNoticeDetailEntity::getNoticeId, noticeIds));
        if (wmsOutNoticeMapper.deleteByIds(noticeIds) != noticeIds.size()) {
            throw new CheckedException("删除出库通知单失败");
        }
        return Boolean.TRUE;
    }

    /**
     * 作废单据
     *
     * @param id 通知单id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidObjs(Long id) {
        if (id == null) {
            throw new CheckedException("出库通知单ID不能为空");
        }
        wmsOutNoticeMapper.lockNotice(id);
        WmsOutNoticeEntity noticeEntity = Optional.ofNullable(wmsOutNoticeMapper.selectById(id))
                .orElseThrow(() -> new CheckedException("单据不存在！"));

        if (!InOutNoticeStateEnum.READY.getCode().equals(noticeEntity.getState())
                || (noticeEntity.getAllOutNum() != null && noticeEntity.getAllOutNum() != 0D)) {
            throw new CheckedException("出库通知单已开始处理或已作废，不能再次作废");
        }
        Long count = wmsOutBillMapper.selectCount(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getNoticeId, id));
        if (count > 0) {
            throw new CheckedException("出库通知单已关联出库单，请先处理关联单据");
        }

        if (wmsOutNoticeMapper.update(new LambdaUpdateWrapper<WmsOutNoticeEntity>()
                .set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.CANCELED.getCode())
                .eq(BaseEntity::getId, id)
                .eq(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.READY.getCode())) != 1) {
            throw new CheckedException("作废出库通知单失败");
        }
    }

    @Override
    public List<WmsOutNoticeEntity> outNoticeListByDuId(Long id) {
        // 根据业务单号查询通知单
        return wmsOutNoticeMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeEntity>()
                .eq(WmsOutNoticeEntity::getDocumentId, id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean outNoticeRemoveByDuId(Long id) {
        return voidObjByDocumentId(id, DocumentEnum.DELIVER.getCode());
    }

    /**
     * 查询主表列表
     */
    @Override
    public List<WmsOutNoticeExcelVO> list(QueryWrapper<WmsOutNoticeEntity> wrapper) {
        List<WmsOutNoticeEntity> list = wmsOutNoticeMapper.selectList(wrapper);
        List<WmsOutNoticeExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsOutNoticeExcelVO vo = new WmsOutNoticeExcelVO();
            WmsOutNoticeEntity entity = list.get(i);
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            vo.setState(InOutNoticeStateEnum.getByCode(entity.getState()).getName());
            result.add(vo);
        }
        return result;
    }

    /**
     * 查询明细列表
     *
     * @param params 查询条件
     * @param ids    指定ID
     */
    @Override
    public List<WmsOutNoticeDetailExcelVO> listDetails(WmsInNoticeDetailQueryDTO params, Long[] ids) {
        List<WmsOutNoticeDetailEntity> list = wmsOutNoticeDetailMapper.fetchDetails(params, ids);
        List<WmsOutNoticeDetailExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsOutNoticeDetailExcelVO vo = new WmsOutNoticeDetailExcelVO();
            WmsOutNoticeDetailEntity entity = list.get(i);
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            vo.setNoticeOutNum(entity.getNoticeOutNum() + entity.getMatUnit());
            vo.setOutNum(entity.getOutNum() + entity.getMatUnit());
            vo.setNotOutNum(entity.getNotOutNum() + entity.getMatUnit());
            vo.setState(InOutNoticeStateEnum.getByCode(entity.getState()).getName());
            result.add(vo);
        }
        return result;
    }

    /**
     * 作废生产领料出库通知单-通过业务单据
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean voidObjByDocumentId(Long documentId, String documentTable) {
        if (documentId == null || StrUtil.isBlank(documentTable)) {
            throw new CheckedException("来源单据不能为空");
        }
        List<WmsOutNoticeEntity> entityList = wmsOutNoticeMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeEntity>()
                .eq(WmsOutNoticeEntity::getDocumentId, documentId)
                .eq(WmsOutNoticeEntity::getDocumentTable, documentTable));
        if (entityList.isEmpty()) {
            return Boolean.TRUE;
        }
        List<Long> ids = entityList.stream().map(BaseEntity::getId).sorted().toList();
        List<Long> activeIds = new ArrayList<>(ids.size());
        for (Long id : ids) {
            wmsOutNoticeMapper.lockNotice(id);
            WmsOutNoticeEntity notice = wmsOutNoticeMapper.selectById(id);
            if (notice == null || !Objects.equals(documentId, notice.getDocumentId())
                    || !Objects.equals(documentTable, notice.getDocumentTable())) {
                throw new CheckedException("来源出库通知单已变更，不能作废");
            }
            if (InOutNoticeStateEnum.CANCELED.getCode().equals(notice.getState())) {
                continue;
            }
            if (!InOutNoticeStateEnum.READY.getCode().equals(notice.getState())
                    || (notice.getAllOutNum() != null && notice.getAllOutNum() != 0D)) {
                throw new CheckedException("来源出库通知单已发生出库，不能作废");
            }
            activeIds.add(id);
        }
        if (activeIds.isEmpty()) {
            return Boolean.TRUE;
        }
        List<WmsOutBillEntity> outBills = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .in(WmsOutBillEntity::getNoticeId, activeIds));
        boolean physicalOutOccurred = outBills.stream()
                .anyMatch(item -> InOutBillStateEnum.FINISHED.getCode().equals(item.getState())
                        || InOutBillStateEnum.PASSED.getCode().equals(item.getState()));
        if (physicalOutOccurred) {
            throw new CheckedException("领料已发生实物出库，不可撤回；请按生产退料流程处理");
        }
        if (!outBills.isEmpty()) {
            throw new CheckedException("领料通知已生成出库单，请先删除未执行的出库单后再撤回");
        }

        if (wmsOutNoticeMapper.update(new LambdaUpdateWrapper<WmsOutNoticeEntity>()
                .set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.CANCELED.getCode())
                .in(BaseEntity::getId, activeIds)
                .eq(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.READY.getCode())) != activeIds.size()) {
            throw new CheckedException("来源出库通知单作废失败");
        }
        return Boolean.TRUE;
    }
}
