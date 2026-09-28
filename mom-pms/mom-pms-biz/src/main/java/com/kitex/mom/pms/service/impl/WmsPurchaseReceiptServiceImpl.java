package com.kitex.mom.pms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteSupplierService;
import com.kitex.mom.pms.dto.PmsInNoticeOrderDTO;
import com.kitex.mom.pms.dto.PmsOrderPurchaseReceiptInspectionDTO;
import com.kitex.mom.pms.dto.PmsOrderPurchaseReceiptInspectionDetailDTO;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptSetQualifiedNumDTO;
import com.kitex.mom.pms.dto.app.WmsPurchaseReceiptParamsDTO;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.enums.PmsContractStatusEnum;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.*;
import com.kitex.mom.pms.service.PmsContractService;
import com.kitex.mom.pms.service.PmsOrderService;
import com.kitex.mom.pms.service.WmsPurchaseReceiptService;
import com.kitex.mom.pms.vo.WmsPurchaseReceiptExcelVO;
import com.kitex.mom.qms.api.feign.RemotePurchaseService;
import com.kitex.mom.qms.api.feign.RemoteQualityStandardService;
import com.kitex.mom.qms.dto.inspection.InspectionPurchaseCreateParam;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.wms.api.feign.RemoteInNoticeService;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.PurchaseReceiptStateEnum;
import com.kitex.mom.wms.enums.YesNoEnum;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 采购收料单
 *
 * @author kitex
 * @date 2025-01-22 11:34:35
 */
@Service
@RequiredArgsConstructor
public class WmsPurchaseReceiptServiceImpl extends ServiceImpl<WmsPurchaseReceiptMapper, WmsPurchaseReceiptEntity> implements WmsPurchaseReceiptService {

    private final WmsPurchaseReceiptDetailMapper wmsPurchaseReceiptDetailMapper;

    private final WmsPurchaseReceiptBatchDetailMapper batchDetailMapper;

    private final RemoteInNoticeService remoteInNoticeService;

    private final RemoteSeqConfigService remoteSeqConfigService;

    private final RemoteMaterialService remoteMaterialService;

    private final RemotePurchaseService remotePurchaseService;

    private final PmsOrderService pmsOrderService;

    private final RemoteQualityStandardService remoteQualityStandardService;

    private final PmsContractService pmsContractService;

    private final PmsSupplierMatMapper pmsSupplierMatMapper;

    private final PmsOrderDetailMapper pmsOrderDetailMapper;

