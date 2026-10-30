package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsPickingAreaEntity;
import com.kitex.mom.wms.entity.WmsPickingEventEntity;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsPickingAreaMapper;
import com.kitex.mom.wms.mapper.WmsPickingEventMapper;
import com.kitex.mom.wms.service.WmsPickingAreaService;
import com.kitex.mom.wms.vo.WmsPickingAreaExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 拣货区
 *
 * @author kitex
 * @date 2025-01-21 15:38:39
 */
@Service
@RequiredArgsConstructor
public class WmsPickingAreaServiceImpl extends ServiceImpl<WmsPickingAreaMapper, WmsPickingAreaEntity>
        implements WmsPickingAreaService {

    private final WmsInoutFlowMapper wmsInoutFlowMapper;
    private final WmsPickingEventMapper pickingEventMapper;
    private final RemoteDictService remoteDictService;

    private static boolean positivePickingQuantity(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private static void requirePicking(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    /**
     * 将削减的库存放到拣货区，仅同一出库明细和同一来源库存行累计数量。
     *
     * @param stockOuted 削减的库存
     * @param outBill 出库单
     * @param outBillDetailId 本次出库明细ID
     * @return 是否全部接收
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByStockList(List<WmsLiveStockEntity> stockOuted, WmsOutBillEntity outBill,
                                     Long outBillDetailId) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        requirePicking(tenant != null && StrUtil.isNotBlank(factory) && outBill != null
                        && Objects.equals(tenant, outBill.getTenantId())
                        && Objects.equals(factory, outBill.getFactoryCode())
                        && outBill.getId() != null && outBill.getId() > 0
                        && StrUtil.isNotBlank(outBill.getBillNo())
                        && StrUtil.isNotBlank(outBill.getWarehouseCode())
                        && outBillDetailId != null && outBillDetailId > 0
                        && stockOuted != null && !stockOuted.isEmpty(),
                "拣货接收必须具有实际组织、原出库单及明细和库存行");
        for (WmsLiveStockEntity entity : stockOuted) {
            requirePicking(entity != null && Objects.equals(tenant, entity.getTenantId())
                            && Objects.equals(factory, entity.getFactoryCode())
                            && entity.getId() != null && entity.getId() > 0
                            && Objects.equals(outBill.getWarehouseCode(), entity.getWarehouseCode())
                            && StrUtil.isNotBlank(entity.getWarehouseCode()) && StrUtil.isNotBlank(entity.getSiteCode())
                            && StrUtil.isNotBlank(entity.getMatCode()) && positivePickingQuantity(entity.getMatNum()),
                    "拣货接收的实际组织、来源库存行、库存属性或数量无效");
            // 同一出库明细只能累加同一真实库存行，避免同批次不同实物混合。
            WmsPickingAreaEntity one = getOne(new LambdaQueryWrapper<WmsPickingAreaEntity>()
                    .eq(WmsPickingAreaEntity::getBillNo, outBill.getBillNo())
                    .eq(WmsPickingAreaEntity::getBillId, outBill.getId())
                    .eq(WmsPickingAreaEntity::getBillDetailId, outBillDetailId)
                    .eq(WmsPickingAreaEntity::getTenantId, tenant)
                    .eq(WmsPickingAreaEntity::getFactoryCode, factory)
                    .eq(WmsPickingAreaEntity::getSourceStockId, entity.getId())
                    .last("FOR UPDATE"), true);
            if (one != null) {
                requirePicking(one.getId() != null
                        && Objects.equals(one.getBillId(), outBill.getId())
                        && Objects.equals(one.getBillDetailId(), outBillDetailId)
                        && Objects.equals(one.getSourceStockId(), entity.getId())
                        && Objects.equals(one.getWarehouseCode(), entity.getWarehouseCode())
                        && Objects.equals(one.getSiteCode(), entity.getSiteCode())
                        && Objects.equals(one.getMatCode(), entity.getMatCode())
                        && Objects.equals(one.getMatBatch(), entity.getMatBatch())
                        && Objects.equals(StrUtil.blankToDefault(one.getContainerCode(), null),
                        StrUtil.blankToDefault(entity.getContainerCode(), null))
                        && Objects.equals(StrUtil.blankToDefault(one.getSupplierCode(), null),
                        StrUtil.blankToDefault(entity.getSupplierCode(), null))
                        && Objects.equals(one.getSourceInBillId(), entity.getSourceInBillId())
                        && Objects.equals(one.getSourceInBillDetailId(), entity.getSourceInBillDetailId())
                        && Objects.equals(one.getSourceMesInboundRequestId(), entity.getSourceMesInboundRequestId())
                        && Objects.equals(one.getSourceOrderNo(), entity.getSourceOrderNo())
                        && Objects.equals(one.getSourceOrderDetailId(), entity.getSourceOrderDetailId())
                        && Objects.equals(one.getSourceReceiptId(), entity.getSourceReceiptId())
                        && Objects.equals(one.getSourceReceiptDetailId(), entity.getSourceReceiptDetailId())
                        && Objects.equals(one.getMatBarcode(), entity.getMatBarcode())
                        && Objects.equals(one.getSerialManaged(), entity.getSerialManaged())
                        && Objects.equals(one.getStockOwnerType(), entity.getStockOwnerType())
                        && Objects.equals(one.getStockOwnerCode(), entity.getStockOwnerCode())
                        && Objects.equals(one.getSourceBusinessNo(), entity.getSourceBusinessNo()),
                        "同一来源库存行的拣货实物属性已变化，禁止合并");
                requirePicking(positivePickingQuantity(one.getMatNum())
                        && Double.isFinite(one.getMatNum() + entity.getMatNum()),
                        "原拣货记录数量无效或累计溢出");
                requirePicking(baseMapper.update(null, new LambdaUpdateWrapper<WmsPickingAreaEntity>()
                        .set(WmsPickingAreaEntity::getMatNum, one.getMatNum() + entity.getMatNum())
                        .eq(WmsPickingAreaEntity::getId, one.getId())
                        .eq(WmsPickingAreaEntity::getBillNo, outBill.getBillNo())
                        .eq(WmsPickingAreaEntity::getBillId, outBill.getId())
                        .eq(WmsPickingAreaEntity::getBillDetailId, outBillDetailId)
                        .eq(WmsPickingAreaEntity::getTenantId, tenant)
                        .eq(WmsPickingAreaEntity::getFactoryCode, factory)
                        .eq(WmsPickingAreaEntity::getSourceStockId, entity.getId())) == 1,
                        "原拣货记录更新未确认，禁止继续出库");
            } else {
                WmsPickingAreaEntity pickingArea = new WmsPickingAreaEntity();
                BeanUtil.copyProperties(entity, pickingArea, "id");
                pickingArea.setSourceStockId(entity.getId());
                pickingArea.setBillNo(outBill.getBillNo());
                pickingArea.setBillId(outBill.getId());
                pickingArea.setBillDetailId(outBillDetailId);
                pickingArea.setContainerCode(StrUtil.blankToDefault(entity.getContainerCode(), null));
                pickingArea.setSupplierCode(StrUtil.blankToDefault(entity.getSupplierCode(), null));
                // 即时保存后，同一接收集合的下一条才能查询并合并到已存在的原记录。
                requirePicking(baseMapper.insert(pickingArea) == 1, "拣货记录新增未确认，禁止继续出库");
            }
            appendPickingEvent(entity, outBill, outBillDetailId, tenant, factory);
        }
        return Boolean.TRUE;
    }

    private void appendPickingEvent(WmsLiveStockEntity stock, WmsOutBillEntity bill, Long detailId,
                                    Long tenant, String factory) {
        WmsPickingEventEntity event = new WmsPickingEventEntity()
                .setTenantId(tenant)
                .setFactoryCode(factory)
                .setWarehouseCode(stock.getWarehouseCode())
                .setSiteCode(stock.getSiteCode())
                .setBillId(bill.getId())
                .setBillDetailId(detailId)
                .setBillNo(bill.getBillNo())
                .setSourceStockId(stock.getId())
                .setSourceInBillId(stock.getSourceInBillId())
                .setSourceInBillDetailId(stock.getSourceInBillDetailId())
                .setSourceMesInboundRequestId(stock.getSourceMesInboundRequestId())
                .setMatCode(stock.getMatCode())
                .setMatBatch(stock.getMatBatch())
                .setMatBarcode(stock.getMatBarcode())
                .setMatNum(stock.getMatNum())
                .setMatUnit(stock.getMatUnit())
                .setStockOwnerType(stock.getStockOwnerType())
                .setStockOwnerCode(stock.getStockOwnerCode())
                .setSourceBusinessNo(stock.getSourceBusinessNo())
                .setSerialManaged(stock.getSerialManaged())
                .setPickedAt(LocalDateTime.now());
        requirePicking(pickingEventMapper.insert(event) == 1, "拣配事件新增未确认，原拣货操作整体回滚");
    }

    /**
     * 删除拣货区物料 记录出库流水
     *
     * @param outBill 出库单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByOutBill(WmsOutBillEntity outBill) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        requirePicking(tenant != null && StrUtil.isNotBlank(factory) && outBill != null
                        && Objects.equals(tenant, outBill.getTenantId()) && Objects.equals(factory, outBill.getFactoryCode())
                        && outBill.getId() != null && outBill.getId() > 0
                        && StrUtil.isNotBlank(outBill.getBillNo()) && StrUtil.isNotBlank(outBill.getWarehouseCode()),
                "拣货完成必须具有同实际组织的原出库单");
        List<WmsPickingAreaEntity> list = list(new LambdaQueryWrapper<WmsPickingAreaEntity>()
                .eq(WmsPickingAreaEntity::getBillNo, outBill.getBillNo())
                .eq(WmsPickingAreaEntity::getTenantId, tenant)
                .eq(WmsPickingAreaEntity::getFactoryCode, factory)
                .last("FOR UPDATE"));
        requirePicking(list != null && !list.isEmpty(), "原单拣货记录不存在，请核账，不能冒充完成");
        for (WmsPickingAreaEntity row : list) {
            requirePicking(row.getId() != null && Objects.equals(tenant, row.getTenantId())
                    && Objects.equals(factory, row.getFactoryCode())
                    && (row.getBillId() == null || Objects.equals(outBill.getId(), row.getBillId()))
                    && Objects.equals(outBill.getWarehouseCode(), row.getWarehouseCode())
                    && positivePickingQuantity(row.getMatNum()), "原单拣货记录归属或数量无效");
        }
        // 删除
        requirePicking(baseMapper.delete(new LambdaQueryWrapper<WmsPickingAreaEntity>()
                        .eq(WmsPickingAreaEntity::getBillNo, outBill.getBillNo())
                        .eq(WmsPickingAreaEntity::getTenantId, tenant)
                        .eq(WmsPickingAreaEntity::getFactoryCode, factory)
                        .in(WmsPickingAreaEntity::getId, list.stream().map(WmsPickingAreaEntity::getId).toList())) == list
                        .size(),
                "原单拣货清理数量不符，禁止生成出库流水");
        // 出库流水
        for (WmsPickingAreaEntity pickingArea : list) {
            WmsInoutFlowEntity inoutFlow = new WmsInoutFlowEntity();
            BeanUtil.copyProperties(pickingArea, inoutFlow, "id");
            inoutFlow.setSourceStockId(pickingArea.getSourceStockId());
            inoutFlow.setBillId(pickingArea.getBillId());
            inoutFlow.setBillDetailId(pickingArea.getBillDetailId());
            inoutFlow.setSourceInBillId(pickingArea.getSourceInBillId());
            inoutFlow.setSourceInBillDetailId(pickingArea.getSourceInBillDetailId());
            inoutFlow.setSourceMesInboundRequestId(pickingArea.getSourceMesInboundRequestId());
            inoutFlow.setMatBarcode(pickingArea.getMatBarcode());
            inoutFlow.setSerialManaged(pickingArea.getSerialManaged());
            inoutFlow.setStockOwnerType(pickingArea.getStockOwnerType());
            inoutFlow.setStockOwnerCode(pickingArea.getStockOwnerCode());
            inoutFlow.setSourceBusinessNo(pickingArea.getSourceBusinessNo());
            inoutFlow.setBillNo(outBill.getBillNo());
            inoutFlow.setBillType(outBill.getBillType());
            if (pickingArea.getBillDetailId() != null && pickingArea.getSourceStockId() != null) {
                inoutFlow.setStockProofType(OutBillTypeEnum.SALES_OUT.getCode().equals(outBill.getBillType())
                        ? "SALES_SHIPMENT" : "OUTBOUND");
                inoutFlow.setOperationRequestId("OUT-STOCK:" + pickingArea.getBillDetailId()
                        + ":" + pickingArea.getSourceStockId());
            }
            inoutFlow.setStockInTime(pickingArea.getInTime());
            inoutFlow.setOperateType(InOutEnum.OUT.getCode());
            requirePicking(wmsInoutFlowMapper.insert(inoutFlow) == 1, "出库流水新增未确认，原单拣货整体回滚");
        }
        return Boolean.TRUE;
    }

    /**
     * 导出
     *
     * @param wrapper 查询条件
     * @return
     */
    @Override
    public List<WmsPickingAreaExcelVO> exportExcel(LambdaQueryWrapper<WmsPickingAreaEntity> wrapper) {
        List<WmsPickingAreaEntity> list = list(wrapper);
        // 获取单位字典
        List<SysDictItemEntity> material_unit = Optional
                .ofNullable(remoteDictService.getDictByType("material_unit").getData()).orElse(new ArrayList<>());
        Map<String, String> unitMap = material_unit.stream().collect(
                Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (v1, v2) -> v1));
        List<WmsPickingAreaExcelVO> vos = list.stream().map(it -> {
            WmsPickingAreaExcelVO vo = new WmsPickingAreaExcelVO();
            BeanUtil.copyProperties(it, vo);
            vo.setMatUnit(Optional.ofNullable(unitMap.get(it.getMatUnit())).orElse(it.getMatUnit()));
            return vo;
        }).toList();
        for (int i = 0; i < vos.size(); i++) {
            vos.get(i).setRowNo(i + 1);
        }
        return vos;
    }
}
