package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteFactoryModelService;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mdm.vo.MdmFactoryModelTreeVO;
import com.kitex.mom.mes.dto.MesMaterialReceiptCommand;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.*;
import com.kitex.mom.mes.exception.MesMaterialReceiptDestinationRejectedException;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.wms.dto.WmsLineDeliveryEvidenceDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 正式WMS来源的通知登记、签收及异人冲销。工单关联写入先参加统一材料执行互斥，
 * 永久签收原号在新生产准入前回读；数量、质量、来源与原指纹合同保持。
 */
@Service
@RequiredArgsConstructor
public class MesMaterialReceiptService {
    private final MesWarehouseMaterialInBillMapper noticeMapper;
    private final MesWarehousePickingBillMapper pickingMapper;
    private final MesWarehousePickingBillDetailMapper pickingDetailMapper;
    private final MesMaterialReceiptMapper receiptMapper;
    private final MesWarehouseLiveStockMapper stockMapper;
    private final MesWarehouseInoutMapper inoutMapper;
    private final MesWarehouseLiveStockService stockService;
    private final RemoteMdmFactoryAccessService factoryAccessService;
    private final RemoteFactoryModelService factoryModelService;
    private final com.kitex.mom.wms.api.feign.RemoteProductionPickingOutBillService wmsSourceService;
    private final MesMaterialExecutionGuard materialExecution;
    private final MesPickingWmsSourceGuard pickingWmsSources;

    public void requireWriteAccess(String factory) {
        access(factory, "write");
    }

    public MesWarehouseMaterialInBillEntity notice(Long id) {
        MesWarehouseMaterialInBillEntity notice = noticeMapper.selectById(id);
        require(notice != null, "收料通知不存在");
        access(notice.getFactoryCode(), "read");
        return notice;
    }

    public com.baomidou.mybatisplus.core.metadata.IPage<MesWarehouseMaterialInBillEntity> page(
            com.kitex.common.core.entity.SearchPage page, String factory, String billNo, String status) {
        access(factory, "read");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<MesWarehouseMaterialInBillEntity> bounded =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(Math.max(1, page.getCurrent()), Math.min(100, Math.max(1, page.getSize())));
        return noticeMapper.selectPage(bounded, new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                .eq(MesWarehouseMaterialInBillEntity::getFactoryCode, factory)
                .like(StrUtil.isNotBlank(billNo), MesWarehouseMaterialInBillEntity::getBillNo, billNo)
                .eq(StrUtil.isNotBlank(status), MesWarehouseMaterialInBillEntity::getStatus, status)
                .orderByDesc(MesWarehouseMaterialInBillEntity::getId));
    }

    /**
     * WMS事实投递及人工导入共用来源锁；重复通知必须校验事实，不允许旧回调覆盖签收量。
     */
    @Transactional(rollbackFor = Exception.class)
    public void registerNotice(MesWarehouseMaterialInBillEntity notice) {
        validateNotice(notice);
        MesWarehousePickingBillEntity picking = notice.getBillId() == null ? null : lockPicking(notice.getBillId());
        registerNoticeLocked(notice, picking);
    }

