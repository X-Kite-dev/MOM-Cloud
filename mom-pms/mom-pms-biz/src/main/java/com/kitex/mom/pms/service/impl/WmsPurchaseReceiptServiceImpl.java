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
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
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

    private final PmsOrderReceivableQuantity receiptCapacity;

    private final RemoteSupplierService remoteSupplierService;

    private final PmsExistingReceiptRegistrationService existingReceiptRegistration;

    private final PmsPurchaseInboundPostingService inboundPosting;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(WmsPurchaseReceiptEntity entity) {
        PmsOrderEntity order = null;
        if (StrUtil.isNotBlank(entity.getPmsOrderNo())) {
            order = requireOpenOrder(entity.getPmsOrderNo(), entity.getFactoryCode(), entity.getSupplierCode());
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
        if (order != null) {
            markReceiving(order);
        }

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
     * 按实际租户及工厂读取唯一原收料单和所属明细/批次。
     *
     * @param wmsPurchaseReceipt 仅使用完整原单号，不信任客户端组织及来源字段
     * @return 经过实名工厂读核验的原单；未知或重复原号拒绝
     */
    @Override
    public WmsPurchaseReceiptEntity getByBillNo(WmsPurchaseReceiptEntity wmsPurchaseReceipt) {
        if (wmsPurchaseReceipt == null) {
            throw new CheckedException("请输入完整原收料单号");
        }
        return existingReceiptRegistration.readByBillNo(wmsPurchaseReceipt.getBillNo());
    }

    /**
     * 沿原收料单登记实收；组织、剩余量、批次日期及价格由独立登记服务核验。
     * 本入口不生成质量批次或入库通知；同一收料行不覆盖不同实物批次。
     *
     * @param params 原单、物料/精确原行及本次实收事实
     * @return 已保存实收登记；失败整笔回滚
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean appReceipt(WmsPurchaseReceiptParamsDTO params) {
        return existingReceiptRegistration.register(params);
    }

    /**
     * 生成质检任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean genQualityTask(List<InspectionPurchaseCreateParam> body, Boolean qmsStatus) {
        if (qmsStatus == null) {
            if (CollUtil.isEmpty(body)) {
                throw new CheckedException("没有可送检的收料行，免检行请直接办理入库通知");
            }
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
                    .eq(WmsPurchaseReceiptEntity::getBillNo, body.get(0).getNoticeId())
                    .eq(WmsPurchaseReceiptEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                    .eq(WmsPurchaseReceiptEntity::getTenantId, TenantContextHolder.getTenantId()));
            if (wmsPurchase == null) {
                throw new CheckedException("采购质检任务缺少有效采购收料单");
            }
            List<WmsPurchaseReceiptDetailEntity> allReceiptDetails = wmsPurchaseReceiptDetailMapper.selectList(
                    new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                            .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchase.getId()));
            for (InspectionPurchaseCreateParam task : body) {
                if (!Objects.equals(task.getNoticeId(), wmsPurchase.getBillNo())) {
                    throw new CheckedException("同一批质检请求必须来自同一张采购收料单");
                }
                List<WmsPurchaseReceiptDetailEntity> matches = allReceiptDetails.stream()
                        .filter(detail -> Objects.equals(detail.getMatCode(), task.getMatCode()))
                        .filter(detail -> task.getOrderDetailId() == null
                                || Objects.equals(task.getOrderDetailId(), detail.getOrderDetailId()))
                        .toList();
                if (matches.size() != 1 || matches.get(0).getOrderDetailId() == null) {
                    throw new CheckedException("采购质检任务必须唯一定位采购订单行，物料：" + task.getMatCode());
                }
                var line = matches.get(0);
                if (Boolean.TRUE.equals(line.getIsQuality()) || line.getActualNum() == null
                        || !Double.isFinite(line.getActualNum()) || task.getNum() == null || task.getNum() <= 0
                        || BigDecimal.valueOf(line.getActualNum()).compareTo(BigDecimal.valueOf(task.getNum())) != 0
                        || StrUtil.isBlank(line.getMatBatch()) || !Objects.equals(line.getMatBatch(), task.getBatchNo())) {
                    throw new CheckedException("送检数量或批次与实收原行不一致，当前来料检验只接受整件数量");
                }
                task.setOrderDetailId(line.getOrderDetailId());
                task.setSupplierCode(wmsPurchase.getSupplierCode());
                task.setSupplierName(wmsPurchase.getSupplierName());
                task.setFactoryCode(wmsPurchase.getFactoryCode());
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
                if (item.getActualNum() == null || !Double.isFinite(item.getActualNum()) || item.getActualNum() <= 0) {
                    throw new CheckedException("免检行尚无正数实收，不能按应收数量生成批次");
                }
                WmsPurchaseReceiptBatchDetailEntity wmsPurchaseReceiptBatchDetailEntity = new WmsPurchaseReceiptBatchDetailEntity();
                wmsPurchaseReceiptBatchDetailEntity.setBillId(wmsPurchase.getId());
                wmsPurchaseReceiptBatchDetailEntity.setOrderDetailId(item.getOrderDetailId());
                wmsPurchaseReceiptBatchDetailEntity.setMatBatch(item.getMatBatch());
                wmsPurchaseReceiptBatchDetailEntity.setMatCode(item.getMatCode());
                wmsPurchaseReceiptBatchDetailEntity.setMatName(item.getMatName());
                wmsPurchaseReceiptBatchDetailEntity.setMatUnit(item.getMatUnit());
                wmsPurchaseReceiptBatchDetailEntity.setMatSpec(item.getMatSpec());
                wmsPurchaseReceiptBatchDetailEntity.setMatNum(item.getActualNum());
                wmsPurchaseReceiptBatchDetailEntity.setQualityNum(item.getActualNum());
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
        WmsInNoticeEntity inNotice = prepareWarehouseNotice(wmsPurchaseReceipt);
        double noticeInNum = 0; // 暂存总数量
        List<WmsInNoticeDetailEntity> noticeDetails = new ArrayList<>();
        Map<String, MdmMaterialEntity> matMap = new HashMap<>(); // 暂存物料

        Set<Long> selected = new HashSet<>();
        if (CollUtil.isEmpty(wmsPurchaseReceipt.getBatchDetails()) || !Set.of("1", "2").contains(Objects.toString(wmsPurchaseReceipt.getGenType(), ""))) {
            throw new CheckedException("请选择质检批次及对应的入库类型");
        }
        for (var batchDetail : wmsPurchaseReceipt.getBatchDetails()) {
            if (batchDetail == null || batchDetail.getId() == null || !selected.add(batchDetail.getId())) {
                throw new CheckedException("所选收料批次缺少原号或重复");
            }
            var persistedBatch = batchDetailMapper.selectOne(new LambdaQueryWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                    .eq(WmsPurchaseReceiptBatchDetailEntity::getId, batchDetail.getId())
                    .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, wmsPurchaseReceipt.getId()).last("FOR UPDATE"));
            if (persistedBatch == null || persistedBatch.getOrderDetailId() == null
                    || !PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode().equals(persistedBatch.getState())
                    || persistedBatch.getQualityNum() == null || !Double.isFinite(persistedBatch.getQualityNum())
                    || persistedBatch.getQualityNum() <= 0) {
                throw new CheckedException("采购收料批次、质量状态或待通知量无法确认");
            }
            boolean failed = InspectionTaskResultEnum.UN_QUALIFIED.name().equals(persistedBatch.getQualityResult());
            boolean passed = InspectionTaskResultEnum.EXEMPTION.name().equals(persistedBatch.getQualityResult())
                    || InspectionTaskResultEnum.QUALIFIED.name().equals(persistedBatch.getQualityResult())
                    || InspectionTaskResultEnum.RELEASE.name().equals(persistedBatch.getQualityResult())
                    || InspectionTaskResultEnum.RECEIVE.name().equals(persistedBatch.getQualityResult());
            String generated = failed ? persistedBatch.getUnQualifiedGen() : persistedBatch.getQualifiedGen();
            if ((!passed && !failed) || failed != "2".equals(wmsPurchaseReceipt.getGenType()) || "1".equals(generated)) {
                throw new CheckedException("采购批次质检结论与入库类型不符或已有通知，请核对原批次");
            }
            var original = wmsPurchaseReceiptDetailMapper.selectOne(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                    .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchaseReceipt.getId())
                    .eq(WmsPurchaseReceiptDetailEntity::getOrderDetailId, persistedBatch.getOrderDetailId())
                    .eq(WmsPurchaseReceiptDetailEntity::getMatCode, persistedBatch.getMatCode()).last("FOR UPDATE"));
            if (original == null || !Objects.equals(original.getMatUnit(), persistedBatch.getMatUnit())) {
                throw new CheckedException("采购批次单位与原收料行不一致");
            }
            if (!Boolean.TRUE.equals(original.getWmsStatus())) {
                original.setWmsStatus(true);
                if (wmsPurchaseReceiptDetailMapper.updateById(original) != 1) {
                    throw new CheckedException("原采购收料行通知状态未保存");
                }
            }
            WmsInNoticeDetailEntity noticeDetail = new WmsInNoticeDetailEntity();
            BeanUtil.copyProperties(persistedBatch, noticeDetail, "id");
            setPurchaseSource(noticeDetail, wmsPurchaseReceipt, persistedBatch);
            applyNoticeMaterial(noticeDetail, persistedBatch, wmsPurchaseReceipt, matMap);
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
            // 质量任务和结论只沿服务端原收料批次传递，客户端选择不能覆盖已持久化的质量依据。
            noticeDetail.setQualityTaskId(persistedBatch.getQualityId());
            noticeDetail.setQualityResult(persistedBatch.getQualityResult());
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

        // 原批次已逐行锁定。先记本地通知标记，仓库明确拒绝时由事务回滚。
        int changed = batchDetailMapper.update(new LambdaUpdateWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, wmsPurchaseReceipt.getId())
                .set("1".equals(wmsPurchaseReceipt.getGenType()), WmsPurchaseReceiptBatchDetailEntity::getQualifiedGen, YesNoEnum.YES.getCode())
                .set("2".equals(wmsPurchaseReceipt.getGenType()), WmsPurchaseReceiptBatchDetailEntity::getUnQualifiedGen, YesNoEnum.YES.getCode())
                .in(BaseEntity::getId, selected));
        if (changed != selected.size()) {
            throw new CheckedException("所选采购批次通知状态未完整保存");
        }
        saveWarehouseNotice(inNotice);

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
        inboundPosting.lockLegacySource(billId);
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
            // 同次送检会为免检行登记批次，后续从该批次续办。
            detail.setWmsStatus(Boolean.TRUE.equals(pmsOrderDTO.getQms()) && Boolean.TRUE.equals(detail.getIsQuality()));
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
                try {
                    inspectionPurchaseCreateParam.setNum(BigDecimal.valueOf(item.getShouldNum()).intValueExact());
                } catch (ArithmeticException ex) {
                    throw new CheckedException("来料检验数量超出整件范围，请核对原采购单位及本次送检量");
                }
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
        markReceiving(order);
        return Boolean.TRUE;
    }

    /** 首次建收料单进入收货中，已部分到货的订单继续保留其原状态。调用方已锁定原订单。 */
    private void markReceiving(PmsOrderEntity order) {
        if (PmsOrderStatusEnum.CHECK_SUCCESS.getCode().equals(order.getStatus())
                && !pmsOrderService.update(new LambdaUpdateWrapper<PmsOrderEntity>()
                        .eq(PmsOrderEntity::getId, order.getId())
                        .eq(PmsOrderEntity::getTenantId, order.getTenantId())
                        .eq(PmsOrderEntity::getFactoryCode, order.getFactoryCode())
                        .set(PmsOrderEntity::getStatus, PmsOrderStatusEnum.RECEIVING.getCode()))) {
            throw new CheckedException("原采购单收货状态未保存");
        }
    }

    /** 在当前认证组织锁定采购订单，避免跨工厂同号和分批收料并发占用。 */
    private PmsOrderEntity requireOpenOrder(String orderNo, String factoryCode, String supplierCode) {
        String currentFactory = FactoryContextHolder.getFactoryCode();
        Long currentTenant = TenantContextHolder.getTenantId();
        if (StrUtil.isBlank(orderNo) || currentTenant == null || StrUtil.isBlank(currentFactory)) {
            throw new CheckedException("采购收料须指定原订单并具有当前租户和工厂身份");
        }
        PmsOrderEntity order = pmsOrderService.getOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getPmsOrderNo, orderNo)
                .eq(PmsOrderEntity::getTenantId, currentTenant)
                .eq(PmsOrderEntity::getFactoryCode, currentFactory).last("FOR UPDATE"));
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
        if (submittedLines == null || submittedLines.isEmpty()) {
            throw new CheckedException("采购收料至少包含一条明细");
        }
        Map<Long, BigDecimal> requested = new LinkedHashMap<>();
        List<ReceiptItem> items = new ArrayList<>();
        for (Object value : submittedLines) {
            ReceiptItem item;
            if (value instanceof WmsPurchaseReceiptDetailEntity detail) {
                item = new ReceiptItem(detail.getOrderDetailId(), detail.getMatCode(), detail.getMatUnit(), detail.getShouldNum());
            } else if (value instanceof PmsOrderPurchaseReceiptInspectionDetailDTO detail) {
                item = new ReceiptItem(detail.getOrderDetailId(), detail.getMatCode(), detail.getMatUnit(), detail.getShouldNum());
            } else {
                throw new CheckedException("不支持的采购收料明细类型");
            }
            if (item.id() == null || item.quantity() == null || !Double.isFinite(item.quantity()) || item.quantity() <= 0) {
                throw new CheckedException("采购收料须指定原订单行和正数应收数量");
            }
            if (requested.putIfAbsent(item.id(), BigDecimal.valueOf(item.quantity())) != null) {
                throw new CheckedException("同一张收料单内原采购行只能出现一次，分批到货请分别登记");
            }
            items.add(item);
        }
        var lines = pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).eq(PmsOrderDetailEntity::getTenantId, order.getTenantId())
                .eq(PmsOrderDetailEntity::getFactoryCode, order.getFactoryCode()).in(PmsOrderDetailEntity::getId, requested.keySet())
                .orderByAsc(PmsOrderDetailEntity::getId).last("FOR UPDATE"));
        if (lines.size() != requested.size()) {
            throw new CheckedException("采购收料包含不存在或不属于当前订单的明细");
        }
        var available = receiptCapacity.available(order, lines, excludeBillId);
        var originals = lines.stream().collect(Collectors.toMap(PmsOrderDetailEntity::getId, Function.identity()));
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var original = originals.get(item.id());
            PmsOrderReceivableQuantity.requireMaterial(original, item.code(), item.unit());
            // 收料单沿用原采购行单价。客户端仅选择收料数量和现场批次。
            if (submittedLines.get(index) instanceof WmsPurchaseReceiptDetailEntity detail) {
                detail.setPrice(original.getPrice());
            } else if (submittedLines.get(index) instanceof PmsOrderPurchaseReceiptInspectionDetailDTO detail) {
                detail.setPrice(original.getPrice());
            }
            var remaining = available.get(item.id());
            if (requested.get(item.id()).compareTo(remaining) > 0) {
                throw new CheckedException("采购订单行 " + item.id() + " 可收数量仅为 " + remaining.stripTrailingZeros().toPlainString());
            }
        }
    }

    /** 本次收料提交的原行身份、原单位和业务数量。 */
    private record ReceiptItem(Long id, String code, String unit, Double quantity) {
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
        WmsInNoticeEntity inNotice = prepareWarehouseNotice(wmsPurchaseReceipt);
        double noticeInNum = 0; // 暂存总数量
        List<WmsInNoticeDetailEntity> noticeDetails = new ArrayList<>();
        Map<String, MdmMaterialEntity> matMap = new HashMap<>(); // 暂存物料

        if (CollUtil.isEmpty(wmsPurchaseReceipt.getDetails()) || !"1".equals(wmsPurchaseReceipt.getGenType())) {
            throw new CheckedException("请选择要办理合格入库的免检收料行");
        }
        for (var selected : wmsPurchaseReceipt.getDetails()) {
            if (selected == null || selected.getId() == null) {
                throw new CheckedException("免检通知缺少原收料行");
            }
            var detail = wmsPurchaseReceiptDetailMapper.selectOne(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                    .eq(WmsPurchaseReceiptDetailEntity::getId, selected.getId())
                    .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchaseReceipt.getId()).last("FOR UPDATE"));
            if (detail == null || !Boolean.TRUE.equals(detail.getIsQuality()) || !Boolean.FALSE.equals(detail.getWmsStatus())
                    || detail.getActualNum() == null || !Double.isFinite(detail.getActualNum()) || detail.getActualNum() <= 0) {
                throw new CheckedException("原收料行已生成通知、未获免检或缺少正数实收量，请重新读取");
            }
            // “收料并送检”可能已经登记免检批次，续办时只能从原批次生成通知。
            if (batchDetailMapper.exists(new LambdaQueryWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                    .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, detail.getBillId())
                    .eq(WmsPurchaseReceiptBatchDetailEntity::getOrderDetailId, detail.getOrderDetailId()))) {
                throw new CheckedException("原采购行已有收料批次，请从批次生成或核对原通知");
            }
            detail.setWmsStatus(true);
            if (wmsPurchaseReceiptDetailMapper.updateById(detail) != 1) {
                throw new CheckedException("免检收料行通知状态未保存");
            }
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
            applyNoticeMaterial(noticeDetail, wmsPurchaseReceiptBatchDetailEntity, wmsPurchaseReceipt, matMap);
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

        // 全免检单没有 QMS 回调。最后一条免检行已登记批次后，原收料单才能进入待入库回写状态。
        long pending = wmsPurchaseReceiptDetailMapper.selectCount(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                .eq(WmsPurchaseReceiptDetailEntity::getBillId, wmsPurchaseReceipt.getId())
                .and(q -> q.isNull(WmsPurchaseReceiptDetailEntity::getIsQuality)
                        .or().eq(WmsPurchaseReceiptDetailEntity::getIsQuality, false)
                        .or().isNull(WmsPurchaseReceiptDetailEntity::getWmsStatus)
                        .or().eq(WmsPurchaseReceiptDetailEntity::getWmsStatus, false)));
        if (pending == 0 && !this.update(new LambdaUpdateWrapper<WmsPurchaseReceiptEntity>()
                .eq(WmsPurchaseReceiptEntity::getId, wmsPurchaseReceipt.getId())
                .set(WmsPurchaseReceiptEntity::getState, PurchaseReceiptStateEnum.QUALITY_FINISHED.getCode()))) {
            throw new CheckedException("原免检收料单状态未保存");
        }
        saveWarehouseNotice(inNotice);

        return Boolean.TRUE;
    }

    /** 从已锁定的当前组织原单生成通知表头。仓储目的地使用现场选择，供应商与单据身份使用原件。 */
    private WmsInNoticeEntity prepareWarehouseNotice(WmsPurchaseReceiptEntity selection) {
        if (selection == null || selection.getId() == null || TenantContextHolder.getTenantId() == null
                || StrUtil.isBlank(FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("采购通知缺少原收料单或当前组织");
        }
        var original = getOne(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                .eq(WmsPurchaseReceiptEntity::getId, selection.getId())
                .eq(WmsPurchaseReceiptEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsPurchaseReceiptEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("FOR UPDATE"));
        if (original == null || PurchaseReceiptStateEnum.COMPLETED.getCode().equals(original.getState())
                || !Objects.equals(original.getFactoryCode(), selection.getFactoryCode())
                || !Objects.equals(original.getSupplierCode(), selection.getSupplierCode())) {
            throw new CheckedException("原收料单已完成或与当前工厂、供应商不一致");
        }
        var notice = new WmsInNoticeEntity();
        BeanUtil.copyProperties(original, notice, "id", "billNo");
        notice.setWarehouseCode(selection.getWarehouseCode());
        notice.setWarehouseName(selection.getWarehouseName());
        selection.setSupplierName(original.getSupplierName());
        return notice;
    }

    /** 仓库必须明确确认。本地事务无法判断网络中断后远程是否已受理，须按原收料单核对。 */
    private void saveWarehouseNotice(WmsInNoticeEntity notice) {
        var reply = remoteInNoticeService.save(notice);
        if (reply == null || !reply.isOk() || !Boolean.TRUE.equals(reply.getData())) {
            throw new CheckedException("仓库未确认采购入库通知单，请按原收料单核对");
        }
    }

    /**
     * 仓储通知沿用已核实的采购批次单位。当前物料仅用于核对基本单位和补充仓库所需的分类、效期。
     */
    private void applyNoticeMaterial(WmsInNoticeDetailEntity notice, WmsPurchaseReceiptBatchDetailEntity batch,
                                     WmsPurchaseReceiptEntity receipt, Map<String, MdmMaterialEntity> cache) {
        var material = cache.computeIfAbsent(batch.getMatCode(), code -> {
            var response = remoteMaterialService.getDetails(code);
            return response != null && response.isOk() ? response.getData() : null;
        });
        if (material == null || !Objects.equals(material.getMaterialCode(), batch.getMatCode())
                || !Objects.equals(material.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(material.getFactoryCode(), receipt.getFactoryCode())
                || StrUtil.isBlank(batch.getMatUnit()) || !batch.getMatUnit().equals(material.getBasicUnit())) {
            throw new CheckedException("采购批次 " + batch.getId() + " 的原单位或物料组织与当前主档不一致");
        }
        notice.setMatGroup(material.getGroupCode());
        notice.setMatGroupName(material.getGroupName());
        notice.setMatUnit(batch.getMatUnit());
        notice.setOperateUnit(batch.getMatUnit());
        notice.setMatValidPeriod(material.getExpirationDay());
    }

    /**
     * 将实际采购订单、收料单和批次的来源写入WMS通知明细，供入库单、库存和流水追溯。
     * 订单行的订单号可指向上游需求；采购归属只沿订单行的采购订单主键解析。
     * 原件身份、物料、租户、工厂或供应商不一致时，拒绝生成通知。
     */
    private void setPurchaseSource(WmsInNoticeDetailEntity noticeDetail,
                                   WmsPurchaseReceiptEntity receipt,
                                   WmsPurchaseReceiptBatchDetailEntity batchDetail) {
        if (receipt.getId() == null || batchDetail.getId() == null || batchDetail.getOrderDetailId() == null
                || !Objects.equals(batchDetail.getBillId(), receipt.getId())) {
            throw new CheckedException("采购收料来源不完整，不能生成不可追溯的入库通知");
        }

        WmsPurchaseReceiptEntity persistedReceipt = Optional.ofNullable(baseMapper.selectById(receipt.getId()))
                .orElseThrow(() -> new CheckedException("采购收料单不存在，不能生成入库通知"));
        PmsOrderDetailEntity orderDetail = Optional.ofNullable(pmsOrderDetailMapper.selectById(batchDetail.getOrderDetailId()))
                .orElseThrow(() -> new CheckedException("采购收料批次对应的采购订单行不存在"));
        if (orderDetail.getPmsOrderId() == null) {
            throw new CheckedException("原采购订单行未绑定采购订单，不能生成入库通知");
        }
        PmsOrderEntity order = Optional.ofNullable(pmsOrderService.getById(orderDetail.getPmsOrderId()))
                .orElseThrow(() -> new CheckedException("原采购订单不存在，不能生成入库通知"));
        if (persistedReceipt.getTenantId() == null || StrUtil.isBlank(persistedReceipt.getPmsOrderNo())
                || StrUtil.isBlank(persistedReceipt.getFactoryCode()) || StrUtil.isBlank(persistedReceipt.getSupplierCode())
                || StrUtil.isBlank(batchDetail.getMatCode()) || !Objects.equals(batchDetail.getMatCode(), orderDetail.getMatCode())
                || !Objects.equals(persistedReceipt.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(persistedReceipt.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                || !Objects.equals(persistedReceipt.getTenantId(), orderDetail.getTenantId())
                || !Objects.equals(persistedReceipt.getTenantId(), order.getTenantId())
                || !Objects.equals(persistedReceipt.getPmsOrderNo(), order.getPmsOrderNo())
                || !Objects.equals(persistedReceipt.getFactoryCode(), order.getFactoryCode())
                || (StrUtil.isNotBlank(orderDetail.getFactoryCode())
                        && !Objects.equals(persistedReceipt.getFactoryCode(), orderDetail.getFactoryCode()))
                || !Objects.equals(persistedReceipt.getSupplierCode(), order.getSupplierCode())
                || !Objects.equals(persistedReceipt.getFactoryCode(), receipt.getFactoryCode())
                || !Objects.equals(persistedReceipt.getSupplierCode(), receipt.getSupplierCode())) {
            throw new CheckedException("采购收料单、收料批次与采购订单行来源不一致，请先修复来源数据");
        }

        PmsOrderReceivableQuantity.requireMaterial(orderDetail, batchDetail.getMatCode(), batchDetail.getMatUnit());
        noticeDetail.setSourceOrderNo(order.getPmsOrderNo());
        noticeDetail.setSourceOrderDetailId(batchDetail.getOrderDetailId());
        noticeDetail.setSourceReceiptId(persistedReceipt.getId());
        noticeDetail.setSourceReceiptDetailId(batchDetail.getId());
        noticeDetail.setDocumentId(batchDetail.getId());
        noticeDetail.setDocumentTable("mom_pms.wms_purchase_receipt_batch_detail");
    }
}