    private final RemoteSupplierService remoteSupplierService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(WmsPurchaseReceiptEntity entity) {
        if (StrUtil.isNotBlank(entity.getPmsOrderNo())) {
            PmsOrderEntity order = requireOpenOrder(entity.getPmsOrderNo(), entity.getFactoryCode(), entity.getSupplierCode());
            assertReceiptCapacity(order, Optional.ofNullable(entity.getDetails()).orElse(List.of()), null);
        }
        R<String> billNoR = remoteSeqConfigService.create("wms_purchase_receipt");
        if (!billNoR.isOk()) {
            throw new CheckedException("生成采购收料单号失败！" + billNoR.getMsg());
        }
        entity.setId(null);
        String billNo = billNoR.getData();
        entity.setBillNo(billNo);
        entity.setState(PurchaseReceiptStateEnum.PENDING.getCode());
        baseMapper.insert(entity);

        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        mdmSupplierEntity.setSupplierCode(entity.getSupplierCode());
        MdmSupplierEntity mdmSupplier = remoteSupplierService.getDetails(mdmSupplierEntity).getData();
        if (mdmSupplier == null) {
            throw new CheckedException(String.format("编码为【%s】的供应商不存在", entity.getSupplierCode()));
        }
        // 是否免检
        Map<String, Boolean> supplierMatMap = pmsSupplierMatMapper.selectList(new LambdaQueryWrapper<PmsSupplierMatEntity>()
                        .eq(PmsSupplierMatEntity::getSupplierId, mdmSupplier.getId()))
                .stream().collect(Collectors.toMap(PmsSupplierMatEntity::getMatCode, PmsSupplierMatEntity::getIsQuality, (v1, v2) -> v1));
        for (WmsPurchaseReceiptDetailEntity detail : entity.getDetails()) {
            detail.setId(null);
            detail.setBillId(entity.getId());
//			detail.setState(PurchaseReceiptStateEnum.PENDING.getCode());
            detail.setActualNum(0d);
            detail.setInableNum(0d);
            detail.setInNum(0d);
            detail.setRejectNum(0d);
            Boolean aBoolean = supplierMatMap.get(detail.getMatCode());
            detail.setIsQuality(aBoolean != null ? aBoolean : Boolean.FALSE);
            detail.setWmsStatus(Boolean.FALSE);
            wmsPurchaseReceiptDetailMapper.insert(detail);
        }

        // 采购订单状态修改
        pmsOrderService.update(new LambdaUpdateWrapper<PmsOrderEntity>().eq(PmsOrderEntity::getPmsOrderNo, entity.getPmsOrderNo())
                .set(PmsOrderEntity::getStatus, PmsOrderStatusEnum.RECEIVING.getCode()));

        // 合同状态修改
        pmsContractService.statusUpdate(entity.getPmsOrderNo(), PmsContractStatusEnum.EXECUTION.getCode());
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        if (wmsPurchaseReceipt == null || wmsPurchaseReceipt.getId() == null) {
            throw new CheckedException("采购收料单编辑必须指定单据");
        }
        WmsPurchaseReceiptEntity current = baseMapper.selectOne(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                .eq(WmsPurchaseReceiptEntity::getId, wmsPurchaseReceipt.getId()).last("FOR UPDATE"));
        if (current == null || !PurchaseReceiptStateEnum.PENDING.getCode().equals(current.getState())) {
            throw new CheckedException("只有待处理采购收料单允许编辑，已形成现场或质量事实的单据必须保留历史");
        }
        PmsOrderEntity order = requireOpenOrder(current.getPmsOrderNo(), current.getFactoryCode(), current.getSupplierCode());
        wmsPurchaseReceipt.setBillNo(current.getBillNo());
        wmsPurchaseReceipt.setPmsOrderNo(current.getPmsOrderNo());
        wmsPurchaseReceipt.setSupplierCode(current.getSupplierCode());
        wmsPurchaseReceipt.setSupplierName(current.getSupplierName());
        wmsPurchaseReceipt.setState(current.getState());
        wmsPurchaseReceipt.setFactoryCode(current.getFactoryCode());
        baseMapper.updateById(wmsPurchaseReceipt);
        List<WmsPurchaseReceiptDetailEntity> submittedDetails = wmsPurchaseReceipt.getDetails() == null
                ? Collections.emptyList() : wmsPurchaseReceipt.getDetails();
        List<WmsPurchaseReceiptDetailEntity> persistedDetails = wmsPurchaseReceiptDetailMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                        .eq(WmsPurchaseReceiptDetailEntity::getBillId, current.getId())
                        .orderByAsc(WmsPurchaseReceiptDetailEntity::getId).last("FOR UPDATE"));
        long submittedExistingCount = submittedDetails.stream().filter(value -> value.getId() != null).count();
        long submittedExistingDistinct = submittedDetails.stream().map(WmsPurchaseReceiptDetailEntity::getId)
                .filter(Objects::nonNull).distinct().count();
        if (submittedExistingCount != submittedExistingDistinct) throw new CheckedException("采购收料单明细重复提交");
        Map<Long, WmsPurchaseReceiptDetailEntity> submittedById = submittedDetails.stream()
                .filter(value -> value.getId() != null)
                .collect(Collectors.toMap(WmsPurchaseReceiptDetailEntity::getId, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        for (WmsPurchaseReceiptDetailEntity submittedDetail : submittedDetails) {
            if (submittedDetail.getId() == null) continue;
            WmsPurchaseReceiptDetailEntity persisted = persistedDetails.stream()
                    .filter(value -> Objects.equals(value.getId(), submittedDetail.getId())).findFirst().orElse(null);
            if (persisted == null || !Objects.equals(persisted.getBillId(), current.getId())) {
                throw new CheckedException("采购收料单明细不存在或不属于当前单据");
            }
            submittedDetail.setOrderDetailId(persisted.getOrderDetailId());
        }
        List<WmsPurchaseReceiptDetailEntity> effectiveDetails = new ArrayList<>();
        for (WmsPurchaseReceiptDetailEntity persisted : persistedDetails) {
            effectiveDetails.add(submittedById.getOrDefault(persisted.getId(), persisted));
        }
        effectiveDetails.addAll(submittedDetails.stream().filter(value -> value.getId() == null).toList());
        assertReceiptCapacity(order, effectiveDetails, current.getId());
        for (WmsPurchaseReceiptDetailEntity wmsPurchaseReceiptDetail : submittedDetails) {
            if (Objects.isNull(wmsPurchaseReceiptDetail.getId())) {
                wmsPurchaseReceiptDetail.setBillId(wmsPurchaseReceipt.getId());
                wmsPurchaseReceiptDetail.setActualNum(0D);
                wmsPurchaseReceiptDetail.setInableNum(0D);
                wmsPurchaseReceiptDetail.setInNum(0D);
                wmsPurchaseReceiptDetail.setRejectNum(0D);
                wmsPurchaseReceiptDetail.setWmsStatus(Boolean.FALSE);
                wmsPurchaseReceiptDetailMapper.insert(wmsPurchaseReceiptDetail);
            } else {
                WmsPurchaseReceiptDetailEntity currentDetail = wmsPurchaseReceiptDetailMapper.selectById(
                        wmsPurchaseReceiptDetail.getId());
                if (currentDetail == null || !Objects.equals(currentDetail.getBillId(), current.getId())) {
                    throw new CheckedException("采购收料单明细不存在或不属于当前单据");
                }
                wmsPurchaseReceiptDetail.setBillId(current.getId());
                wmsPurchaseReceiptDetail.setOrderDetailId(currentDetail.getOrderDetailId());
                wmsPurchaseReceiptDetail.setActualNum(currentDetail.getActualNum());
                wmsPurchaseReceiptDetail.setInableNum(currentDetail.getInableNum());
                wmsPurchaseReceiptDetail.setInNum(currentDetail.getInNum());
                wmsPurchaseReceiptDetail.setRejectNum(currentDetail.getRejectNum());
                wmsPurchaseReceiptDetail.setWmsStatus(currentDetail.getWmsStatus());
                wmsPurchaseReceiptDetailMapper.updateById(wmsPurchaseReceiptDetail);
            }
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        if (ids == null || ids.length == 0) return Boolean.TRUE;
        List<WmsPurchaseReceiptEntity> receipts = baseMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                        .in(WmsPurchaseReceiptEntity::getId, Arrays.asList(ids)).last("FOR UPDATE"));
        if (receipts.size() != Arrays.stream(ids).distinct().count()
                || receipts.stream().anyMatch(value -> !PurchaseReceiptStateEnum.PENDING.getCode().equals(value.getState()))) {
            throw new CheckedException("只能删除全部存在且待处理的采购收料单；现场、质量和入库事实不得物理删除");
        }
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        wmsPurchaseReceiptDetailMapper.delete(Wrappers.<WmsPurchaseReceiptDetailEntity>lambdaQuery().in(WmsPurchaseReceiptDetailEntity::getBillId, ids));
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        if (ids == null || ids.length == 0) return Boolean.TRUE;
        List<WmsPurchaseReceiptDetailEntity> details = wmsPurchaseReceiptDetailMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                        .in(WmsPurchaseReceiptDetailEntity::getId, Arrays.asList(ids)).last("FOR UPDATE"));
        if (details.size() != Arrays.stream(ids).distinct().count()) {
            throw new CheckedException("采购收料单明细不存在或已被删除");
        }
        Set<Long> billIds = details.stream().map(WmsPurchaseReceiptDetailEntity::getBillId).collect(Collectors.toSet());
        List<WmsPurchaseReceiptEntity> receipts = baseMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                .in(WmsPurchaseReceiptEntity::getId, billIds).last("FOR UPDATE"));
        if (receipts.size() != billIds.size()
                || receipts.stream().anyMatch(value -> !PurchaseReceiptStateEnum.PENDING.getCode().equals(value.getState()))) {
            throw new CheckedException("只有待处理采购收料单允许删除明细；现场、质量和入库事实不得物理删除");
        }
        wmsPurchaseReceiptDetailMapper.deleteBatchIds(CollUtil.toList(ids));
        return Boolean.TRUE;
    }

    @Override
    public List<WmsPurchaseReceiptExcelVO> listExcel(QueryWrapper<WmsPurchaseReceiptEntity> wrapper) {
        List<WmsPurchaseReceiptEntity> list = list(wrapper);
        List<WmsPurchaseReceiptExcelVO> vos = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsPurchaseReceiptEntity entity = list.get(i);
            WmsPurchaseReceiptExcelVO vo = new WmsPurchaseReceiptExcelVO();
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            PurchaseReceiptStateEnum stateEnum = PurchaseReceiptStateEnum.getByCode(entity.getState());
            vo.setState(stateEnum == null ? entity.getState() : stateEnum.getDesc());
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 根据单号获取信息
     *
     * @param wmsPurchaseReceipt
     */
    @Override
    public WmsPurchaseReceiptEntity getByBillNo(WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        WmsPurchaseReceiptEntity bill = Optional.ofNullable(getOne(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                        .eq(WmsPurchaseReceiptEntity::getBillNo, wmsPurchaseReceipt.getBillNo())
                , false)).orElseThrow(() -> new CheckedException("采购收料单查询失败"));
        // 查询批次明细
        List<WmsPurchaseReceiptBatchDetailEntity> batchDetail = batchDetailMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, bill.getId())
        );
        // 查询单子物料明细
        List<WmsPurchaseReceiptDetailEntity> detail = wmsPurchaseReceiptDetailMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                .eq(WmsPurchaseReceiptDetailEntity::getBillId, bill.getId())
        );
        bill.setDetails(detail);
        bill.setBatchDetails(batchDetail);
        return bill;
    }

    /**
     * app收料
     * 同批次在批次明细中增加数量，不同批次新增一条
     * 在明细中更新物料的实收数量
     *
     * @param params
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean appReceipt(WmsPurchaseReceiptParamsDTO params) {
        WmsPurchaseReceiptEntity bill = Optional.ofNullable(getOne(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                        .eq(WmsPurchaseReceiptEntity::getBillNo, params.getBillNo())
                , false)).orElseThrow(() -> new CheckedException("采购收料单查询失败"));
        PurchaseReceiptStateEnum stateEnumBill = PurchaseReceiptStateEnum.getByCode(bill.getState());
        if (!PurchaseReceiptStateEnum.PENDING.getCode().equals(bill.getState()) && !PurchaseReceiptStateEnum.HANDLING.getCode().equals(bill.getState())) {
            throw new CheckedException("收料单状态【" + stateEnumBill.getDesc() + "】不允许收料");
        }
        // 更新单据状态
        if (PurchaseReceiptStateEnum.PENDING.getCode().equals(bill.getState())) {
            update(new LambdaUpdateWrapper<WmsPurchaseReceiptEntity>()
                    .set(WmsPurchaseReceiptEntity::getState, PurchaseReceiptStateEnum.HANDLING.getCode())
                    .eq(BaseEntity::getId, bill.getId())
            );
        }
        // 查询单子物料明细
        WmsPurchaseReceiptDetailEntity detail = Optional.ofNullable(wmsPurchaseReceiptDetailMapper.selectOne(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                        .eq(WmsPurchaseReceiptDetailEntity::getBillId, bill.getId())
                        .eq(WmsPurchaseReceiptDetailEntity::getMatCode, params.getMatCode())
                , false)).orElseThrow(() -> new CheckedException("此收料单中不包含此物料：" + params.getMatCode()));
//		PurchaseReceiptStateEnum stateEnum = PurchaseReceiptStateEnum.getByCode(detail.getState());
//		if (!PurchaseReceiptStateEnum.PENDING.getCode().equals(detail.getState()) && !PurchaseReceiptStateEnum.HANDLING.getCode().equals(detail.getState())) {
//			throw new CheckedException("收料单物料状态【" + stateEnum.getDesc() + "】不允许收料");
//		}
        // 更新数量
        wmsPurchaseReceiptDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptDetailEntity>()
                        .set(WmsPurchaseReceiptDetailEntity::getMatProduceDate, params.getMatProduceDate())
                        .set(WmsPurchaseReceiptDetailEntity::getPrice, params.getPrice())
                        .set(WmsPurchaseReceiptDetailEntity::getActualNum, detail.getActualNum() + params.getMatNum())
                        .set(WmsPurchaseReceiptDetailEntity::getMatBatch, params.getMatBatch())
//				.set(WmsPurchaseReceiptDetailEntity::getState, PurchaseReceiptStateEnum.HANDLING.getCode())
                        .eq(BaseEntity::getId, detail.getId())
        );

        return Boolean.TRUE;
    }

    /**
     * 生成质检任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean genQualityTask(List<InspectionPurchaseCreateParam> body, Boolean qmsStatus) {
        if (qmsStatus == null) {
            List<QmsQualityStandardEntity> qualityStandardList = remoteQualityStandardService.list(new QmsQualityStandardEntity().setInspectionType(InspectionTaskTypeEnum.PURCHASE.name())).getData();
            if (qualityStandardList.isEmpty()) {
                throw new RuntimeException("未找到采购质检标准，请先创建采购质检标准");
            }
            Map<String, QmsQualityStandardEntity> qualityStandardMap = qualityStandardList
                    .stream()
                    .collect(Collectors.toMap(QmsQualityStandardEntity::getMatCode, Function.identity(), (v1, v2) -> v1));

            // 收集所有缺少质检标准的物料编码
            List<String> missingMatCodes = new ArrayList<>();
            // 数量判断
            List<String> numMatCodes = new ArrayList<>();
            for (InspectionPurchaseCreateParam inspectionPurchaseCreateParam : body) {
                if (inspectionPurchaseCreateParam.getNum() == null || inspectionPurchaseCreateParam.getNum() == 0) {
                    numMatCodes.add(inspectionPurchaseCreateParam.getMatCode());
                }
                if (!qualityStandardMap.containsKey(inspectionPurchaseCreateParam.getMatCode())) {
                    missingMatCodes.add(inspectionPurchaseCreateParam.getMatCode());
                }
            }

            if (!numMatCodes.isEmpty()) {
                throw new CheckedException("以下物料数量为空：" + String.join("、", numMatCodes));
            }

            // 如果有缺少质检标准的物料，统一抛出异常
            if (!missingMatCodes.isEmpty()) {
                throw new CheckedException("未找到以下物料的质检标准：" + String.join("、", missingMatCodes));
            }

            // 查询采购收料单
            WmsPurchaseReceiptEntity wmsPurchase = this.getOne(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                    .eq(WmsPurchaseReceiptEntity::getBillNo, body.get(0).getNoticeId()), false);
            if (wmsPurchase == null) {
                throw new CheckedException("采购质检任务缺少有效采购收料单");
            }
            List<WmsPurchaseReceiptDetailEntity> allReceiptDetails = wmsPurchaseReceiptDetailMapper.selectList(
                    new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                            .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchase.getId()));
            for (InspectionPurchaseCreateParam task : body) {
                if (task.getOrderDetailId() != null) continue;
                List<WmsPurchaseReceiptDetailEntity> matches = allReceiptDetails.stream()
                        .filter(detail -> Objects.equals(detail.getMatCode(), task.getMatCode())).toList();
                if (matches.size() != 1 || matches.get(0).getOrderDetailId() == null) {
                    throw new CheckedException("采购质检任务必须唯一定位采购订单行，物料：" + task.getMatCode());
                }
                task.setOrderDetailId(matches.get(0).getOrderDetailId());
            }
            // 查询采购收料单明细
            List<WmsPurchaseReceiptDetailEntity> wmsPurchaseReceiptDetailEntities = wmsPurchaseReceiptDetailMapper
                    .selectList(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                            .eq(WmsPurchaseReceiptDetailEntity::getIsQuality, Boolean.TRUE)
                            .eq(WmsPurchaseReceiptDetailEntity::getWmsStatus, Boolean.FALSE)
                            .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchase.getId()));
            wmsPurchaseReceiptDetailMapper
                    .update(new LambdaUpdateWrapper<WmsPurchaseReceiptDetailEntity>()
                            .eq(WmsPurchaseReceiptDetailEntity::getIsQuality, Boolean.TRUE)
                            .set(WmsPurchaseReceiptDetailEntity::getWmsStatus, Boolean.TRUE)
                            .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchase.getId()));
            // 新增收料单收料明细
            wmsPurchaseReceiptDetailEntities.forEach(item -> {
                WmsPurchaseReceiptBatchDetailEntity wmsPurchaseReceiptBatchDetailEntity = new WmsPurchaseReceiptBatchDetailEntity();
                wmsPurchaseReceiptBatchDetailEntity.setBillId(wmsPurchase.getId());
                wmsPurchaseReceiptBatchDetailEntity.setOrderDetailId(item.getOrderDetailId());
                wmsPurchaseReceiptBatchDetailEntity.setMatBatch(item.getMatBatch());
                wmsPurchaseReceiptBatchDetailEntity.setMatCode(item.getMatCode());
                wmsPurchaseReceiptBatchDetailEntity.setMatName(item.getMatName());
                wmsPurchaseReceiptBatchDetailEntity.setMatUnit(item.getMatUnit());
                wmsPurchaseReceiptBatchDetailEntity.setMatSpec(item.getMatSpec());
                wmsPurchaseReceiptBatchDetailEntity.setMatNum(item.getShouldNum());
                wmsPurchaseReceiptBatchDetailEntity.setQualityNum(item.getShouldNum());
                wmsPurchaseReceiptBatchDetailEntity.setState(PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode());
                wmsPurchaseReceiptBatchDetailEntity.setMatProduceDate(item.getMatProduceDate());
                wmsPurchaseReceiptBatchDetailEntity.setQualifiedGen(String.valueOf(YesNoEnum.NO.getCode()));
                wmsPurchaseReceiptBatchDetailEntity.setQualityResult(InspectionTaskResultEnum.EXEMPTION.name());
                batchDetailMapper.insert(wmsPurchaseReceiptBatchDetailEntity);
            });

        }
        if (!body.isEmpty()) {
            R<Map<String, Long>> mapR = remotePurchaseService.batchCreate(body);
            Map<String, Long> mapTaskId = mapR.getData();
            if (CollUtil.isEmpty(mapTaskId)) {
                String msg = mapR.getMsg();
                // 解析报错信息，提取报错原因
                if (msg != null && msg.contains("\"msg\":\"")) {
                    int start = msg.indexOf("\"msg\":\"");
                    int end = msg.indexOf("\"", start + 7);
                    msg = msg.substring(start + 7, end);
                }
                throw new CheckedException("生成质检任务失败：" + msg);
            }

            // 更新主表状态
            WmsPurchaseReceiptEntity setQualityIdParam = new WmsPurchaseReceiptEntity();
            setQualityIdParam.setBillNo(body.get(0).getNoticeId());
            setQualityId(setQualityIdParam, qmsStatus);
        }
        return Boolean.TRUE;
    }

    /**
     * 绑定质检任务id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean setQualityId(WmsPurchaseReceiptEntity body, Boolean qmsStatus) {
        if (qmsStatus == null || !qmsStatus) {
            // 更新主表状态
            update(new LambdaUpdateWrapper<WmsPurchaseReceiptEntity>()
                    .set(WmsPurchaseReceiptEntity::getState, PurchaseReceiptStateEnum.QUALITY_COMMIT.getCode())
                    .eq(WmsPurchaseReceiptEntity::getBillNo, body.getBillNo())
            );
        }
        return Boolean.TRUE;
    }

    /**
     * 质检回馈合格数量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean setQualifiedNum(WmsPurchaseReceiptSetQualifiedNumDTO body) {
        WmsPurchaseReceiptEntity wmsPurchaseReceipt = this.getOne(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>().eq(WmsPurchaseReceiptEntity::getBillNo, body.getBillNo()));
        if (wmsPurchaseReceipt == null) {
            throw new CheckedException("采购收料单查询失败");
        }
        Double qualityNum = Optional.ofNullable(body.getQualityNum()).orElse(0.0);
        // 更新单据明细的数量
        List<WmsPurchaseReceiptDetailEntity> matchedDetails = wmsPurchaseReceiptDetailMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                        .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchaseReceipt.getId())
                        .eq(body.getOrderDetailId() != null, WmsPurchaseReceiptDetailEntity::getOrderDetailId,
                                body.getOrderDetailId())
                        .eq(WmsPurchaseReceiptDetailEntity::getMatCode, body.getMatCode()));
        if (matchedDetails.size() != 1) {
            throw new CheckedException("采购质检结果必须唯一定位采购订单明细，当前匹配数量：" + matchedDetails.size());
        }
        WmsPurchaseReceiptDetailEntity billDetail = matchedDetails.get(0);
        wmsPurchaseReceiptDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptDetailEntity>()
                .set(InspectionTaskResultEnum.QUALIFIED.name().equals(body.getResult()) || InspectionTaskResultEnum.EXEMPTION.name().equals(body.getResult())
                        , WmsPurchaseReceiptDetailEntity::getInableNum, Optional.ofNullable(billDetail.getInableNum()).orElse(0d) + qualityNum)
                .set(InspectionTaskResultEnum.UN_QUALIFIED.name().equals(body.getResult()), WmsPurchaseReceiptDetailEntity::getRejectNum,
                        Optional.ofNullable(billDetail.getRejectNum()).orElse(0d) + qualityNum)
                .eq(BaseEntity::getId, billDetail.getId())
        );

        // 新增收料单收料明细
        WmsPurchaseReceiptBatchDetailEntity wmsPurchaseReceiptBatchDetailEntity = new WmsPurchaseReceiptBatchDetailEntity();
        wmsPurchaseReceiptBatchDetailEntity.setBillId(wmsPurchaseReceipt.getId());
        wmsPurchaseReceiptBatchDetailEntity.setOrderDetailId(billDetail.getOrderDetailId());
        wmsPurchaseReceiptBatchDetailEntity.setMatBatch(body.getMatBatch());
        wmsPurchaseReceiptBatchDetailEntity.setMatCode(billDetail.getMatCode());
        wmsPurchaseReceiptBatchDetailEntity.setMatName(billDetail.getMatName());
        wmsPurchaseReceiptBatchDetailEntity.setMatUnit(billDetail.getMatUnit());
        wmsPurchaseReceiptBatchDetailEntity.setMatSpec(billDetail.getMatSpec());
        wmsPurchaseReceiptBatchDetailEntity.setMatNum(body.getMatNum());
        wmsPurchaseReceiptBatchDetailEntity.setQualityNum(qualityNum);
        wmsPurchaseReceiptBatchDetailEntity.setQualityResult(body.getResult());
        wmsPurchaseReceiptBatchDetailEntity.setQualityId(body.getQualityId());
        wmsPurchaseReceiptBatchDetailEntity.setState(PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode());
        wmsPurchaseReceiptBatchDetailEntity.setMatProduceDate(billDetail.getMatProduceDate());
        batchDetailMapper.insert(wmsPurchaseReceiptBatchDetailEntity);

        // 检查是否全部质检完成，更新主表的状态
        List<WmsPurchaseReceiptBatchDetailEntity> details = batchDetailMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, wmsPurchaseReceipt.getId())
        );
        boolean flag = true;
        for (WmsPurchaseReceiptBatchDetailEntity item : details) {
            if (!PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode().equals(item.getState()) &&
                    !PurchaseReceiptStateEnum.COMPLETED.getCode().equals(item.getState())) {
                flag = false;
                break;
            }
        }
        // 全部质检完成
        if (flag) {
            update(new LambdaUpdateWrapper<WmsPurchaseReceiptEntity>()
                    .set(WmsPurchaseReceiptEntity::getState, PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode())
                    .eq(BaseEntity::getId, wmsPurchaseReceipt.getId())
            );
        }
        return Boolean.TRUE;
    }

    /**
     * 生成采购入库通知单
     *
     * @param wmsPurchaseReceipt
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean genInNotice(WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        WmsInNoticeEntity inNotice = new WmsInNoticeEntity();
        BeanUtil.copyProperties(wmsPurchaseReceipt, inNotice, "id", "billNo");
        double noticeInNum = 0; // 暂存总数量
        List<WmsInNoticeDetailEntity> noticeDetails = new ArrayList<>();
        Map<String, MdmMaterialEntity> matMap = new HashMap<>(); // 暂存物料

        wmsPurchaseReceiptDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptDetailEntity>()
                .set(WmsPurchaseReceiptDetailEntity::getWmsStatus, Boolean.TRUE)
                .in(WmsPurchaseReceiptDetailEntity::getMatCode, wmsPurchaseReceipt.getBatchDetails()
                        .stream().map(WmsPurchaseReceiptBatchDetailEntity::getMatCode).collect(Collectors.toList()))
                .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchaseReceipt.getId())
        );
        for (WmsPurchaseReceiptBatchDetailEntity batchDetail : wmsPurchaseReceipt.getBatchDetails()) {
            WmsPurchaseReceiptBatchDetailEntity persistedBatch = batchDetail.getId() == null ? null
                    : batchDetailMapper.selectById(batchDetail.getId());
            if (persistedBatch == null || !Objects.equals(persistedBatch.getBillId(), wmsPurchaseReceipt.getId())
                    || persistedBatch.getOrderDetailId() == null) {
                throw new CheckedException("采购入库通知明细缺少收料批次或采购订单行来源，不能生成不可追溯库存");
            }
            WmsInNoticeDetailEntity noticeDetail = new WmsInNoticeDetailEntity();
            BeanUtil.copyProperties(persistedBatch, noticeDetail, "id");
            setPurchaseSource(noticeDetail, wmsPurchaseReceipt, persistedBatch);
            // 查找物料信息进行赋值
            MdmMaterialEntity mat = matMap.get(persistedBatch.getMatCode());
            if (mat == null) {
                mat = remoteMaterialService.getDetails(persistedBatch.getMatCode()).getData();
                matMap.put(persistedBatch.getMatCode(), mat);
            }
            if (mat == null) {
                throw new CheckedException("未查找到物料信息，请维护物料");
            }
            noticeDetail.setMatGroup(mat.getGroupCode());
            noticeDetail.setMatGroupName(mat.getGroupName());
            noticeDetail.setMatUnit(mat.getBasicUnit());
            noticeDetail.setOperateUnit(mat.getBasicUnit());
            noticeDetail.setMatValidPeriod(mat.getExpirationDay());
            if (noticeDetail.getMatProduceDate() != null && noticeDetail.getMatValidPeriod() != null) {
                noticeDetail.setMatValidEndDate(noticeDetail.getMatProduceDate().plusDays(noticeDetail.getMatValidPeriod()));
            }
            noticeDetail.setWarehouseCode(wmsPurchaseReceipt.getWarehouseCode());
            noticeDetail.setWarehouseName(wmsPurchaseReceipt.getWarehouseName());
            noticeDetail.setSupplierCode(wmsPurchaseReceipt.getSupplierCode());
            noticeDetail.setSupplierName(wmsPurchaseReceipt.getSupplierName());
            // 数量
            Double inNum = persistedBatch.getQualityNum();
            noticeDetail.setNoticeInNum(inNum);
            noticeDetail.setInNum(0d);
            noticeDetail.setNotInNum(inNum);
            noticeDetail.setOperateNum(inNum);
            // 质检结果
            noticeDetail.setQualityTaskId(batchDetail.getQualityId());
            noticeDetail.setQualityResult(batchDetail.getQualityResult());
            // 所属组织
            noticeDetail.setFactoryCode(String.valueOf(wmsPurchaseReceipt.getFactoryCode()));
            noticeInNum += inNum;
            noticeDetails.add(noticeDetail);
        }

        inNotice.setNoticeInNum(noticeInNum);
        inNotice.setAllInNum(0d);
        inNotice.setAllNotInNum(noticeInNum);
        inNotice.setDocumentId(wmsPurchaseReceipt.getId());// 采购收料单的id
        inNotice.setDocumentTable(DocumentEnum.PURCHASE_RECEIPT.getCode());
        inNotice.setBillType(InBillTypeEnum.PURCHASE.getCode());
        inNotice.setDetailList(noticeDetails);
        Map<String, String> exMap = new HashMap<>();
        exMap.put("genType", wmsPurchaseReceipt.getGenType());
        inNotice.setExpansion(JSONUtil.toJsonStr(exMap)); // 将类别存到扩展字段里，方便回调

        Boolean data = remoteInNoticeService.save(inNotice).getData();
        if (!data) {
            throw new CheckedException("生成采购入库通知单失败");
        }

        // 更新收料明细字段 入库通知单作废时也需要将此字段改回来
        batchDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                .set(StrUtil.equals("1", wmsPurchaseReceipt.getGenType()), WmsPurchaseReceiptBatchDetailEntity::getQualifiedGen, YesNoEnum.YES.getCode())
                .set(StrUtil.equals("2", wmsPurchaseReceipt.getGenType()), WmsPurchaseReceiptBatchDetailEntity::getUnQualifiedGen, YesNoEnum.YES.getCode())
                .in(BaseEntity::getId, wmsPurchaseReceipt.getBatchDetails().stream().map(BaseEntity::getId).toList())
        );

        return Boolean.TRUE;
    }

    /**
     * 入库时更新实际入库数
     *
     * @param billId   采购收料单id
     * @param matCode  物料编码
     * @param matBatch 物料批次
     * @param matNum   入库数量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addMatNum(@NotNull Long billId, Long orderDetailId, @NotNull String matCode,
                             @NotNull String matBatch, @NotNull Double matNum) {
        // 更新收料明细数量
        List<WmsPurchaseReceiptBatchDetailEntity> batchMatches = batchDetailMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                        .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, billId)
                        .eq(orderDetailId != null, WmsPurchaseReceiptBatchDetailEntity::getOrderDetailId, orderDetailId)
                        .eq(WmsPurchaseReceiptBatchDetailEntity::getMatCode, matCode)
                        .eq(WmsPurchaseReceiptBatchDetailEntity::getMatBatch, matBatch));
        if (batchMatches.size() != 1) {
            throw new CheckedException("采购入库必须唯一定位收料批次明细，当前匹配数量：" + batchMatches.size());
        }
        WmsPurchaseReceiptBatchDetailEntity batchDetailEntity = batchMatches.get(0);
        Double inedNum = Optional.ofNullable(batchDetailEntity.getInNum()).orElse(0d);
        Double inableNum = Optional.ofNullable(batchDetailEntity.getQualityNum()).orElse(0d);
        double inNum = inedNum + matNum;
        batchDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                .set(WmsPurchaseReceiptBatchDetailEntity::getInNum, inNum)
                .set(inedNum + matNum >= inableNum, WmsPurchaseReceiptBatchDetailEntity::getState, PurchaseReceiptStateEnum.COMPLETED.getCode())
                .eq(BaseEntity::getId, batchDetailEntity.getId())
        );
        // 如果全部已完成，单据就完成
        Long count = batchDetailMapper.selectCount(new LambdaQueryWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, billId)
                .ne(WmsPurchaseReceiptBatchDetailEntity::getState, PurchaseReceiptStateEnum.COMPLETED.getCode())
        );
        Long detailCount = wmsPurchaseReceiptDetailMapper.selectCount(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                .eq(WmsPurchaseReceiptDetailEntity::getBillId, billId)
                .eq(WmsPurchaseReceiptDetailEntity::getWmsStatus, Boolean.FALSE)
                .eq(WmsPurchaseReceiptDetailEntity::getIsQuality, Boolean.FALSE));
        WmsPurchaseReceiptEntity byId = this.getById(billId);
        if (count == 0 && detailCount == 0) {
            byId.setState(PurchaseReceiptStateEnum.COMPLETED.getCode());
            updateById(byId);
        }
        // 更新单据明细数量
        List<WmsPurchaseReceiptDetailEntity> detailMatches = wmsPurchaseReceiptDetailMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                        .eq(WmsPurchaseReceiptDetailEntity::getBillId, billId)
                        .eq(WmsPurchaseReceiptDetailEntity::getOrderDetailId, batchDetailEntity.getOrderDetailId())
                        .eq(WmsPurchaseReceiptDetailEntity::getMatCode, matCode));
        if (detailMatches.size() != 1) {
            throw new CheckedException("采购入库必须唯一定位采购订单明细，当前匹配数量：" + detailMatches.size());
        }
        WmsPurchaseReceiptDetailEntity detailEntity = detailMatches.get(0);
        Double allInedNum = Optional.ofNullable(detailEntity.getInNum()).orElse(0d);
        wmsPurchaseReceiptDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptDetailEntity>()
                .set(WmsPurchaseReceiptDetailEntity::getInNum, allInedNum + matNum)
                .eq(WmsPurchaseReceiptDetailEntity::getId, detailEntity.getId())
        );
        // 推送采购模块更新采购订单到货数量
        if (StrUtil.isNotBlank(byId.getPmsOrderNo())) {
            PmsInNoticeOrderDTO pmsInNoticeOrderDTO = new PmsInNoticeOrderDTO();
            pmsInNoticeOrderDTO.setOrderDetailId(detailEntity.getOrderDetailId());
            pmsInNoticeOrderDTO.setPmsOrderNo(byId.getPmsOrderNo());
            pmsInNoticeOrderDTO.setMatCode(detailEntity.getMatCode());
            pmsInNoticeOrderDTO.setNum(matNum);
            pmsOrderService.inNoticeSave(pmsInNoticeOrderDTO);
        }
        return Boolean.TRUE;
    }

    /**
     * pc端采购收料接口
     *
     * @param pmsOrderDTO 采购收料单质检DTO
     * @return R
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean pcPmsReceipt(PmsOrderPurchaseReceiptInspectionDTO pmsOrderDTO, Map<String, Boolean> supplierMatMap) {
        WmsPurchaseReceiptEntity entity = new WmsPurchaseReceiptEntity();
        BeanUtil.copyProperties(pmsOrderDTO, entity);
        R<String> wmsPurchaseReceipt = remoteSeqConfigService.create("wms_purchase_receipt");
        if (wmsPurchaseReceipt.getCode() != 0) {
            throw new CheckedException(String.format("【wms_purchase_receipt】生成采购收料单号失败:【%s】", wmsPurchaseReceipt.getMsg()));
        }
        entity.setBillNo(wmsPurchaseReceipt.getData());
        Boolean qmsStatus = false;
        if (pmsOrderDTO.getQmsDetails() == null || pmsOrderDTO.getQmsDetails().size() > 0) {
            entity.setState(PurchaseReceiptStateEnum.HANDLING.getCode());
        } else {
            qmsStatus = true;
            entity.setState(PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode());
        }

        baseMapper.insert(entity);
        for (PmsOrderPurchaseReceiptInspectionDetailDTO detailDTO : pmsOrderDTO.getDetails()) {
            WmsPurchaseReceiptDetailEntity detail = new WmsPurchaseReceiptDetailEntity();
            BeanUtil.copyProperties(detailDTO, detail);
            detail.setBillId(entity.getId());
            detail.setActualNum(detailDTO.getShouldNum());
            detail.setInableNum(0d);
            detail.setInNum(0d);
            detail.setRejectNum(0d);
            detail.setPrice(detailDTO.getPrice());
            detail.setMatProduceDate(detailDTO.getMatProduceDate());
            Boolean aBoolean = supplierMatMap.get(detail.getMatCode());
            detail.setIsQuality(aBoolean != null ? aBoolean : Boolean.FALSE);
            detail.setWmsStatus(Boolean.FALSE);
            wmsPurchaseReceiptDetailMapper.insert(detail);

            detailDTO.setNoticeId(entity.getBillNo());
        }
        // 如果需要生成质检任务
        if (pmsOrderDTO.getQms()) {
            List<InspectionPurchaseCreateParam> body = pmsOrderDTO.getQmsDetails().stream().map(item -> {
                InspectionPurchaseCreateParam inspectionPurchaseCreateParam = new InspectionPurchaseCreateParam();
                BeanUtil.copyProperties(item, inspectionPurchaseCreateParam);
                inspectionPurchaseCreateParam.setOrderDetailId(item.getOrderDetailId());
                inspectionPurchaseCreateParam.setSupplierCode(pmsOrderDTO.getSupplierCode());
                inspectionPurchaseCreateParam.setSupplierName(pmsOrderDTO.getSupplierName());
                inspectionPurchaseCreateParam.setBatchNo(item.getMatBatch());
                inspectionPurchaseCreateParam.setNum(item.getShouldNum().intValue());
                inspectionPurchaseCreateParam.setFactoryCode(String.valueOf(pmsOrderDTO.getFactoryCode()));
                return inspectionPurchaseCreateParam;
            }).toList();
            this.genQualityTask(body, qmsStatus);

            // 新增收料单收料明细
            pmsOrderDTO.getWmsDetails().forEach(item -> {
                WmsPurchaseReceiptBatchDetailEntity wmsPurchaseReceiptBatchDetailEntity = new WmsPurchaseReceiptBatchDetailEntity();
                wmsPurchaseReceiptBatchDetailEntity.setBillId(entity.getId());
                wmsPurchaseReceiptBatchDetailEntity.setOrderDetailId(item.getOrderDetailId());
                wmsPurchaseReceiptBatchDetailEntity.setMatBatch(item.getMatBatch());
                wmsPurchaseReceiptBatchDetailEntity.setMatCode(item.getMatCode());
                wmsPurchaseReceiptBatchDetailEntity.setMatName(item.getMatName());
                wmsPurchaseReceiptBatchDetailEntity.setMatUnit(item.getMatUnit());
                wmsPurchaseReceiptBatchDetailEntity.setMatSpec(item.getMatSpec());
                wmsPurchaseReceiptBatchDetailEntity.setMatNum(item.getShouldNum());
                wmsPurchaseReceiptBatchDetailEntity.setQualityNum(item.getShouldNum());
                wmsPurchaseReceiptBatchDetailEntity.setState(PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode());
                wmsPurchaseReceiptBatchDetailEntity.setMatProduceDate(item.getMatProduceDate());
                wmsPurchaseReceiptBatchDetailEntity.setQualifiedGen(String.valueOf(YesNoEnum.NO.getCode()));
                wmsPurchaseReceiptBatchDetailEntity.setQualityResult(InspectionTaskResultEnum.EXEMPTION.name());
                batchDetailMapper.insert(wmsPurchaseReceiptBatchDetailEntity);
            });

        }
        return Boolean.TRUE;
    }

    /**
     * 采购入库通知单作废后回调
     *
     * @param documentId
     * @param genType
     * @param result
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean voidObjsBack(Long documentId, String genType, List<WmsPurchaseReceiptBatchDetailEntity> result) {
        for (WmsPurchaseReceiptBatchDetailEntity batchDetail : result) {
            batchDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                    .set(StrUtil.equals("1", genType), WmsPurchaseReceiptBatchDetailEntity::getQualifiedGen, YesNoEnum.NO.getCode())
                    .set(StrUtil.equals("2", genType), WmsPurchaseReceiptBatchDetailEntity::getUnQualifiedGen, YesNoEnum.NO.getCode())
                    .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, documentId)
                    .eq(WmsPurchaseReceiptBatchDetailEntity::getMatCode, batchDetail.getMatCode())
                    .eq(WmsPurchaseReceiptBatchDetailEntity::getMatBatch, batchDetail.getMatBatch())
            );
        }
        return Boolean.TRUE;
    }

    /**
     * 收料
     *
     * @param pmsOrder 采购收料单质检DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean receive(PmsOrderPurchaseReceiptInspectionDTO pmsOrder) {

        PmsOrderEntity order = requireOpenOrder(pmsOrder.getPmsOrderNo(), pmsOrder.getFactoryCode(), pmsOrder.getSupplierCode());
        assertReceiptCapacity(order, pmsOrder.getDetails(), null);

        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        mdmSupplierEntity.setSupplierCode(pmsOrder.getSupplierCode());
        MdmSupplierEntity mdmSupplier = remoteSupplierService.getDetails(mdmSupplierEntity).getData();
        if (mdmSupplier == null) {
            throw new CheckedException(String.format("编码为【%s】的供应商不存在", pmsOrder.getSupplierCode()));
        }
        Map<String, Boolean> supplierMatMap = pmsSupplierMatMapper.selectList(new LambdaQueryWrapper<PmsSupplierMatEntity>()
                        .eq(PmsSupplierMatEntity::getSupplierId, mdmSupplier.getId()))
                .stream().collect(Collectors.toMap(PmsSupplierMatEntity::getMatCode, PmsSupplierMatEntity::getIsQuality, (v1, v2) -> v1));
        Map<String, QmsQualityStandardEntity> qualityStandardMap = new HashMap<>();
        if (pmsOrder.getQms()) {
            // 检查是否有需要质检的物料（supplierMatMap中值为false的物料需要质检）
            boolean hasQualityMat = pmsOrder.getDetails().stream()
                    .anyMatch(detail -> !Boolean.TRUE.equals(supplierMatMap.get(detail.getMatCode())));

            if (hasQualityMat) {
                List<QmsQualityStandardEntity> qualityStandardList = remoteQualityStandardService.list(new QmsQualityStandardEntity().setInspectionType(InspectionTaskTypeEnum.PURCHASE.name())).getData();
                if (qualityStandardList.isEmpty()) {
                    throw new RuntimeException("未找到采购质检标准，请先创建采购质检标准");
                }
                qualityStandardMap = qualityStandardList
                        .stream()
                        .collect(Collectors.toMap(QmsQualityStandardEntity::getMatCode, Function.identity(), (v1, v2) -> v1));
            }

            // 需要质检的明细
            List<PmsOrderPurchaseReceiptInspectionDetailDTO> qmsDetails = new ArrayList<>();
            // 免检的明细
            List<PmsOrderPurchaseReceiptInspectionDetailDTO> wmsDetails = new ArrayList<>();
            // 收集所有缺少质检标准的物料编码
            List<String> missingMatCodes = new ArrayList<>();

            for (PmsOrderPurchaseReceiptInspectionDetailDTO pmsOrderPurchaseReceiptInspectionDetailDTOList : pmsOrder.getDetails()) {
                if (!Boolean.TRUE.equals(supplierMatMap.get(pmsOrderPurchaseReceiptInspectionDetailDTOList.getMatCode()))) {
                    if (!qualityStandardMap.containsKey(pmsOrderPurchaseReceiptInspectionDetailDTOList.getMatCode())) {
                        missingMatCodes.add(pmsOrderPurchaseReceiptInspectionDetailDTOList.getMatCode());
                    }
                    qmsDetails.add(pmsOrderPurchaseReceiptInspectionDetailDTOList);
                } else {
                    wmsDetails.add(pmsOrderPurchaseReceiptInspectionDetailDTOList);
                }
            }

            // 如果有缺少质检标准的物料，统一抛出异常
            if (!missingMatCodes.isEmpty()) {
                throw new CheckedException("未找到以下物料的质检标准：" + String.join("、", missingMatCodes));
            }

            pmsOrder.setQmsDetails(qmsDetails);
            pmsOrder.setWmsDetails(wmsDetails);
        }
        // 采购收料
        this.pcPmsReceipt(pmsOrder, supplierMatMap);

        // 合同状态修改
        pmsContractService.statusUpdate(pmsOrder.getPmsOrderNo(), PmsContractStatusEnum.EXECUTION.getCode());

        // 修改采购订单状态
        return pmsOrderService.update(new LambdaUpdateWrapper<PmsOrderEntity>().set(PmsOrderEntity::getStatus, PmsOrderStatusEnum.RECEIVING.getCode())
                .eq(PmsOrderEntity::getPmsOrderNo, pmsOrder.getPmsOrderNo()));
    }

    private PmsOrderEntity requireOpenOrder(String orderNo, String factoryCode, String supplierCode) {
        if (StrUtil.isBlank(orderNo)) throw new CheckedException("采购收料必须指定采购订单号");
        PmsOrderEntity order = pmsOrderService.getOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, orderNo).last("FOR UPDATE"), false);
        if (order == null || !Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(),
                PmsOrderStatusEnum.RECEIVING.getCode(), PmsOrderStatusEnum.PART_RECEIVE.getCode()).contains(order.getStatus())) {
            throw new CheckedException("采购订单不存在、未审核通过、已完成或已取消，禁止新建收料事实");
        }
        if (StrUtil.isNotBlank(factoryCode) && !Objects.equals(factoryCode, order.getFactoryCode())) {
            throw new CheckedException("采购收料工厂与采购订单不一致");
        }
        if (StrUtil.isNotBlank(supplierCode) && !Objects.equals(supplierCode, order.getSupplierCode())) {
            throw new CheckedException("采购收料供应商与采购订单不一致");
        }
        pmsContractService.assertReceivableContract(orderNo);
        return order;
    }

    /**
     * 在创建收料事实前按订单行锁定并校验有效未到货量。已受控撤回的数量、其他活动收料单
     * 已占用的数量都不可再次收料；这也是行级采购变更不能被 WMS 旁路绕过的最终门禁。
     */
    private void assertReceiptCapacity(PmsOrderEntity order, List<?> submittedLines, Long excludeBillId) {
        if (submittedLines == null || submittedLines.isEmpty()) throw new CheckedException("采购收料至少包含一条明细");
        Map<Long, BigDecimal> requested = new LinkedHashMap<>();
        for (Object value : submittedLines) {
            Long detailId;
            Double quantity;
            if (value instanceof WmsPurchaseReceiptDetailEntity detail) {
                detailId = detail.getOrderDetailId();
                quantity = detail.getShouldNum();
            } else if (value instanceof PmsOrderPurchaseReceiptInspectionDetailDTO detail) {
                detailId = detail.getOrderDetailId();
                quantity = detail.getShouldNum();
            } else {
                throw new CheckedException("不支持的采购收料明细类型");
            }
            if (detailId == null || quantity == null || quantity <= 0D)
                throw new CheckedException("采购收料明细必须指定订单行和正数应收数量");
            requested.merge(detailId, BigDecimal.valueOf(quantity), BigDecimal::add);
        }
        List<PmsOrderDetailEntity> orderLines = pmsOrderDetailMapper.selectList(
                new LambdaQueryWrapper<PmsOrderDetailEntity>()
                        .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId())
                        .in(PmsOrderDetailEntity::getId, requested.keySet())
                        .orderByAsc(PmsOrderDetailEntity::getId).last("FOR UPDATE"));
        if (orderLines.size() != requested.size())
            throw new CheckedException("采购收料包含不存在或不属于当前订单的明细");

        List<WmsPurchaseReceiptEntity> activeReceipts = baseMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                        .eq(WmsPurchaseReceiptEntity::getPmsOrderNo, order.getPmsOrderNo())
                        .ne(excludeBillId != null, WmsPurchaseReceiptEntity::getId, excludeBillId)
                        .and(value -> value.isNull(WmsPurchaseReceiptEntity::getState)
                                .or().ne(WmsPurchaseReceiptEntity::getState, PurchaseReceiptStateEnum.COMPLETED.getCode())));
        Map<Long, BigDecimal> occupied = new HashMap<>();
        if (!activeReceipts.isEmpty()) {
            wmsPurchaseReceiptDetailMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                            .in(WmsPurchaseReceiptDetailEntity::getBillId,
                                    activeReceipts.stream().map(WmsPurchaseReceiptEntity::getId).toList())
                            .in(WmsPurchaseReceiptDetailEntity::getOrderDetailId, requested.keySet()))
                    .forEach(value -> occupied.merge(value.getOrderDetailId(),
                            BigDecimal.valueOf(Optional.ofNullable(value.getShouldNum()).orElse(0D)), BigDecimal::add));
        }
        for (PmsOrderDetailEntity line : orderLines) {
            BigDecimal ordered = BigDecimal.valueOf(Optional.ofNullable(line.getOrderNum()).orElse(0D));
            BigDecimal arrived = BigDecimal.valueOf(Optional.ofNullable(line.getArrivalNum()).orElse(0D));
            BigDecimal cancelled = BigDecimal.valueOf(Optional.ofNullable(line.getCancelledNum()).orElse(0D));
            BigDecimal available = ordered.subtract(arrived).subtract(cancelled)
                    .subtract(occupied.getOrDefault(line.getId(), BigDecimal.ZERO));
            BigDecimal currentRequest = requested.get(line.getId());
            if (available.signum() < 0 || currentRequest.compareTo(available) > 0) {
                throw new CheckedException("采购订单行" + line.getId() + "可收数量仅为"
                        + available.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()
                        + "，已到货、已撤回或活动收料占用数量不得重复收料");
            }
        }
    }

    /**
     * 根据id查询采购收料单
     *
     * @param id id
     * @return R  对象
     */
    @Override
    public WmsPurchaseReceiptEntity genQmsDetails(Long id) {
        WmsPurchaseReceiptEntity byIdDeep = this.getByIdDeep(id);

        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        mdmSupplierEntity.setSupplierCode(byIdDeep.getSupplierCode());
        MdmSupplierEntity mdmSupplier = remoteSupplierService.getDetails(mdmSupplierEntity).getData();
        if (mdmSupplier == null) {
            throw new CheckedException(String.format("编码为【%s】的供应商不存在", byIdDeep.getSupplierCode()));
        }
        // 检查所有details中的isQuality是否都为true
        boolean allIsQuality = byIdDeep.getDetails().stream()
                .allMatch(detail -> Boolean.TRUE.equals(detail.getIsQuality()));
        byIdDeep.setQmsStatus(allIsQuality);
        return byIdDeep;
    }

    /**
     * 免检生成采购入库通知单
     *
     * @param wmsPurchaseReceipt 采购收料单
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean qmsGenInNotice(WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        WmsInNoticeEntity inNotice = new WmsInNoticeEntity();
        BeanUtil.copyProperties(wmsPurchaseReceipt, inNotice, "id", "billNo");
        double noticeInNum = 0; // 暂存总数量
        List<WmsInNoticeDetailEntity> noticeDetails = new ArrayList<>();
        Map<String, MdmMaterialEntity> matMap = new HashMap<>(); // 暂存物料

        wmsPurchaseReceiptDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptDetailEntity>()
                .set(WmsPurchaseReceiptDetailEntity::getWmsStatus, Boolean.TRUE)
                .in(WmsPurchaseReceiptDetailEntity::getId, wmsPurchaseReceipt.getDetails()
                        .stream().map(WmsPurchaseReceiptDetailEntity::getId).collect(Collectors.toList())));

        for (WmsPurchaseReceiptDetailEntity detail : wmsPurchaseReceipt.getDetails()) {

            // 新增收料单收料明细
            WmsPurchaseReceiptBatchDetailEntity wmsPurchaseReceiptBatchDetailEntity = new WmsPurchaseReceiptBatchDetailEntity();
            wmsPurchaseReceiptBatchDetailEntity.setBillId(wmsPurchaseReceipt.getId());
            wmsPurchaseReceiptBatchDetailEntity.setOrderDetailId(detail.getOrderDetailId());
            wmsPurchaseReceiptBatchDetailEntity.setMatBatch(detail.getMatBatch());
            wmsPurchaseReceiptBatchDetailEntity.setMatCode(detail.getMatCode());
            wmsPurchaseReceiptBatchDetailEntity.setMatName(detail.getMatName());
            wmsPurchaseReceiptBatchDetailEntity.setMatUnit(detail.getMatUnit());
            wmsPurchaseReceiptBatchDetailEntity.setMatSpec(detail.getMatSpec());
            wmsPurchaseReceiptBatchDetailEntity.setMatNum(detail.getActualNum());
            wmsPurchaseReceiptBatchDetailEntity.setQualityNum(detail.getActualNum());
            wmsPurchaseReceiptBatchDetailEntity.setState(PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode());
            wmsPurchaseReceiptBatchDetailEntity.setMatProduceDate(detail.getMatProduceDate());
            wmsPurchaseReceiptBatchDetailEntity.setQualifiedGen(String.valueOf(YesNoEnum.YES.getCode()));
            wmsPurchaseReceiptBatchDetailEntity.setQualityResult(InspectionTaskResultEnum.EXEMPTION.name());
            batchDetailMapper.insert(wmsPurchaseReceiptBatchDetailEntity);

            WmsInNoticeDetailEntity noticeDetail = new WmsInNoticeDetailEntity();
            BeanUtil.copyProperties(wmsPurchaseReceiptBatchDetailEntity, noticeDetail, "id");
            setPurchaseSource(noticeDetail, wmsPurchaseReceipt, wmsPurchaseReceiptBatchDetailEntity);
            // 查找物料信息进行赋值
            MdmMaterialEntity mat = matMap.get(detail.getMatCode());
            if (mat == null) {
                mat = remoteMaterialService.getDetails(wmsPurchaseReceiptBatchDetailEntity.getMatCode()).getData();
                matMap.put(wmsPurchaseReceiptBatchDetailEntity.getMatCode(), mat);
            }
            if (mat == null) {
                throw new CheckedException("未查找到物料信息，请维护物料");
            }
            noticeDetail.setMatGroup(mat.getGroupCode());
            noticeDetail.setMatGroupName(mat.getGroupName());
            noticeDetail.setMatUnit(mat.getBasicUnit());
            noticeDetail.setOperateUnit(mat.getBasicUnit());
            noticeDetail.setMatValidPeriod(mat.getExpirationDay());
            if (noticeDetail.getMatProduceDate() != null && noticeDetail.getMatValidPeriod() != null) {
                noticeDetail.setMatValidEndDate(noticeDetail.getMatProduceDate().plusDays(noticeDetail.getMatValidPeriod()));
            }
            noticeDetail.setWarehouseCode(wmsPurchaseReceipt.getWarehouseCode());
            noticeDetail.setWarehouseName(wmsPurchaseReceipt.getWarehouseName());
            noticeDetail.setSupplierCode(wmsPurchaseReceipt.getSupplierCode());
            noticeDetail.setSupplierName(wmsPurchaseReceipt.getSupplierName());
            // 数量
            Double inNum = wmsPurchaseReceiptBatchDetailEntity.getQualityNum();
            noticeDetail.setNoticeInNum(inNum);
            noticeDetail.setInNum(0d);
            noticeDetail.setNotInNum(inNum);
            noticeDetail.setOperateNum(inNum);
            // 所属组织
            noticeDetail.setFactoryCode(String.valueOf(wmsPurchaseReceipt.getFactoryCode()));
            noticeInNum += inNum;
            noticeDetails.add(noticeDetail);
        }

        inNotice.setNoticeInNum(noticeInNum);
        inNotice.setAllInNum(0d);
        inNotice.setAllNotInNum(noticeInNum);
        inNotice.setDocumentId(wmsPurchaseReceipt.getId());// 采购收料单的id
        inNotice.setDocumentTable(DocumentEnum.PURCHASE_RECEIPT.getCode());
        inNotice.setBillType(InBillTypeEnum.PURCHASE.getCode());
        inNotice.setDetailList(noticeDetails);
        Map<String, String> exMap = new HashMap<>();
        exMap.put("genType", wmsPurchaseReceipt.getGenType());
        inNotice.setExpansion(JSONUtil.toJsonStr(exMap)); // 将类别存到扩展字段里，方便回调

        Boolean data = remoteInNoticeService.save(inNotice).getData();
        if (!data) {
            throw new CheckedException("生成采购入库通知单失败");
        }

        Long count = wmsPurchaseReceiptDetailMapper.selectCount(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                .eq(WmsPurchaseReceiptDetailEntity::getIsQuality, Boolean.TRUE)
                .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchaseReceipt.getId()));
        if (count == 0) {
            this.update(new LambdaUpdateWrapper<WmsPurchaseReceiptEntity>()
                    .eq(WmsPurchaseReceiptEntity::getId, wmsPurchaseReceipt.getId())
                    .set(WmsPurchaseReceiptEntity::getState, PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode()));
        }

        return Boolean.TRUE;
    }

    /**
     * 将采购来源写入WMS通知明细，后续通过入库单、实时库存和流水原样传递。
     */
    private void setPurchaseSource(WmsInNoticeDetailEntity noticeDetail,
                                   WmsPurchaseReceiptEntity receipt,
                                   WmsPurchaseReceiptBatchDetailEntity batchDetail) {
        if (receipt.getId() == null || batchDetail.getId() == null || batchDetail.getOrderDetailId() == null) {
            throw new CheckedException("采购收料来源不完整，不能生成不可追溯的入库通知");
        }

        WmsPurchaseReceiptEntity persistedReceipt = Optional.ofNullable(baseMapper.selectById(receipt.getId()))
                .orElseThrow(() -> new CheckedException("采购收料单不存在，不能生成入库通知"));
        PmsOrderDetailEntity orderDetail = Optional.ofNullable(pmsOrderDetailMapper.selectById(batchDetail.getOrderDetailId()))
                .orElseThrow(() -> new CheckedException("采购收料批次对应的采购订单行不存在"));
        if (!Objects.equals(batchDetail.getBillId(), persistedReceipt.getId())
                || StrUtil.isBlank(persistedReceipt.getPmsOrderNo())
                || !Objects.equals(orderDetail.getOrderNo(), persistedReceipt.getPmsOrderNo())) {
            throw new CheckedException("采购收料单、收料批次与采购订单行来源不一致，请先修复来源数据");
        }

        noticeDetail.setSourceOrderNo(persistedReceipt.getPmsOrderNo());
        noticeDetail.setSourceOrderDetailId(batchDetail.getOrderDetailId());
        noticeDetail.setSourceReceiptId(persistedReceipt.getId());
        noticeDetail.setSourceReceiptDetailId(batchDetail.getId());
        noticeDetail.setDocumentId(batchDetail.getId());
        noticeDetail.setDocumentTable("mom_pms.wms_purchase_receipt_batch_detail");
    }
}