    /**
     * 同一真实领料的完整回调批次只保护一次父单，逐行仍核来源和累计量。
     * 父单由本方法实际取得，不能让外部上传“已锁实体”充当生产许可；旧回调与新版Inbox共用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void registerPickingNotices(Long pickingId, List<MesWarehouseMaterialInBillEntity> notices) {
        require(notices != null && !notices.isEmpty() &&
                        notices.stream().allMatch(row -> row != null && row.getWmsOutDetailId() != null),
                "WMS发料批次缺少精确出库明细");
        MesWarehousePickingBillEntity picking = lockPicking(pickingId);
        for (MesWarehouseMaterialInBillEntity notice : notices.stream()
                .sorted(Comparator.comparing(MesWarehouseMaterialInBillEntity::getWmsOutDetailId)).toList()) {
            require(StrUtil.isBlank(notice.getFactoryCode()) ||
                            Objects.equals(notice.getFactoryCode(), picking.getFactoryCode()),
                    "WMS发料工厂与领料单不一致");
            notice.setBillId(picking.getId());
            notice.setBillNo(picking.getBillNo());
            notice.setFactoryCode(picking.getFactoryCode());
            validateNotice(notice);
            registerNoticeLocked(notice, picking);
        }
        refreshPickingLocked(picking);
    }

    /** 通知形状和当前可信组织核验，与是否存在生产工单归属分开。 */
    private void validateNotice(MesWarehouseMaterialInBillEntity notice) {
        require(notice != null && notice.getWmsOutDetailId() != null && StrUtil.isNotBlank(notice.getResourceNo())
                        && StrUtil.isNotBlank(notice.getFactoryCode()) && StrUtil.isNotBlank(notice.getMatCode())
                        && notice.getNoticeInNum() != null && notice.getNoticeInNum().signum() > 0,
                "WMS收料通知缺少精确来源、工厂或数量");
        MesQualityGateSourceEvidence.scope();
        require(Objects.equals(notice.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && (notice.getTenantId() == null ||
                        Objects.equals(notice.getTenantId(), TenantContextHolder.getTenantId())),
                "WMS收料来源与当前实际组织不一致");
    }

    /** 仅在本服务已保护的父单下登记；无领料来源必须同时没有父实体，不能伪造绕过门禁。 */
    private void registerNoticeLocked(MesWarehouseMaterialInBillEntity notice, MesWarehousePickingBillEntity picking) {
        require(notice.getBillId() == null ?
                picking == null : picking != null && Objects.equals(notice.getBillId(), picking.getId())
                && Objects.equals(notice.getFactoryCode(), picking.getFactoryCode()), "通知与已保护的原领料不一致");
        receiptMapper.lockSource(notice.getWmsOutDetailId());
        MesWarehouseMaterialInBillEntity existing = noticeMapper.selectOne(
                new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                        .eq(MesWarehouseMaterialInBillEntity::getWmsOutDetailId, notice.getWmsOutDetailId()));
        if (existing != null) {
            require(Objects.equals(sourceFingerprint(existing), sourceFingerprint(notice)),
                    "同一WMS发料明细重复投递的物料、批次、数量或来源不同，禁止覆盖");
            return;
        }
        // 不能把旧无精确来源的同单物料，再作为新通知静默重复导入。
        require(noticeMapper.selectCount(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                        .isNull(MesWarehouseMaterialInBillEntity::getWmsOutDetailId)
                        .eq(MesWarehouseMaterialInBillEntity::getResourceNo, notice.getResourceNo())
                        .eq(MesWarehouseMaterialInBillEntity::getMatCode, notice.getMatCode())) == 0,
                "同一出库单物料存在历史无来源收料记录，先对账迁移再导入");
        if (notice.getBillId() != null) {
            List<MesWarehousePickingBillDetailEntity> requirements = pickingDetailMapper.selectList(
                    new LambdaQueryWrapper<MesWarehousePickingBillDetailEntity>()
                            .eq(MesWarehousePickingBillDetailEntity::getBillId, notice.getBillId()));
            BigDecimal planned = requirements.stream().filter(d -> Objects.equals(materialKey(d), materialKey(notice)))
                    .map(MesWarehousePickingBillDetailEntity::getMatNum).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal issued = noticeMapper.selectList(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                            .eq(MesWarehouseMaterialInBillEntity::getBillId, notice.getBillId())).stream()
                    .filter(n -> Objects.equals(materialKey(n), materialKey(notice)))
                    .map(MesWarehouseMaterialInBillEntity::getNoticeInNum).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(planned.signum() > 0 && issued.add(notice.getNoticeInNum()).compareTo(planned) <= 0,
                    "WMS发料物料/替代关系/仓库/预留与领料单不一致或累计超出申请量");
        }
        notice.setId(null);
        notice.setInNum(BigDecimal.ZERO);
        notice.setReceiptBaselineQuantity(null);
        notice.setInTime(null);
        notice.setStatus(MesMaterialInStateEnum.PENDING.getCode());
        notice.setSiteCode(null);
        notice.setSiteName(null);
        notice.setContainerCode(null);
        notice.setHandleBy(null);
        notice.setHandleByName(null);
        require(noticeMapper.insert(notice) == 1, "待签收通知保存失败");
    }

    private String sourceFingerprint(MesWarehouseMaterialInBillEntity n) {
        return sha256(JSONUtil.toJsonStr(Arrays.asList(n.getBillId(), n.getWmsOutDetailId(), n.getResourceNo(),
                n.getFactoryCode(), n.getMatCode(), n.getMatBatch(), n.getMatBarcode(), number(n.getNoticeInNum()),
                n.getSupplierCode(), n.getMatUnit(), n.getWarehouseCode(), n.getMatProduceDate(), n.getMatValidEndDate(),
                n.getInspectionStatus(), n.getReservationNo(), n.getRequirementMatCode(), number(n.getRequirementMatNum()),
                n.getSubstituteFlag(), number(n.getSubstituteRatio()))));
    }

    private WmsOutBillEntity verifyWmsSource(MesWarehouseMaterialInBillEntity notice) {
        R<WmsOutBillEntity> response = wmsSourceService.sourceByDetailId(notice.getWmsOutDetailId());
        require(response != null && response.isOk() && response.getData() != null,
                "WMS正式发料来源不可核验，本次不入库");
        WmsOutBillEntity bill = response.getData();
        require(!com.kitex.mom.wms.enums.DocumentEnum.MES_PICKING.getCode().equals(bill.getDocumentTable())
                        || bill.getDocumentId() != null,
                "MES生产领料出库缺少原领料单关联，不能按无工单来源签收，请先核对");
        require(com.kitex.mom.wms.enums.OutBillTypeEnum.PRODUCE_OUT.getCode().equals(bill.getBillType())
                && Set.of("2", "12").contains(StrUtil.blankToDefault(bill.getState(), ""))
                && bill.getDetails() != null && bill.getDetails().size() == 1, "WMS发料来源未完成或类型不符");
        com.kitex.mom.wms.entity.WmsOutBillDetailEntity detail = bill.getDetails().get(0);
        require(detail != null && Objects.equals(detail.getId(), notice.getWmsOutDetailId())
                && Objects.equals(detail.getBillId(), bill.getId()) && detail.getMatNum() != null
                && Double.isFinite(detail.getMatNum()) && detail.getMatNum() > 0, "WMS发料来源数量不完整");
        MesWarehouseMaterialInBillEntity live = new MesWarehouseMaterialInBillEntity();
        BeanUtil.copyProperties(detail, live, "id", "billId");
        live.setWmsOutDetailId(detail.getId());
        live.setBillId(com.kitex.mom.wms.enums.DocumentEnum.MES_PICKING.getCode()
                .equals(bill.getDocumentTable()) ? bill.getDocumentId() : null);
        live.setFactoryCode(bill.getFactoryCode());
        live.setResourceNo(bill.getBillNo());
        live.setNoticeInNum(BigDecimal.valueOf(detail.getMatNum()));
        live.setInspectionStatus(detail.getQualityResult());
        require(Objects.equals(sourceFingerprint(notice), sourceFingerprint(live)),
                "MES收料通知与WMS已提交来源不一致，先对账，不按旧回调入库");
        return bill;
    }

    /** 只接受原出库行所属配送任务提供的数量与目的工位证据。 */
    private WmsLineDeliveryEvidenceDTO lineDeliveryEvidence(MesWarehouseMaterialInBillEntity notice,
                                                            WmsOutBillEntity sourceBill) {
        R<WmsLineDeliveryEvidenceDTO> response = wmsSourceService.lineDeliveryByDetailId(notice.getWmsOutDetailId());
        require(response != null && response.isOk() && response.getData() != null, "WMS线边配送到达证据不可核验，本次不入库");
        WmsLineDeliveryEvidenceDTO evidence = response.getData();
        require(sourceBill.getId() != null && Objects.equals(evidence.getOutBillId(), sourceBill.getId())
                        && Objects.equals(evidence.getOutBillDetailId(), notice.getWmsOutDetailId())
                        && Objects.equals(evidence.getFactoryCode(), notice.getFactoryCode())
                        && Objects.equals(evidence.getMatCode(), notice.getMatCode())
                        && evidence.getReceivableNum() != null && evidence.getReceivableNum().signum() >= 0,
                "WMS线边配送证据与MES待签收来源不一致");
        require(StrUtil.isNotBlank(evidence.getTargetSiteCode()), "等待WMS确认配送目的工位后再签收");
        return evidence;
    }

    /**
     * 线上签收、旧端签收与整单签收共用。单件沿原出库流水一次记一件，
     * 普通批料沿原余额分收。历史来源未记管理方式时，只能一次接收剩余量。
     */
    private void requireReceivableQuantity(WmsOutBillEntity bill, MesWarehouseMaterialInBillEntity notice,
                                           BigDecimal received, BigDecimal requested) {
        Boolean serial = bill.getDetails().get(0).getIssuedAsSerial();
        if (Boolean.TRUE.equals(serial)) {
            require(StrUtil.isNotBlank(notice.getMatBarcode()) && received.signum() == 0
                            && notice.getNoticeInNum().compareTo(BigDecimal.ONE) == 0
                            && requested.compareTo(BigDecimal.ONE) == 0,
                    "单件发料须按原序列号一次签收 1 件，请核对出库明细和原签收记录");
        } else if (serial == null) {
            require(received.add(requested).compareTo(notice.getNoticeInNum()) == 0,
                    "原出库行缺少可核验的实物管理方式，分批签收须先核对原流水");
        }
    }

    public List<MesWarehouseMaterialInBillEntity> notices(Long pickingId) {
        MesWarehousePickingBillEntity picking = pickingMapper.selectById(pickingId);
        require(picking != null, "生产领料单不存在");
        access(picking.getFactoryCode(), "read");
        return noticeMapper.selectList(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                .eq(MesWarehouseMaterialInBillEntity::getBillId, pickingId)
                .orderByAsc(MesWarehouseMaterialInBillEntity::getId));
    }

    public List<MesMaterialReceiptEntity> history(Long noticeId) {
        MesWarehouseMaterialInBillEntity notice = noticeMapper.selectById(noticeId);
        require(notice != null, "收料通知不存在");
        access(notice.getFactoryCode(), "read");
        return receiptMapper.selectList(new LambdaQueryWrapper<MesMaterialReceiptEntity>()
                .eq(MesMaterialReceiptEntity::getNoticeId, noticeId)
                .orderByAsc(MesMaterialReceiptEntity::getOperationTime, MesMaterialReceiptEntity::getId));
    }

    public List<MdmFactoryModelTreeVO> sites(Long noticeId) {
        MesWarehouseMaterialInBillEntity notice = noticeMapper.selectById(noticeId);
        require(notice != null, "收料通知不存在");
        access(notice.getFactoryCode(), "read");
        String targetSite = null;
        if (notice.getWmsOutDetailId() != null && StrUtil.isNotBlank(notice.getResourceNo())) {
            WmsOutBillEntity sourceBill = verifyWmsSource(notice);
            if (Boolean.TRUE.equals(sourceBill.getLineDeliveryRequired())) {
                targetSite = lineDeliveryEvidence(notice, sourceBill).getTargetSiteCode();
            }
        }
        return eligibleSites(notice, targetSite);
    }

    /** 有配送目的地时仅返回该处的启用工位，普通发料继续使用当前工厂的工位。 */
    private List<MdmFactoryModelTreeVO> eligibleSites(MesWarehouseMaterialInBillEntity notice, String targetSite) {
        R<List<MdmFactoryModelTreeVO>> result = factoryModelService.getNodesByType("STATION", notice.getFactoryCode());
        require(result != null && result.isOk() && result.getData() != null, "无法核验MDM线边工位");
        return result.getData().stream().filter(node -> Boolean.TRUE.equals(node.getStatus())
                        && "STATION".equals(node.getType()) && Objects.equals(notice.getFactoryCode(), node.getFactoryCode())
                        && (targetSite == null || targetSite.equals(node.getCode())))
                .toList();
    }

    /**
     * 按原号签收并写入线边库存，重复原号先核对载荷并回读已有回执。
     *
     * @param command 带原请求号、工位及预期累计量的签收指令
     * @return 本次签收或同载荷原号的回执
     * @throws MesMaterialReceiptDestinationRejectedException 工位与已核实的WMS配送目的工位不一致
     */
    @Transactional(rollbackFor = Exception.class)
    public MesMaterialReceiptEntity receive(MesMaterialReceiptCommand command) {
        validate(command, false);
        MomUser user = user();
        access(FactoryContextHolder.getFactoryCode(), "write");
        // 原号先串行且只查一次，已接受签收在修订冻结期间仍返回原回执，不制造锁前null缓存。
        materialExecution.request("MATERIAL_RECEIPT", command.getRequestId());
        String fingerprint = fingerprint(command, "RECEIVE");
        MesMaterialReceiptEntity replay = replay(command.getRequestId(), fingerprint);
        if (replay != null) {
            if (command.getRequestId().startsWith("LEGACY-")) {
                requireNoLegacyReversal(command.getRequestId());
            }
            return replay;
        }
        MesWarehouseMaterialInBillEntity notice = lockNotice(command.getNoticeId());
        BigDecimal before = received(notice);
        verifyLedger(notice, before);
        require(before.compareTo(command.getExpectedReceivedQuantity()) == 0, "累计已收量已变化，请刷新后提交新签收");
        require(before.add(command.getQuantity()).compareTo(notice.getNoticeInNum()) <= 0,
                "本批签收超出剩余待收量，溢料须回源核对，禁止直接增库存");
        require(notice.getWmsOutDetailId() != null && StrUtil.isNotBlank(notice.getResourceNo()),
                "缺少精确WMS出库来源，历史通知须先完成来源对账，不得凭手填物料入库");
        WmsOutBillEntity sourceBill = verifyWmsSource(notice);
        requireReceivableQuantity(sourceBill, notice, before, command.getQuantity());
        String targetSite = null;
        if (Boolean.TRUE.equals(sourceBill.getLineDeliveryRequired())) {
            WmsLineDeliveryEvidenceDTO delivery = lineDeliveryEvidence(notice, sourceBill);
            targetSite = delivery.getTargetSiteCode();
            if (!targetSite.equals(command.getSiteCode())) {
                throw new MesMaterialReceiptDestinationRejectedException();
            }
            require(before.add(command.getQuantity()).compareTo(delivery.getReceivableNum()) <= 0,
                    "本批签收超过线边配送已到达/已追回数量；短少、破损、退回或损失不得进入MES库存");
        }
        String quality = StrUtil.blankToDefault(notice.getInspectionStatus(), "").toUpperCase(Locale.ROOT);
        require(Set.of("NOT_REQUIRED", "RELEASED", "QUALIFIED", "RELEASE", "RECEIVE", "EXEMPTION").contains(quality),
                "发料质量结论未明确放行，须先核实质量来源；不合格/让步物料不得按普通可用库存签收");
        require(notice.getMatValidEndDate() == null || !notice.getMatValidEndDate().isBefore(java.time.LocalDate.now()),
                "物料已过有效期，请转质量处置，不能签收入可用线边库存");
        MdmFactoryModelTreeVO site = uniqueSite(notice, command.getSiteCode(), targetSite);
        MesMaterialReceiptEntity receipt = operation(command, notice, user, "RECEIVE", before,
                before.add(command.getQuantity())).setSiteCode(site.getCode()).setSiteName(site.getName());
        Boolean serial = sourceBill.getDetails().get(0).getIssuedAsSerial();
        if (serial != null) {
            // 与原签收一同冻结。此后即使物料配置变更，线边投料仍按出库时的实物身份办理。
            var snapshot = JSONUtil.parseObj(receipt.getSourceSnapshot());
            snapshot.set("issuedAsSerial", serial);
            receipt.setSourceSnapshot(snapshot.toString());
        }
        require(receiptMapper.insert(receipt) == 1, "签收履历保存失败");

        MesWarehouseLiveStockInoutDTO stock = new MesWarehouseLiveStockInoutDTO();
        BeanUtil.copyProperties(notice, stock, "id", "siteCode", "siteName", "containerCode", "containerName");
        stock.setMaterialReceiptId(receipt.getId());
        stock.setBillId(notice.getId());
        stock.setNum(command.getQuantity());
        stock.setSiteCode(site.getCode());
        stock.setSiteName(site.getName());
        stock.setBizType(WarehouseInBizTypeEnum.RECEPTION.getCode());
        stock.setMatDetailType(MesWarehouseMatDetailTypeEnum.RAW.name());
        stock.setInspectionStatus(quality);
        require(Boolean.TRUE.equals(stockService.inStock(stock)), "线边签收入库失败");
        MesWarehouseLiveStockEntity created = stockMapper.selectOne(
                new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                        .eq(MesWarehouseLiveStockEntity::getMaterialReceiptId, receipt.getId()));
        require(created != null, "签收未形成唯一来源库存");
        receipt.setStockFingerprint(stockFingerprint(created));
        require(receiptMapper.updateById(receipt) == 1, "签收库存证据保存失败");
        updateNotice(notice, receipt.getAfterQuantity(), site.getCode(), site.getName(), user);
        refreshPicking(notice.getBillId());
        return receipt;
    }

    @Transactional(rollbackFor = Exception.class)
    public MesMaterialReceiptEntity reverse(MesMaterialReceiptCommand command) {
        validate(command, true);
        MomUser user = user();
        access(FactoryContextHolder.getFactoryCode(), "write");
        materialExecution.request("MATERIAL_RECEIPT", command.getRequestId());
        String fingerprint = fingerprint(command, "REVERSE");
        MesMaterialReceiptEntity replay = replay(command.getRequestId(), fingerprint);
        if (replay != null) {
            return replay;
        }
        MesMaterialReceiptEntity original = receiptMapper.selectOne(new LambdaQueryWrapper<MesMaterialReceiptEntity>()
                .eq(MesMaterialReceiptEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesMaterialReceiptEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MesMaterialReceiptEntity::getId, command.getSourceReceiptId()));
        require(original != null && "RECEIVE".equals(original.getOperationType()), "请选择原始签收记录冲销");
        require(Objects.equals(original.getNoticeId(), command.getNoticeId()), "冲销来源与收料通知不一致");
        MesWarehouseMaterialInBillEntity notice = lockNotice(original.getNoticeId());
        require(!Objects.equals(user.getId(), original.getOperatorId()), "签收冲销须由非原签收人复核执行");
        require(receiptMapper.selectCount(new LambdaQueryWrapper<MesMaterialReceiptEntity>()
                .eq(MesMaterialReceiptEntity::getSourceReceiptId, original.getId())) == 0, "该批签收已冲销");
        BigDecimal before = received(notice);
        verifyLedger(notice, before);
        require(before.compareTo(command.getExpectedReceivedQuantity()) == 0, "累计已收量已变化，请刷新后复核");
        MesWarehouseLiveStockEntity stock = stockMapper.selectOne(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getMaterialReceiptId, original.getId()).last("FOR UPDATE"));
        require(stock != null && original.getQuantity().compareTo(stock.getNum()) == 0
                        && Objects.equals(original.getStockFingerprint(), stockFingerprint(stock)),
                "原签收库存已使用、移动或质量/属性变化，不能冲销；请走实际退料或质量处置");
        require(inoutMapper.selectCount(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                        .eq(MesWarehouseInoutEntity::getMaterialReceiptId, original.getId())
                        .eq(MesWarehouseInoutEntity::getOperateType, InOutEnum.OUT.getCode())) == 0,
                "原签收已有出库事实，不得以补回库存掩盖后再冲销");
        BigDecimal after = before.subtract(original.getQuantity());
        require(after.signum() >= 0, "签收数量账不一致，先对账");
        MesMaterialReceiptEntity reversal = operation(command, notice, user, "REVERSE", before, after)
                .setQuantity(original.getQuantity()).setSiteCode(original.getSiteCode())
                .setSiteName(original.getSiteName())
                .setStockFingerprint(original.getStockFingerprint());
        require(receiptMapper.insert(reversal) == 1, "冲销履历保存失败");
        MesWarehouseInoutEntity movement = new MesWarehouseInoutEntity();
        BeanUtil.copyProperties(stock, movement, "id", "createBy", "createTime", "updateBy", "updateTime", "delFlag");
        movement.setMaterialReceiptId(reversal.getId());
        movement.setBillId(notice.getId());
        movement.setBillNo(notice.getBillNo());
        movement.setResourceNo(notice.getResourceNo());
        movement.setOperateType(InOutEnum.OUT.getCode());
        movement.setOperateNum(original.getQuantity());
        movement.setBizType(WarehouseInBizTypeEnum.RECEPTION_REVERSE.getCode());
        require(stockMapper.deleteById(stock.getId()) == 1 && inoutMapper.insert(movement) == 1,
                "签收冲销库存操作失败");
        updateNotice(notice, after, notice.getSiteCode(), notice.getSiteName(), user);
        refreshPicking(notice.getBillId());
        return reversal;
    }

