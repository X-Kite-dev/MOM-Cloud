package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesPickingReceiveMatDTO;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillDetailEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.enums.MesPickingBillStateEnum;
import com.kitex.mom.mes.enums.YesNoEnum;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillDetailMapper;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillMapper;
import com.kitex.mom.mes.service.MesWarehouseMaterialInBillService;
import com.kitex.mom.mes.service.MesWarehousePickingBillService;
import com.kitex.mom.wms.api.feign.RemoteProducePickingNoticeService;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产领料
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@Service
@RequiredArgsConstructor
public class MesWarehousePickingBillServiceImpl extends ServiceImpl<MesWarehousePickingBillMapper, MesWarehousePickingBillEntity> implements MesWarehousePickingBillService {

    private final MesWarehousePickingBillDetailMapper mesWarehousePickingBillDetailMapper;
    private final MesWarehouseMaterialInBillService mesWarehouseMaterialInBillService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final RemoteProducePickingNoticeService remoteProducePickingNoticeService;
    private final MesMaterialReceiptService materialReceiptService;
    private final MesActualFactoryAccessService actualFactoryAccess;
    private final MesMaterialExecutionGuard materialExecution;
    private final MesPickingMaterialPolicy pickingPolicy;
    private final MesPickingWmsSourceGuard wmsSources;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MesWarehousePickingBillEntity entity) {
        if (entity == null || entity.getId() != null) {
            throw new CheckedException("新增领料单不得携带已有ID");
        }
        wmsSources.requireReady();
        pickingPolicy.normalize(entity, true);
        // 序列号
        R<String> billNoR = remoteSeqConfigService.create("mes_picking_bill");
        if (!billNoR.isOk()) {
            throw new CheckedException("生成生产领料单号失败！");
        }
        String billNo = billNoR.getData();
        entity.setBillNo(billNo);
        entity.setStatus(MesPickingBillStateEnum.CREATED.getCode());
        MomUser user = SecurityUtils.getUser();
        entity.setPicker(user.getUsername());
        entity.setPickTime(LocalDateTime.now());
        if (baseMapper.insert(entity) != 1) {
            throw new CheckedException("生产领料单保存失败");
        }

        for (MesWarehousePickingBillDetailEntity detail : entity.getDetailList()) {
            detail.setBillId(entity.getId());
            detail.setBillNo(billNo);
            detail.setStatus(YesNoEnum.NO.getCode());
            if (mesWarehousePickingBillDetailMapper.insert(detail) != 1) {
                throw new CheckedException("生产领料明细保存失败");
            }
            pickingPolicy.verifySavedPickingQuantities(detail);
        }

        wmsSources.register(entity);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MesWarehousePickingBillEntity entity) {
        if (entity == null || entity.getId() == null) {
            throw new CheckedException("请选择领料单");
        }
        MesWarehousePickingBillEntity original = lockDraft(entity.getId());
        entity.setBillNo(original.getBillNo());
        entity.setFactoryCode(original.getFactoryCode());
        entity.setTenantId(original.getTenantId());
        entity.setTaskCode(original.getTaskCode());
        entity.setStatus(original.getStatus());
        entity.setPicker(original.getPicker());
        entity.setPickTime(original.getPickTime());
        if (entity.getDetailList() != null) {
            for (MesWarehousePickingBillDetailEntity detail : entity.getDetailList()) {
                if (detail.getId() != null) {
                    MesWarehousePickingBillDetailEntity existing = mesWarehousePickingBillDetailMapper.selectById(
                            detail.getId());
                    if (existing == null || !Objects.equals(existing.getBillId(), original.getId())) {
                        throw new CheckedException("领料明细不属于当前单据");
                    }
                }
                detail.setBillId(original.getId());
                detail.setBillNo(original.getBillNo());
                detail.setFactoryCode(original.getFactoryCode());
                detail.setStatus(YesNoEnum.NO.getCode());
            }
        }
        pickingPolicy.normalize(entity, wmsSources.source(entity.getId()) != null);
        // 实体更新默认跳过null；源产品为空时也必须清除旧值，且不能重复给非空列赋值。
        LambdaUpdateWrapper<MesWarehousePickingBillEntity> update = new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                .eq(MesWarehousePickingBillEntity::getId, entity.getId())
                .set(entity.getMatCode() == null, MesWarehousePickingBillEntity::getMatCode, null)
                .set(entity.getMatName() == null, MesWarehousePickingBillEntity::getMatName, null)
                .set(entity.getMatSpec() == null, MesWarehousePickingBillEntity::getMatSpec, null);
        if (baseMapper.update(entity, update) != 1) {
            throw new CheckedException("生产领料单修改失败");
        }
        for (MesWarehousePickingBillDetailEntity mesWarehouseLockDetail : entity.getDetailList()) {
            if (Objects.isNull(mesWarehouseLockDetail.getId())) {
                mesWarehouseLockDetail.setBillId(entity.getId());
                if (mesWarehousePickingBillDetailMapper.insert(mesWarehouseLockDetail) != 1) {
                    throw new CheckedException("生产领料明细保存失败");
                }
            } else {
                if (mesWarehousePickingBillDetailMapper.updateById(mesWarehouseLockDetail) != 1) {
                    throw new CheckedException("生产领料明细修改失败");
                }
            }
            pickingPolicy.verifySavedPickingQuantities(mesWarehouseLockDetail);
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        if (ids == null || ids.length == 0 || java.util.Arrays.asList(ids).contains(null))
            throw new CheckedException("请选择领料单");
        List<Long> selected = java.util.Arrays.stream(ids).distinct().sorted().toList();
        for (Long id : selected) {
            lockDraft(id);
        }
        LambdaQueryWrapper<MesWarehousePickingBillDetailEntity> selectedDetails = Wrappers
                .<MesWarehousePickingBillDetailEntity>lambdaQuery()
                .in(MesWarehousePickingBillDetailEntity::getBillId, selected);
        long detailCount = mesWarehousePickingBillDetailMapper.selectCount(selectedDetails);
        if (baseMapper.deleteBatchIds(selected) != selected.size()) {
            throw new CheckedException("生产领料单删除失败");
        }
        if (mesWarehousePickingBillDetailMapper.delete(selectedDetails) != detailCount) {
            throw new CheckedException("生产领料明细删除失败");
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        if (ids == null || ids.length == 0 || java.util.Arrays.asList(ids).contains(null)) {
            throw new CheckedException("请选择领料明细");
        }
        List<MesWarehousePickingBillDetailEntity> details = mesWarehousePickingBillDetailMapper.selectBatchIds(java.util.Arrays.asList(ids));
        if (details.size() != java.util.Arrays.stream(ids).distinct().count())
            throw new CheckedException("领料明细不存在");
        for (Long billId : details.stream().map(MesWarehousePickingBillDetailEntity::getBillId).distinct().sorted().toList()) {
            MesWarehousePickingBillEntity bill = lockDraft(billId);
            if (details.stream().filter(detail -> Objects.equals(detail.getBillId(), billId))
                    .anyMatch(detail -> !Objects.equals(detail.getFactoryCode(), bill.getFactoryCode())
                            || !Objects.equals(detail.getTenantId(), bill.getTenantId()))) {
                throw new CheckedException("领料明细与原单租户或工厂不一致");
            }
        }
        if (mesWarehousePickingBillDetailMapper.deleteBatchIds(CollUtil.toList(ids)) != details.size()) {
            throw new CheckedException("生产领料明细删除失败");
        }
        return Boolean.TRUE;
    }

    /**
     * 提交生产领料单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean commitBill(Long id) {
        lockDraft(id);
        wmsSources.requireLegacy(id);
        MesWarehousePickingBillEntity pickingBill = getByIdDeep(id);
        // 提交前再次核对已存明细；不能把草稿时期的旧方案或换算结果带入实际发料。
        pickingPolicy.normalize(pickingBill, false);
        for (var detail : pickingBill.getDetailList()) {
            if (mesWarehousePickingBillDetailMapper.updateById(detail) != 1) {
                throw new CheckedException("提交前领料需求复核保存失败");
            }
            pickingPolicy.verifySavedPickingQuantities(detail);
            if (!Double.isFinite(detail.getMatNum().doubleValue())
                    || BigDecimal.valueOf(detail.getMatNum().doubleValue()).compareTo(detail.getMatNum()) != 0) {
                throw new CheckedException("现有WMS通知接口无法无损表达该领料数量，禁止舍入发料");
            }
        }
        // 1、修改状态
        if (baseMapper.update(null, new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                .set(MesWarehousePickingBillEntity::getStatus, MesPickingBillStateEnum.COMMITTED.getCode())
                .eq(BaseEntity::getId, id)
        ) != 1) {
            throw new CheckedException("生产领料单提交失败");
        }
        // 2、生成出库通知单
        List<MesWarehousePickingBillDetailEntity> detailList = pickingBill.getDetailList();
        // 明细中存在不同的出库仓库，需要生成不同的出库单
        Map<String, List<MesWarehousePickingBillDetailEntity>> map = detailList.stream()
                .collect(Collectors.groupingBy(MesWarehousePickingBillDetailEntity::getWarehouseCode));
        for (String warehouseCode : map.keySet()) {
            List<MesWarehousePickingBillDetailEntity> list = map.get(warehouseCode);
            String warehouseName = list.get(0).getWarehouseName();
            WmsOutNoticeEntity outNotice = new WmsOutNoticeEntity();
            BeanUtil.copyProperties(pickingBill, outNotice, "id");
            outNotice.setWarehouseCode(warehouseCode);
            outNotice.setWarehouseName(warehouseName);
            outNotice.setDocumentId(id);
            outNotice.setDocumentTable(DocumentEnum.MES_PICKING.getCode());
            List<WmsOutNoticeDetailEntity> outNoticeDetails = new ArrayList<>();
            for (MesWarehousePickingBillDetailEntity pickingBillDetail : list) {
                WmsOutNoticeDetailEntity outNoticeDetail = new WmsOutNoticeDetailEntity();
                BeanUtil.copyProperties(pickingBillDetail, outNoticeDetail, "id");
                outNoticeDetail.setNoticeOutNum(pickingBillDetail.getMatNum().doubleValue());
                outNoticeDetail.setDocumentId(id);
                outNoticeDetail.setDocumentTable(DocumentEnum.MES_PICKING.getCode());
                outNoticeDetails.add(outNoticeDetail);
            }
            outNotice.setDetailList(outNoticeDetails);
            R<Boolean> add = remoteProducePickingNoticeService.add(outNotice);
            // 兼容历史WMS业务成功且Boolean为空；新版返回true，仍拒绝明确失败或缺失响应。
            if (add == null || !add.isOk() || Boolean.FALSE.equals(add.getData())) {
                throw new CheckedException(
                        "生成WMS领料出库通知单失败！" + (add == null ? "未收到受理结果" : add.getMsg()));
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 撤回
     *
     * @param id 生产领料单id
     */
    @Override
    @Transactional
    public Boolean withdraw(Long id) {
        MesWarehousePickingBillEntity pickingBill = lockBillForWrite(id);
        wmsSources.requireLegacy(id);
        if (!MesPickingBillStateEnum.COMMITTED.getCode().equals(pickingBill.getStatus())) {
            MesPickingBillStateEnum state = MesPickingBillStateEnum.getByCode(pickingBill.getStatus());
            throw new CheckedException("仅已提交且尚未出库的生产领料单允许撤回，当前状态："
                    + (state == null ? pickingBill.getStatus() : state.getName()));
        }
        if (mesWarehouseMaterialInBillService.count(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                .eq(MesWarehouseMaterialInBillEntity::getBillId, id)) > 0) {
            throw new CheckedException("已有WMS发料通知，须通过退料处置，不允许撤回领料来源");
        }
        // 作废出库通知单
        R voidRes = remoteProducePickingNoticeService.voidObjByDocumentId(id, DocumentEnum.MES_PICKING.getCode());
        if (voidRes == null || !voidRes.isOk() || !Boolean.TRUE.equals(voidRes.getData())) {
            throw new CheckedException(
                    "作废领料出库通知单失败！" + (voidRes == null ? "未收到受理结果" : voidRes.getMsg()));
        }
        // 修改状态
        if (baseMapper.update(null, new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                .set(MesWarehousePickingBillEntity::getStatus, MesPickingBillStateEnum.CREATED.getCode())
                .eq(BaseEntity::getId, id)
        ) != 1) {
            throw new CheckedException("生产领料单撤回失败");
        }
        return Boolean.TRUE;
    }

    /**
     * 领料出库单完成后返回结果
     */
    @Override
    @Transactional
    public Boolean wmsOutBillBack(MesPickingWmsOutBillBackDTO dto) {
        if (dto == null || dto.getId() == null || CollUtil.isEmpty(dto.getMatDetails())
                || dto.getMatDetails().stream().anyMatch(item -> item == null || item.getWmsOutDetailId() == null)) {
            throw new CheckedException("WMS发料回调必须包含生产领料单及精确出库明细来源");
        }
        materialReceiptService.registerPickingNotices(dto.getId(), dto.getMatDetails());
        return true;
    }

    @Override
    @Transactional
    public Boolean receiveBill(MesPickingReceiveMatDTO dto) {
        if (dto == null || dto.getId() == null) throw new CheckedException("请选择生产领料单");
        return materialReceiptService.receiveAll(dto.getId(), dto.getSiteCode());
    }

    private MesWarehousePickingBillEntity lockDraft(Long id) {
        MesWarehousePickingBillEntity bill = lockBillForWrite(id);
        wmsSources.requireEditable(id);
        if (!MesPickingBillStateEnum.CREATED.getCode().equals(bill.getStatus())
                || mesWarehouseMaterialInBillService.count(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                .eq(MesWarehouseMaterialInBillEntity::getBillId, id)) > 0) {
            throw new CheckedException("只有未提交、无发料及签收事实的领料草案可以修改、删除或提交");
        }
        return bill;
    }

    private MesWarehousePickingBillEntity lockBillForWrite(Long id) {
        String factory = actualFactoryAccess.require("write");
        MesWarehousePickingBillEntity bill = materialExecution.picking(id);
        if (bill == null || !Objects.equals(factory, bill.getFactoryCode())) {
            throw new CheckedException("生产领料单不存在或不属于当前租户工厂");
        }
        return bill;
    }
}
