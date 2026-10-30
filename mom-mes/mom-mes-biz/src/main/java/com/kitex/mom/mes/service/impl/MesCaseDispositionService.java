package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesCaseDispositionCommand;
import com.kitex.mom.mes.dto.MesInventoryQualityOrigin;
import com.kitex.mom.mes.entity.MesCaseDispositionReceiptEntity;
import com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.mapper.MesCaseDispositionReceiptMapper;
import com.kitex.mom.mes.mapper.MesInventoryQualityCaseMapper;
import com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper;
import com.kitex.mom.mes.mapper.MesWipTransferMapper;
import com.kitex.mom.mes.service.MesProductionNgCaptureService;
import com.kitex.mom.qms.api.feign.RemoteQmsMesCaseDecisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 按已提交QMS决策执行；案件原始事实不改写，库存变动由不可变回执证明。
 */
@Service
@RequiredArgsConstructor
public class MesCaseDispositionService {
    private final MesActualFactoryAccessService access;
    private final MesInventoryQualityCaseMapper cases;
    private final MesCaseDispositionReceiptMapper receipts;
    private final MesWarehouseLiveStockMapper stocks;
    private final MesWipTransferMapper transfers;
    private final RemoteQmsMesCaseDecisionService decisions;
    private final MesProductionNgCaptureService productionNg;
    @org.springframework.beans.factory.annotation.Value("${mom.quality-receipt-reconcile.read-secret:${MOM_MES_RECEIPT_READ_SECRET:}}")
    private String receiptReadSecret;

    @Transactional(rollbackFor = Exception.class)
    public com.kitex.mom.mes.dto.MesCaseReceiptBundle chain(Long caseId, Long ncrId, Long detailId, String key) {
        return receiptBundle(access.require("write"), caseId, ncrId, detailId, key);
    }

    @Transactional(rollbackFor = Exception.class)
    public com.kitex.mom.mes.dto.MesCaseReceiptBundle readSigned(com.kitex.mom.mes.dto.MesCaseReceiptRead r) {
        require(r != null && r.getTenantId() != null && r.getTenantId().equals(TenantContextHolder.getTenantId())
                && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(r.getFactoryCode()) && r.getFactoryCode().equals(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode()), "回执只读请求必须指定一致的租户和工厂");
        long now = java.time.Instant.now().getEpochSecond();
        require(r.getIssuedAt() != null && r.getIssuedAt() >= now - 90 && r.getIssuedAt() <= now + 15
                && r.getNonce() != null && r.getNonce().matches("[A-Za-z0-9_-]{16,64}") && r.signatureMatches(receiptReadSecret), "回执只读签名无效或已过期");
        return receiptBundle(r.getFactoryCode(), r.getCaseId(), r.getNcrId(), r.getDetailId(), r.getDecisionKey());
    }

    private com.kitex.mom.mes.dto.MesCaseReceiptBundle receiptBundle(String factory, Long caseId, Long ncrId, Long detailId, String key) {
        require(caseId != null && ncrId != null && detailId != null && StrUtil.isNotBlank(key) && key.length() <= 64, "回执读取须提供精确案件、NCR、分类和决策键");
        // 与实物执行共用案件行锁，保证根库存和回执集合来自同一提交边界。
        var c = cases.selectOne(caseScope(factory).eq(MesInventoryQualityCaseEntity::getId, caseId).last("FOR UPDATE"));
        require(c != null, "MES案件不存在");
        var all = history(c);
        reconcile(c, all);
        var chain = all.stream().filter(x -> Objects.equals(x.getNcrId(), ncrId) && Objects.equals(x.getDetailId(), detailId) && key.equals(x.getDecisionKey())).toList();
        require(chain.size() <= 2 && (chain.isEmpty() || "EXECUTE".equals(chain.get(0).getStage()))
                && (chain.size() < 2 || Objects.equals(chain.get(1).getExecutionReceiptId(), chain.get(0).getId()) && Set.of("VERIFY_PASS", "VERIFY_REJECT").contains(chain.get(1).getStage())), "MES决策回执链不完整，禁止按最新状态猜测恢复");
        var result = new com.kitex.mom.mes.dto.MesCaseReceiptBundle();
        result.setSource(c);
        result.setReceipts(chain);
        return result;
    }