    /**
     * 旧单行入口仍用累计量；固定请求号确保旧客户端重放不产生第二批库存。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean receiveLegacy(MesWarehouseMaterialInBillEntity input) {
        require(input != null && input.getId() != null && input.getInNum() != null, "缺少收料通知和实际数量");
        receive(new MesMaterialReceiptCommand().setNoticeId(input.getId()).setQuantity(input.getInNum())
                .setExpectedReceivedQuantity(BigDecimal.ZERO).setSiteCode(input.getSiteCode())
                .setRequestId("LEGACY-LINE-" + input.getId()).setReason("旧单行入口首次签收"));
        return true;
    }

    /**
     * 整单旧入口只接收尚未签收过的整行，已有部分签收须进入明细工作台。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean receiveAll(Long pickingId, String siteCode) {
        MesWarehousePickingBillEntity picking = lockPicking(pickingId);
        access(picking.getFactoryCode(), "write");
        List<MesWarehouseMaterialInBillEntity> notices = noticeMapper.selectList(
                new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                        .eq(MesWarehouseMaterialInBillEntity::getBillId, pickingId)
                        .orderByAsc(MesWarehouseMaterialInBillEntity::getId));
        require(!notices.isEmpty(), "没有可签收的WMS发料通知");
        for (MesWarehouseMaterialInBillEntity notice : notices) {
            BigDecimal received = received(notice);
            if (received.compareTo(notice.getNoticeInNum()) == 0) {
                continue;
            }
            require(received.signum() == 0, "已有部分签收，请进入签收明细按剩余量继续接收");
            receive(new MesMaterialReceiptCommand().setNoticeId(notice.getId()).setQuantity(notice.getNoticeInNum())
                    .setExpectedReceivedQuantity(BigDecimal.ZERO).setSiteCode(siteCode)
                    .setRequestId("LEGACY-BILL-" + notice.getId()).setReason("旧整单入口首次签收"));
        }
        refreshPicking(pickingId);
        return true;
    }

    /** 有领料来源先取真实工单锁，再锁通知；合法无领料来源保持无根并沿原来源门禁。 */
    private MesWarehouseMaterialInBillEntity lockNotice(Long id) {
        return materialExecution.notice(id);
    }