    public MesInventoryQualityCaseEntity source(Long id) {
        String factory = access.require("write");
        var c = cases.selectOne(caseScope(factory).eq(MesInventoryQualityCaseEntity::getId, id));
        require(c != null, "当前工厂质量案件不存在");
        reconcile(c, history(c));
        return c;
    }

    public MesCaseDispositionReceiptEntity latest(Long detailId, String key) {
        String factory = access.require("write");
        require(detailId != null && StrUtil.isNotBlank(key), "须提供精确QMS明细和决策键");
        return receipts.selectOne(scope(factory).eq(MesCaseDispositionReceiptEntity::getDetailId, detailId)
                .eq(MesCaseDispositionReceiptEntity::getDecisionKey, key).orderByDesc(MesCaseDispositionReceiptEntity::getSequenceNo).last("LIMIT 1"));
    }

    @Transactional(rollbackFor = Exception.class)
    public MesCaseDispositionReceiptEntity execute(MesCaseDispositionCommand command) {
        String factory = access.require("write");
        var actor = SecurityUtils.getUser();
        require(command != null && command.getDetailId() != null && StrUtil.isNotBlank(command.getDecisionKey()) && command.getDecisionKey().length() <= 64, "须提供QMS明细和决策键");
        require(command.getRequestId() != null && command.getRequestId().matches("[A-Za-z0-9_-]{16,64}"), "操作请求号无效");
        require(Set.of("EXECUTE", "VERIFY_PASS", "VERIFY_REJECT").contains(Objects.toString(command.getStage(), "")), "MES处置阶段无效");
        require(Boolean.TRUE.equals(command.getConfirmed()) && StrUtil.isNotBlank(command.getEvidence()) && command.getEvidence().length() <= 1000, "须确认现场执行/独立验证并填写1000字以内证据");
        command.setEvidence(command.getEvidence().trim());
        if (command.getActualQuantity() != null)
            command.setActualQuantity(command.getActualQuantity().stripTrailingZeros());
        var intent = new LinkedHashMap<String, Object>();
        intent.put("detail", command.getDetailId());
        intent.put("decision", command.getDecisionKey());
        intent.put("stage", command.getStage());
        intent.put("evidence", command.getEvidence());
        intent.put("quantity", command.getActualQuantity());
        intent.put("location", command.getLocationCode());
        intent.put("barcode", command.getBarcode());
        intent.put("actor", actor.getId());
        String fingerprint = hash(JSONUtil.toJsonStr(intent));
        transfers.lockRequest("MES-CASE-COMMAND:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + command.getRequestId());
        var replay = receipts.selectOne(scope(factory).eq(MesCaseDispositionReceiptEntity::getRequestId, command.getRequestId()));
        if (replay != null) {
            require(fingerprint.equals(replay.getFingerprint()), "请求号已用于其他处置内容或人员");
            return replay;
        }
        // 决策先在QMS提交，本接口只读权威来源，不接受客户端重填物料、数量或MRB类型。
        var response = decisions.decision(command.getDetailId());
        require(response != null && response.isOk() && response.getData() != null, "无法核实已提交的QMS处置决策");
        var d = response.getData().getDetail();
        var n = response.getData().getNcr();
        require(d != null && n != null && Objects.equals(d.getId(), command.getDetailId()) && Objects.equals(d.getBadId(), n.getId())
                && MesInventoryQualityOrigin.isMesResource(n.getResourceType()) && Objects.equals(n.getResourceType(), d.getResourceType())
                && Objects.equals(n.getResourceTaskId(), d.getResourceTaskId())
                && Objects.equals(command.getDecisionKey(), d.getMesDecisionKey()) && factory.equals(n.getFactoryCode()) && factory.equals(d.getFactoryCode())
                && Objects.equals(n.getTenantId(), TenantContextHolder.getTenantId()) && Objects.equals(d.getTenantId(), TenantContextHolder.getTenantId()), "QMS决策来源、版本或归属不一致");
        require(Set.of("RELEASE", "CONCESSION", "SMASH").contains(Objects.toString(d.getHandleResult(), "")), "当前MES处置支持放行、让步接收和报废；返工/退货须独立业务链");
        require(!"CONCESSION".equals(d.getHandleResult()) || Boolean.TRUE.equals(d.getMesConcessionUnrestricted()) && StrUtil.isNotBlank(d.getHandleFile()), "让步授权不完整或有客户/订单范围限制，不得恢复通用可用库存");
        var c = cases.selectOne(caseScope(factory).eq(MesInventoryQualityCaseEntity::getId, n.getResourceTaskId()).last("FOR UPDATE"));
        require(c != null && Objects.equals(MesInventoryQualityOrigin.resourceType(c), n.getResourceType())
                && c.getQuantity().compareTo(n.getNum()) == 0 && Objects.equals(c.getMatCode(), n.getMatCode())
                && Objects.equals(c.getMatBatch(), n.getBatchNo()) && Objects.equals(c.getMatUnit(), n.getQuantityUnit())
                && Objects.equals(c.getMatCode(), d.getMatCode()) && Objects.equals(c.getMatBatch(), d.getBatchNo()) && Objects.equals(c.getMatUnit(), d.getQuantityUnit())
                && Objects.equals(c.getLocationCode(), n.getLocationCode()), "QMS决策与MES原始实物事实不一致");
        var history = history(c);
        var root = reconcile(c, history);
        boolean executing = "EXECUTE".equals(command.getStage());
        var previous = history.stream().filter(r -> command.getDecisionKey().equals(r.getDecisionKey()) && (executing ? "EXECUTE".equals(r.getStage()) : !"EXECUTE".equals(r.getStage()))).findFirst().orElse(null);
        if (previous != null) {
            require(fingerprint.equals(previous.getFingerprint()), "本决策阶段已有不同内容或人员的永久回执，请同步原回执，不得重复执行");
            return previous;
        }
        String snapshot = com.kitex.mom.qms.dto.QmsMesCaseDecisionSnapshot.of(n, d);
        BigDecimal quantity = d.getJudgeNum();
        require(quantity != null && quantity.signum() > 0 && quantity.compareTo(c.getQuantity()) <= 0, "QMS决策数量不合法");
        require(!Boolean.TRUE.equals(c.getIndivisible()) || quantity.compareTo(c.getQuantity()) == 0, "标识型案件须整件/整卡/整包处置，不能按分类拆造子标识");
        require(StrUtil.isNotBlank(d.getHandlePersonId()) && !d.getHandlePersonId().equals(actor.getUsername()), "现场执行/验证人与MRB决策人必须独立");
        require(d.getMesDeciderUserId() != null && !Objects.equals(d.getMesDeciderUserId(), actor.getId()), "现场执行/验证人与原决策人员主键必须不同，账号更名不能绕过");
        var execution = history.stream().filter(r -> command.getDecisionKey().equals(r.getDecisionKey()) && "EXECUTE".equals(r.getStage())).findFirst().orElse(null);
        if (executing) {
            require("WAIT_EXECUTION".equals(d.getStatus()), "QMS当前不是待MES执行状态");
            require(command.getActualQuantity() != null && command.getActualQuantity().compareTo(quantity) == 0, "实际执行数量须与该分类决策量一致；分量须先分类");
            require(Objects.equals(c.getLocationCode(), command.getLocationCode()) && Objects.equals(StrUtil.blankToDefault(c.getMatBarcode(), ""), StrUtil.blankToDefault(command.getBarcode(), "")), "现场库位或实物条码不一致");
            require(root.getNum().subtract(c.getReservedQuantity()).compareTo(quantity) >= 0, "剩余隔离数量已被其他执行占用或已处置");
        } else {
            require("WAIT_VERIFY".equals(d.getStatus()) && execution != null, "须先完成现场执行并同步QMS后才能独立验证");
            require(snapshot.equals(execution.getDecisionSnapshot()), "原MRB决策已变化，不能用旧执行回执验证");
            require(!Objects.equals(actor.getId(), execution.getOperatorId()), "验证人不能是现场执行人");
            require(!("VERIFY_REJECT".equals(command.getStage()) && "SMASH".equals(d.getHandleResult())), "报废实物已执行，不能通过驳回恢复库存；须登记后续异常");
        }
        if (!"SMASH".equals(d.getHandleResult()) && !"VERIFY_REJECT".equals(command.getStage()))
            require(root.getMatValidEndDate() == null || !root.getMatValidEndDate().isBefore(LocalDate.now()), "已过期物料不能通过普通放行或让步恢复可用，须专门效期处置");
        boolean deduct = executing && "SMASH".equals(d.getHandleResult()) || "VERIFY_PASS".equals(command.getStage()) && !"SMASH".equals(d.getHandleResult());
        var receipt = new MesCaseDispositionReceiptEntity();
        receipt.setId(IdUtil.getSnowflakeNextId());
        receipt.setTenantId(c.getTenantId());
        receipt.setFactoryCode(factory);
        receipt.setCaseId(c.getId());
        receipt.setDetailId(d.getId());
        receipt.setNcrId(n.getId());
        receipt.setDecisionKey(command.getDecisionKey());
        receipt.setDecisionSnapshot(snapshot);
        receipt.setRequestId(command.getRequestId());
        receipt.setFingerprint(fingerprint);
        receipt.setStage(command.getStage());
        receipt.setAction(d.getHandleResult());
        receipt.setCommandSnapshot(JSONUtil.toJsonStr(command));
        receipt.setSequenceNo((long) history.size() + 1);
        receipt.setExecutionReceiptId(executing ? null : execution.getId());
        receipt.setPreviousStockReceiptId(root.getQualityDispositionReceiptId());
        receipt.setOperatorId(actor.getId());
        receipt.setOperatorCode(actor.getUsername());
        receipt.setDeciderCode(d.getHandlePersonId());
        receipt.setEvidence(command.getEvidence());
        receipt.setDeciderId(d.getMesDeciderUserId());
        receipt.setQuantity(quantity);
        receipt.setBeforeQuantity(root.getNum());
        receipt.setAfterQuantity(deduct ? root.getNum().subtract(quantity) : root.getNum());
        MesWarehouseLiveStockEntity result = null;
        if (deduct && !"SMASH".equals(d.getHandleResult())) {
            result = new MesWarehouseLiveStockEntity();
            BeanUtils.copyProperties(root, result, "id", "createBy", "createTime", "updateBy", "updateTime", "delFlag", "qualityCaseId", "qualityDispositionReceiptId", "qualityOriginCaseId", "qualityReleaseReceiptId");
            result.setId(IdUtil.getSnowflakeNextId());
            result.setNum(quantity);
            result.setStatus("0");
            result.setInspectionStatus("RELEASED");
            result.setJudgeStatus(null);
            result.setQualityOriginCaseId(c.getId());
            result.setQualityReleaseReceiptId(receipt.getId());
            receipt.setResultStockId(result.getId());
        }
        require(receipts.insert(receipt) == 1, "MES处置回执保存失败");
        if (deduct) {
            require(receipt.getAfterQuantity().signum() >= 0, "隔离余量不足");
            var update = new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>().eq(MesWarehouseLiveStockEntity::getId, root.getId())
                    .eq(MesWarehouseLiveStockEntity::getTenantId, c.getTenantId()).eq(MesWarehouseLiveStockEntity::getFactoryCode, factory)
                    .eq(MesWarehouseLiveStockEntity::getNum, root.getNum()).set(MesWarehouseLiveStockEntity::getNum, receipt.getAfterQuantity())
                    .set(MesWarehouseLiveStockEntity::getQualityDispositionReceiptId, receipt.getId());
            if (receipt.getAfterQuantity().signum() == 0) update.set(MesWarehouseLiveStockEntity::getDelFlag, "1");
            require(stocks.update(null, update) == 1, "隔离库存扣减失败，已回滚");
            var stored = receipts.rootStock(root.getId(), c.getTenantId(), factory);
            require(stored != null && stored.getNum().compareTo(receipt.getAfterQuantity()) == 0, "隔离余量数据库精度不一致");
        }
        if (result != null) {
            require(stocks.insert(result) == 1, "放行库存保存失败");
            var saved = stocks.selectById(result.getId());
            require(saved != null && saved.getNum().compareTo(quantity) == 0, "放行数量数据库精度不一致");
        }
        if (deduct && "PRODUCTION_NG".equals(c.getOriginType()))
            productionNg.dispositionFlows(c, root, result, receipt);
        return receipt;
    }

    private List<MesCaseDispositionReceiptEntity> history(MesInventoryQualityCaseEntity c) {
        var list = receipts.selectList(scope(c.getFactoryCode()).eq(MesCaseDispositionReceiptEntity::getCaseId, c.getId()).orderByAsc(MesCaseDispositionReceiptEntity::getSequenceNo).last("LIMIT 5001"));
        require(list.size() <= 5000, "案件处置履历超过5000条，须受控扩容，不能截断数量证据");
        return list;
    }

    private MesWarehouseLiveStockEntity reconcile(MesInventoryQualityCaseEntity c, List<MesCaseDispositionReceiptEntity> history) {
        var s = receipts.rootStock(c.getStockId(), c.getTenantId(), c.getFactoryCode());
        BigDecimal deducted = BigDecimal.ZERO, reserved = BigDecimal.ZERO, closed = BigDecimal.ZERO;
        Set<Long> verified = new HashSet<>();
        for (var r : history) if (r.getExecutionReceiptId() != null) verified.add(r.getExecutionReceiptId());
        for (var r : history) {
            deducted = deducted.add(r.getBeforeQuantity().subtract(r.getAfterQuantity()));
            if ("EXECUTE".equals(r.getStage()) && !"SMASH".equals(r.getAction()) && !verified.contains(r.getId()))
                reserved = reserved.add(r.getQuantity());
            if ("VERIFY_PASS".equals(r.getStage())) closed = closed.add(r.getQuantity());
        }
        require(s != null && Objects.equals(s.getQualityCaseId(), c.getId()) && s.getNum() != null && s.getNum().signum() >= 0
                && s.getNum().add(deducted).compareTo(c.getQuantity()) == 0 && reserved.compareTo(s.getNum()) <= 0 && closed.compareTo(c.getQuantity()) <= 0
                && "1".equals(s.getStatus()) && "PENDING".equals(s.getInspectionStatus()) && StrUtil.isBlank(s.getJudgeStatus())
                && (s.getNum().signum() == 0 ? "1" : "0").equals(s.getDelFlag())
                && Objects.equals(s.getSourceReportId(), c.getSourceReportId()) && Objects.equals(s.getMatCode(), c.getMatCode())
                && Objects.equals(s.getMatBatch(), c.getMatBatch()) && Objects.equals(s.getMatBarcode(), c.getMatBarcode())
                && Objects.equals(s.getMatUnit(), c.getMatUnit()) && Objects.equals(s.getPhysicalLocationCode(), c.getLocationCode()), "隔离原始数量、剩余库存与处置回执不守恒，禁止继续处置或登记");
        if (MesInventoryQualityOrigin.PRODUCTION_RESOURCE.equals(MesInventoryQualityOrigin.resourceType(c))) {
            c.setIndivisible(productionNg.validateOrigin(c, s));
        } else {
            var transfer = transfers.selectById(c.getTransferId());
            require(transfer != null, "质量案件原转运单缺失");
            c.setIndivisible(transfer.getIndivisible());
        }
        c.setRemainingQuantity(s.getNum());
        c.setReservedQuantity(reserved);
        c.setClosedQuantity(closed);
        return s;
    }

    private LambdaQueryWrapper<MesInventoryQualityCaseEntity> caseScope(String factory) {
        return new LambdaQueryWrapper<MesInventoryQualityCaseEntity>().eq(MesInventoryQualityCaseEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesInventoryQualityCaseEntity::getFactoryCode, factory);
    }

    private LambdaQueryWrapper<MesCaseDispositionReceiptEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesCaseDispositionReceiptEntity>().eq(MesCaseDispositionReceiptEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesCaseDispositionReceiptEntity::getFactoryCode, factory);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