    private void requireNoLegacyReversal(String requestId) {
        MesMaterialReceiptEntity original = receiptMapper.selectOne(new LambdaQueryWrapper<MesMaterialReceiptEntity>()
                .eq(MesMaterialReceiptEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesMaterialReceiptEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MesMaterialReceiptEntity::getRequestId, requestId));
        require(original == null || receiptMapper.selectCount(new LambdaQueryWrapper<MesMaterialReceiptEntity>()
                        .eq(MesMaterialReceiptEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MesMaterialReceiptEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                        .eq(MesMaterialReceiptEntity::getSourceReceiptId, original.getId())) == 0,
                "旧入口原签收已冲销，请在签收工作台按新批次重新交接，不可重用旧请求");
    }

    private void verifyLedger(MesWarehouseMaterialInBillEntity notice, BigDecimal received) {
        List<MesMaterialReceiptEntity> ledger = receiptMapper.selectList(new LambdaQueryWrapper<MesMaterialReceiptEntity>()
                .eq(MesMaterialReceiptEntity::getNoticeId, notice.getId()));
        if (notice.getReceiptBaselineQuantity() == null) {
            require(ledger.isEmpty(), "签收账缺少历史基线，先对账");
            notice.setReceiptBaselineQuantity(received);
            require(noticeMapper.update(null, new LambdaUpdateWrapper<MesWarehouseMaterialInBillEntity>()
                    .eq(MesWarehouseMaterialInBillEntity::getId, notice.getId())
                    .set(MesWarehouseMaterialInBillEntity::getReceiptBaselineQuantity, received)) == 1, "签收历史基线冻结失败");
        }
        BigDecimal net = ledger.stream().map(r -> "REVERSE".equals(r.getOperationType()) ? r.getQuantity().negate() : r.getQuantity())
                .reduce(notice.getReceiptBaselineQuantity(), BigDecimal::add);
        require(net.compareTo(received) == 0, "签收履历净量与通知累计量不一致，请先对账，禁止继续叠加");
    }

    /** 兼容外层整单入口，同样不能先锁材料父单再补工单互斥。 */
    private MesWarehousePickingBillEntity lockPicking(Long id) {
        MesWarehousePickingBillEntity picking = materialExecution.picking(id);
        // 远端实际发料可先于本地受理apply；只允许已持久准备的准确V1来源，仍由后续实际WMS明细核验。
        require(picking != null && (!MesPickingBillStateEnum.CREATED.getCode().equals(picking.getStatus())
                || pickingWmsSources.acceptsEarlyIssue(id)), "领料单不存在或未正式提交");
        return picking;
    }

    /** 通知登记、签收及冲销的同事务结果回写；独立调用也先保护原工单归属。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void refreshPicking(Long id) {
        if (id == null) {
            return;
        }
        refreshPickingLocked(lockPicking(id));
    }

    /** 同服务已取得实际父单锁的批次汇总，不逐行重走目录核验；独立入口仍先经过lockPicking。 */
    private void refreshPickingLocked(MesWarehousePickingBillEntity picking) {
        Long id = picking.getId();
        List<MesWarehouseMaterialInBillEntity> notices = noticeMapper.selectList(
                new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
                        .eq(MesWarehouseMaterialInBillEntity::getBillId, id));
        List<MesWarehousePickingBillDetailEntity> requirements = pickingDetailMapper.selectList(
                new LambdaQueryWrapper<MesWarehousePickingBillDetailEntity>().eq(
                        MesWarehousePickingBillDetailEntity::getBillId, id));
        Map<String, BigDecimal> expected = new HashMap<>(), supplied = new HashMap<>();
        for (MesWarehousePickingBillDetailEntity d : requirements) {
            require(d.getMatNum() != null && d.getMatNum().signum() > 0, "领料申请数量异常");
            expected.merge(materialKey(d), d.getMatNum(), BigDecimal::add);
        }
        for (MesWarehouseMaterialInBillEntity n : notices) {
            supplied.merge(materialKey(n), n.getNoticeInNum(), BigDecimal::add);
        }
        boolean allIssued = !expected.isEmpty() && expected.keySet().equals(supplied.keySet())
                && expected.entrySet().stream().allMatch(e -> e.getValue().compareTo(supplied.get(e.getKey())) == 0);
        for (MesWarehousePickingBillDetailEntity d : requirements) {
            boolean issued =
                    expected.get(materialKey(d)).compareTo(supplied.getOrDefault(materialKey(d), BigDecimal.ZERO)) == 0;
            require(pickingDetailMapper.update(null, new LambdaUpdateWrapper<MesWarehousePickingBillDetailEntity>()
                            .eq(MesWarehousePickingBillDetailEntity::getId, d.getId())
                            .set(MesWarehousePickingBillDetailEntity::getStatus,
                                    issued ? YesNoEnum.YES.getCode() : YesNoEnum.NO.getCode())) == 1,
                    "领料行发料状态更新失败");
        }
        boolean complete = allIssued && !notices.isEmpty() && notices.stream()
                .allMatch(n -> n.getNoticeInNum() != null && received(n).compareTo(n.getNoticeInNum()) == 0);
        require(pickingMapper.update(null, new LambdaUpdateWrapper<MesWarehousePickingBillEntity>()
                .eq(MesWarehousePickingBillEntity::getId, id)
                .set(MesWarehousePickingBillEntity::getStatus, complete ? MesPickingBillStateEnum.FINISHED.getCode()
                        : MesPickingBillStateEnum.PENDING.getCode())) == 1, "领料单接收状态回写失败");
    }

    private String materialKey(MesWarehousePickingBillDetailEntity d) {
        return JSONUtil.toJsonStr(Arrays.asList(d.getMatCode(), StrUtil.blankToDefault(d.getRequirementMatCode(), d.getMatCode()),
                StrUtil.emptyToDefault(d.getWarehouseCode(), ""), StrUtil.emptyToDefault(d.getReservationNo(), ""),
                Boolean.TRUE.equals(d.getSubstituteFlag()), number(d.getSubstituteRatio() == null ? BigDecimal.ONE : d.getSubstituteRatio())));
    }

    private String materialKey(MesWarehouseMaterialInBillEntity d) {
        return JSONUtil.toJsonStr(Arrays.asList(d.getMatCode(), StrUtil.blankToDefault(d.getRequirementMatCode(), d.getMatCode()),
                StrUtil.emptyToDefault(d.getWarehouseCode(), ""), StrUtil.emptyToDefault(d.getReservationNo(), ""),
                Boolean.TRUE.equals(d.getSubstituteFlag()), number(d.getSubstituteRatio() == null ? BigDecimal.ONE : d.getSubstituteRatio())));
    }

    private void updateNotice(MesWarehouseMaterialInBillEntity notice, BigDecimal total, String site, String name, MomUser user) {
        require(noticeMapper.update(null, new LambdaUpdateWrapper<MesWarehouseMaterialInBillEntity>()
                .eq(MesWarehouseMaterialInBillEntity::getId, notice.getId())
                .set(MesWarehouseMaterialInBillEntity::getInNum, total)
                .set(MesWarehouseMaterialInBillEntity::getSiteCode, site).set(MesWarehouseMaterialInBillEntity::getSiteName, name)
                .set(MesWarehouseMaterialInBillEntity::getInTime, LocalDateTime.now())
                .set(MesWarehouseMaterialInBillEntity::getHandleBy, user.getUsername())
                .set(MesWarehouseMaterialInBillEntity::getHandleByName, user.getName())
                .set(MesWarehouseMaterialInBillEntity::getStatus, total.signum() == 0 ? MesMaterialInStateEnum.PENDING.getCode()
                        : total.compareTo(notice.getNoticeInNum()) == 0 ? MesMaterialInStateEnum.ALREADY.getCode()
                        : MesMaterialInStateEnum.PARTIAL.getCode())) == 1, "累计签收数量回写失败");
    }

    private MesMaterialReceiptEntity operation(MesMaterialReceiptCommand c, MesWarehouseMaterialInBillEntity n,
                                               MomUser u, String type, BigDecimal before, BigDecimal after) {
        return new MesMaterialReceiptEntity().setNoticeId(n.getId()).setPickingBillId(n.getBillId())
                .setWmsOutDetailId(n.getWmsOutDetailId()).setRequestId(c.getRequestId())
                .setRequestFingerprint(fingerprint(c, type)).setOperationType(type).setSourceReceiptId(c.getSourceReceiptId())
                .setQuantity(c.getQuantity()).setBeforeQuantity(before).setAfterQuantity(after).setReason(c.getReason())
                .setOperatorId(u.getId()).setOperatorCode(u.getUsername()).setOperationTime(LocalDateTime.now())
                .setSourceSnapshot(JSONUtil.toJsonStr(n)).setFactoryCode(n.getFactoryCode());
    }

    private MesMaterialReceiptEntity replay(String requestId, String hash) {
        MesMaterialReceiptEntity row = receiptMapper.selectOne(new LambdaQueryWrapper<MesMaterialReceiptEntity>()
                .eq(MesMaterialReceiptEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesMaterialReceiptEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MesMaterialReceiptEntity::getRequestId, requestId));
        if (row != null) {
            require(Objects.equals(hash, row.getRequestFingerprint()), "同一签收请求号不能用于不同操作或载荷");
        }
        return row;
    }

    private String fingerprint(MesMaterialReceiptCommand c, String type) {
        return sha256(JSONUtil.toJsonStr(Arrays.asList(type, c.getNoticeId(), c.getSourceReceiptId(),
                number(c.getQuantity()), number(c.getExpectedReceivedQuantity()), c.getSiteCode(), c.getReason())));
    }

    private String stockFingerprint(MesWarehouseLiveStockEntity stock) {
        Map<String, Object> values = new TreeMap<>(BeanUtil.beanToMap(stock));
        for (String key : List.of("createTime", "updateTime", "createBy", "updateBy")) values.remove(key);
        values.replaceAll((key, value) -> value instanceof BigDecimal decimal ? number(decimal) : value);
        return sha256(JSONUtil.toJsonStr(values));
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private BigDecimal received(MesWarehouseMaterialInBillEntity n) {
        BigDecimal received = n.getInNum() == null ? BigDecimal.ZERO : n.getInNum();
        require(n.getNoticeInNum() != null && n.getNoticeInNum().signum() > 0 && received.signum() >= 0
                && received.compareTo(n.getNoticeInNum()) <= 0, "历史收料数量异常，须先对账");
        require(!MesMaterialInStateEnum.ALREADY.getCode().equals(n.getStatus()) || received.compareTo(n.getNoticeInNum()) == 0,
                "历史已完成收料存在短收，须先核实原库存与来源，不自动补入");
        return received;
    }

    private void validate(MesMaterialReceiptCommand c, boolean reverse) {
        require(c != null && c.getNoticeId() != null, "请选择收料通知");
        c.setRequestId(required(c.getRequestId(), 64, "幂等号"));
        c.setReason(required(c.getReason(), 500, "交接/冲销说明"));
        require(c.getExpectedReceivedQuantity() != null && c.getExpectedReceivedQuantity().signum() >= 0, "缺少页面累计量，请刷新");
        if (reverse) {
            require(c.getSourceReceiptId() != null && c.getQuantity() == null && StrUtil.isBlank(c.getSiteCode()), "冲销仅可引用原签收，不可重填数量或工位");
        } else {
            require(c.getSourceReceiptId() == null && c.getQuantity() != null && c.getQuantity().signum() > 0
                            && c.getQuantity().stripTrailingZeros().scale() <= 6 && c.getQuantity().precision() - c.getQuantity().scale() <= 16,
                    "本批数量须大于零且最多6位小数，不可带冲销来源");
            c.setSiteCode(required(c.getSiteCode(), 100, "线边工位"));
        }
    }

    private MdmFactoryModelTreeVO uniqueSite(MesWarehouseMaterialInBillEntity notice, String code, String targetSite) {
        List<MdmFactoryModelTreeVO> matches = eligibleSites(notice, targetSite).stream()
                .filter(site -> Objects.equals(site.getCode(), code)).toList();
        require(matches.size() == 1, "工位不存在、停用、跨厂或编码不唯一");
        return matches.get(0);
    }

    private MomUser user() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()), "签收操作须由已登录人员执行");
        return user;
    }

    private void access(String factory, String permission) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected) || Objects.equals(factory, selected)), "收料工厂与当前范围不一致");
        R<Boolean> response = factoryAccessService.check(new MdmFactoryAccessCheck().setUserId(user().getId())
                .setFactoryCode(factory).setRequiredPermission(permission));
        require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()), "未获该工厂" + permission + "授权或MDM无法核验");
    }

    private String required(String value, int max, String label) {
        require(StrUtil.isNotBlank(value) && value.trim().length() <= max, label + "为空或过长");
        return value.trim();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
