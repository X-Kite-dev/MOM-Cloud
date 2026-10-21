package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesMaterialReconcileActionDTO;
import com.kitex.mom.mes.dto.MesMaterialReconcileEvidenceDTO;
import com.kitex.mom.mes.dto.MesMaterialReconcileScanResultDTO;
import com.kitex.mom.mes.entity.MesMaterialReconcileCompensationEntity;
import com.kitex.mom.mes.entity.MesMaterialReconcileIssueEntity;
import com.kitex.mom.mes.mapper.MesMaterialReconcileCompensationMapper;
import com.kitex.mom.mes.mapper.MesMaterialReconcileIssueMapper;
import com.kitex.mom.mes.service.MesMaterialReconcileIssueService;
import com.kitex.mom.plan.dto.PlanMatMrpReconcileEvidenceDTO;
import com.kitex.mom.plan.dto.PlanMatMrpReconcileQueryDTO;
import com.kitex.mom.plan.feign.RemotePlanMatMrpReconcileService;
import com.kitex.mom.wms.api.feign.RemoteMaterialReservationService;
import com.kitex.mom.wms.dto.WmsMaterialReservationLedgerDTO;
import com.kitex.mom.wms.dto.WmsMaterialReservationLedgerLineDTO;
import com.kitex.mom.wms.dto.WmsMaterialReservationLedgerQueryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MesMaterialReconcileIssueServiceImpl
        extends ServiceImpl<MesMaterialReconcileIssueMapper, MesMaterialReconcileIssueEntity>
        implements MesMaterialReconcileIssueService {
    private static final String OPEN = "OPEN";
    private static final String CLAIMED = "CLAIMED";
    private static final String IGNORED = "IGNORED";
    private static final String RESOLVED = "RESOLVED";

    private final RemoteMaterialReservationService remoteReservationService;
    private final RemotePlanMatMrpReconcileService remotePlanReconcileService;
    private final MesMaterialReconcileCompensationMapper compensationMapper;

    @Override
    public IPage<MesMaterialReconcileIssueEntity> queryPage(Page<MesMaterialReconcileIssueEntity> page,
                                                            MesMaterialReconcileIssueEntity query) {
        query = query == null ? new MesMaterialReconcileIssueEntity() : query;
        return page(page, new LambdaQueryWrapper<MesMaterialReconcileIssueEntity>()
                .eq(StrUtil.isNotBlank(query.getStatus()), MesMaterialReconcileIssueEntity::getStatus, query.getStatus())
                .eq(StrUtil.isNotBlank(query.getSeverity()), MesMaterialReconcileIssueEntity::getSeverity, query.getSeverity())
                .eq(StrUtil.isNotBlank(query.getIssueType()), MesMaterialReconcileIssueEntity::getIssueType, query.getIssueType())
                .like(StrUtil.isNotBlank(query.getReservationNo()), MesMaterialReconcileIssueEntity::getReservationNo, query.getReservationNo())
                .like(StrUtil.isNotBlank(query.getActualMatCode()), MesMaterialReconcileIssueEntity::getActualMatCode, query.getActualMatCode())
                .orderByAsc(MesMaterialReconcileIssueEntity::getStatus)
                .orderByDesc(MesMaterialReconcileIssueEntity::getSeverity)
                .orderByDesc(MesMaterialReconcileIssueEntity::getLastFoundTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesMaterialReconcileScanResultDTO scan() {
        List<MesMaterialReconcileEvidenceDTO> evidenceLines = baseMapper.selectEvidence();
        Set<String> reservationNos = evidenceLines.stream().map(MesMaterialReconcileEvidenceDTO::getReservationNo)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        list(new LambdaQueryWrapper<MesMaterialReconcileIssueEntity>()
                .in(MesMaterialReconcileIssueEntity::getStatus, OPEN, CLAIMED))
                .stream().map(MesMaterialReconcileIssueEntity::getReservationNo)
                .filter(StrUtil::isNotBlank).forEach(reservationNos::add);
        Map<String, WmsMaterialReservationLedgerDTO> ledgers = fetchLedgers(reservationNos);
        Map<Long, PlanMatMrpReconcileEvidenceDTO> planEvidence = fetchPlanEvidence(ledgers.values());
        Map<String, MesMaterialReconcileEvidenceDTO> evidenceByLine = evidenceLines.stream()
                .collect(Collectors.toMap(this::lineKey, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, MesMaterialReconcileIssueEntity> detected = new LinkedHashMap<>();

        for (WmsMaterialReservationLedgerDTO ledger : ledgers.values()) {
            for (String integrityIssue : Optional.ofNullable(ledger.getIntegrityIssues()).orElse(List.of())) {
                MesMaterialReconcileIssueEntity issue = issue(ledger, null, integrityIssue,
                        integritySeverity(integrityIssue), integrityTitle(integrityIssue),
                        "WMS权威预留账本完整性检查未通过：" + integrityIssue, null, null);
                detected.put(issue.getIssueKey(), issue);
            }
            if ("PLAN_MRP_SUBSTITUTE".equals(ledger.getSourceType())) {
                PlanMatMrpReconcileEvidenceDTO plan = planEvidence.get(ledger.getSourceId());
                if (plan == null) {
                    MesMaterialReconcileIssueEntity issue = issue(ledger, null, "PLAN_SOURCE_MISSING", "CRITICAL",
                            "WMS预留找不到PLAN来源",
                            "WMS正式预留关联的MRP记录不存在，无法证明该库存占用仍有有效计划需求。",
                            BigDecimal.ONE, BigDecimal.ZERO);
                    detected.put(issue.getIssueKey(), issue);
                } else {
                    if (!Objects.equals(plan.getReservationNo(), ledger.getReservationNo())) {
                        MesMaterialReconcileIssueEntity issue = planIssue(ledger, plan,
                                "PLAN_WMS_RESERVATION_MISMATCH", "CRITICAL", "PLAN与WMS预留号不一致",
                                "MRP记录保存的正式预留号与WMS来源记录不一致，禁止继续下达或领料。");
                        detected.put(issue.getIssueKey(), issue);
                    }
                    boolean approved = "APPROVED".equals(plan.getSubstituteStatus())
                            || "PARTIAL_APPROVED".equals(plan.getSubstituteStatus());
                    boolean wmsReleased = "RELEASED".equals(ledger.getStatus());
                    if ((approved && wmsReleased) || (!approved && !wmsReleased)) {
                        MesMaterialReconcileIssueEntity issue = planIssue(ledger, plan,
                                "PLAN_APPROVAL_WMS_STATUS_MISMATCH", "CRITICAL", "PLAN决策与WMS占用状态冲突",
                                "PLAN替代决策状态与WMS预留是否应继续占用库存不一致，需要核查撤销、补偿或历史状态。");
                        detected.put(issue.getIssueKey(), issue);
                    }
                    boolean requirementMismatch = Optional.ofNullable(ledger.getLines()).orElse(List.of()).stream()
                            .anyMatch(line -> !Objects.equals(plan.getRequirementMatCode(), line.getRequirementMatCode()));
                    if (requirementMismatch) {
                        MesMaterialReconcileIssueEntity issue = planIssue(ledger, plan,
                                "PLAN_REQUIREMENT_WMS_MISMATCH", "CRITICAL", "PLAN需求物料与WMS预留不一致",
                                "WMS预留明细的需求物料不是原MRP缺料物料，替代关系可能发生串单。");
                        detected.put(issue.getIssueKey(), issue);
                    }
                }
            }
        }
        for (MesMaterialReconcileEvidenceDTO evidence : evidenceLines) {
            WmsMaterialReservationLedgerDTO ledger = ledgers.get(evidence.getReservationNo());
            if (ledger == null) {
                MesMaterialReconcileIssueEntity issue = issue(null, evidence, "WMS_RESERVATION_MISSING", "CRITICAL",
                        "MES执行记录找不到WMS正式预留",
                        "MES已存在领料或现场执行证据，但WMS不存在对应正式预留，必须暂停继续领退料并核查来源。",
                        BigDecimal.ONE, BigDecimal.ZERO);
                detected.put(issue.getIssueKey(), issue);
                continue;
            }
            WmsMaterialReservationLedgerLineDTO ledgerLine = Optional.ofNullable(ledger.getLines()).orElse(List.of())
                    .stream().filter(line -> Objects.equals(lineKey(ledger.getReservationNo(), line.getRequirementMatCode(), line.getActualMatCode()),
                            lineKey(evidence))).findFirst().orElse(null);
            if (ledgerLine == null) {
                MesMaterialReconcileIssueEntity issue = issue(ledger, evidence, "MES_LINEAGE_NOT_RESERVED", "CRITICAL",
                        "MES执行物料不在批准预留中",
                        "需求物料与实际领用物料组合不在PLAN批准并由WMS冻结的预留明细中。",
                        BigDecimal.ONE, BigDecimal.ZERO);
                detected.put(issue.getIssueKey(), issue);
                continue;
            }
            if (optional(evidence.getRequestedNum()).compareTo(optional(ledgerLine.getReservedNum())) > 0) {
                MesMaterialReconcileIssueEntity issue = issue(ledger, evidence, "PICK_REQUEST_EXCEEDS_RESERVATION", "HIGH",
                        "累计领料申请超过正式预留",
                        "MES已提交的累计领料申请超过该需求物料/实际物料组合的WMS累计预留量。",
                        ledgerLine.getReservedNum(), evidence.getRequestedNum());
                detected.put(issue.getIssueKey(), issue);
            }
            if (optional(evidence.getIssuedNum()).compareTo(optional(ledgerLine.getConsumedNum())) != 0) {
                MesMaterialReconcileIssueEntity issue = issue(ledger, evidence, "WMS_MES_ISSUED_MISMATCH", "CRITICAL",
                        "WMS发料与MES接收通知不一致",
                        "WMS预留消费累计量与回传到MES的发料累计量不一致，可能存在回调失败、重复回调或历史数据断链。",
                        ledgerLine.getConsumedNum(), evidence.getIssuedNum());
                detected.put(issue.getIssueKey(), issue);
            }
            if (optional(evidence.getReceivedNum()).compareTo(optional(evidence.getIssuedNum())) > 0) {
                MesMaterialReconcileIssueEntity issue = issue(ledger, evidence, "MES_RECEIVED_EXCEEDS_ISSUED", "CRITICAL",
                        "MES现场接收超过WMS发料",
                        "MES实际接收入线数量超过WMS发料回传数量，禁止继续投料直至核实。",
                        evidence.getIssuedNum(), evidence.getReceivedNum());
                detected.put(issue.getIssueKey(), issue);
            }
        }
        // WMS出现已消费记录但MES完全没有相同行项目时，单独识别回传断点。
        for (WmsMaterialReservationLedgerDTO ledger : ledgers.values()) {
            for (WmsMaterialReservationLedgerLineDTO line : Optional.ofNullable(ledger.getLines()).orElse(List.of())) {
                if (optional(line.getConsumedNum()).compareTo(BigDecimal.ZERO) > 0
                        && !evidenceByLine.containsKey(lineKey(ledger.getReservationNo(), line.getRequirementMatCode(), line.getActualMatCode()))) {
                    MesMaterialReconcileIssueEntity issue = issue(ledger, null, "WMS_CONSUMED_WITHOUT_MES_CALLBACK", "CRITICAL",
                            "WMS已发料但MES没有执行证据",
                            "WMS存在预留消费量，但MES未形成对应物料行，需检查领料回调和单据关联。",
                            line.getConsumedNum(), BigDecimal.ZERO);
                    issue.setRequirementMatCode(line.getRequirementMatCode());
                    issue.setActualMatCode(line.getActualMatCode());
                    issue.setIssueKey(issueKey(issue.getIssueType(), issue.getReservationNo(),
                            issue.getRequirementMatCode(), issue.getActualMatCode()));
                    detected.put(issue.getIssueKey(), issue);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int newCount = 0;
        int reopenedCount = 0;
        for (MesMaterialReconcileIssueEntity finding : detected.values()) {
            MesMaterialReconcileIssueEntity existing = getOne(new LambdaQueryWrapper<MesMaterialReconcileIssueEntity>()
                    .eq(MesMaterialReconcileIssueEntity::getIssueKey, finding.getIssueKey()), false);
            if (existing == null) {
                finding.setStatus(OPEN);
                finding.setOccurrenceCount(1);
                finding.setFirstFoundTime(now);
                finding.setLastFoundTime(now);
                save(finding);
                newCount++;
                continue;
            }
            boolean reopen = RESOLVED.equals(existing.getStatus());
            finding.setId(existing.getId());
            finding.setStatus(reopen ? OPEN : existing.getStatus());
            finding.setOwnerCode(existing.getOwnerCode());
            finding.setOwnerName(existing.getOwnerName());
            finding.setHandleReason(reopen ? null : existing.getHandleReason());
            finding.setOccurrenceCount(Optional.ofNullable(existing.getOccurrenceCount()).orElse(0) + 1);
            finding.setFirstFoundTime(existing.getFirstFoundTime());
            finding.setLastFoundTime(now);
            finding.setResolvedTime(reopen ? null : existing.getResolvedTime());
            finding.setResolvedBy(reopen ? null : existing.getResolvedBy());
            updateById(finding);
            if (reopen) {
                reopenedCount++;
            }
        }

        int autoResolved = 0;
        if (CollUtil.isNotEmpty(reservationNos)) {
            List<MesMaterialReconcileIssueEntity> activeIssues = list(new LambdaQueryWrapper<MesMaterialReconcileIssueEntity>()
                    .in(MesMaterialReconcileIssueEntity::getReservationNo, reservationNos)
                    .in(MesMaterialReconcileIssueEntity::getStatus, OPEN, CLAIMED));
            Set<String> detectedKeys = detected.keySet();
            for (MesMaterialReconcileIssueEntity active : activeIssues) {
                if (!detectedKeys.contains(active.getIssueKey())) {
                    update(new LambdaUpdateWrapper<MesMaterialReconcileIssueEntity>()
                            .eq(MesMaterialReconcileIssueEntity::getId, active.getId())
                            .in(MesMaterialReconcileIssueEntity::getStatus, OPEN, CLAIMED)
                            .set(MesMaterialReconcileIssueEntity::getStatus, RESOLVED)
                            .set(MesMaterialReconcileIssueEntity::getResolvedTime, now)
                            .set(MesMaterialReconcileIssueEntity::getResolvedBy, "AUTO_RECONCILE")
                            .set(MesMaterialReconcileIssueEntity::getHandleReason, "复核扫描已不再发现该差异"));
                    boolean relatedIssueRemaining = detected.values().stream()
                            .anyMatch(item -> sameReconcileScope(active, item));
                    completeApprovedCompensations(active.getId(), now, !relatedIssueRemaining);
                    autoResolved++;
                }
            }
        }
        return new MesMaterialReconcileScanResultDTO()
                .setReservationCount(reservationNos.size())
                .setEvidenceLineCount(evidenceLines.size())
                .setDetectedIssueCount(detected.size())
                .setNewIssueCount(newCount)
                .setReopenedIssueCount(reopenedCount)
                .setAutoResolvedCount(autoResolved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesMaterialReconcileIssueEntity action(MesMaterialReconcileActionDTO command) {
        if (command == null || command.getId() == null || StrUtil.isBlank(command.getAction())) {
            throw new CheckedException("异常处置缺少记录或动作");
        }
        MesMaterialReconcileIssueEntity issue = getById(command.getId());
        if (issue == null) {
            throw new CheckedException("对账异常不存在");
        }
        String action = command.getAction().trim().toUpperCase(Locale.ROOT);
        MomUser user = SecurityUtils.getUser();
        String username = user == null ? "SYSTEM" : user.getUsername();
        String name = user == null ? "SYSTEM" : StrUtil.blankToDefault(user.getName(), user.getUsername());
        if ("RECHECK".equals(action)) {
            if (!OPEN.equals(issue.getStatus()) && !CLAIMED.equals(issue.getStatus())) {
                throw new CheckedException("仅待处理或已认领异常可以复核");
            }
            scan();
            return getById(command.getId());
        }
        LambdaUpdateWrapper<MesMaterialReconcileIssueEntity> update = new LambdaUpdateWrapper<MesMaterialReconcileIssueEntity>()
                .eq(MesMaterialReconcileIssueEntity::getId, issue.getId());
        switch (action) {
            case "CLAIM" -> {
                if (!OPEN.equals(issue.getStatus()) && !CLAIMED.equals(issue.getStatus())) {
                    throw new CheckedException("仅待处理或已认领异常可以认领");
                }
                update.set(MesMaterialReconcileIssueEntity::getStatus, CLAIMED)
                        .set(MesMaterialReconcileIssueEntity::getOwnerCode, username)
                        .set(MesMaterialReconcileIssueEntity::getOwnerName, name);
            }
            case "IGNORE" -> {
                if (!OPEN.equals(issue.getStatus()) && !CLAIMED.equals(issue.getStatus())) {
                    throw new CheckedException("仅待处理或已认领异常可以忽略");
                }
                if (StrUtil.isBlank(command.getReason())) {
                    throw new CheckedException("忽略异常必须填写业务依据");
                }
                update.set(MesMaterialReconcileIssueEntity::getStatus, IGNORED)
                        .set(MesMaterialReconcileIssueEntity::getOwnerCode, username)
                        .set(MesMaterialReconcileIssueEntity::getOwnerName, name)
                        .set(MesMaterialReconcileIssueEntity::getHandleReason, command.getReason());
            }
            case "REOPEN" -> {
                if (!IGNORED.equals(issue.getStatus()) && !RESOLVED.equals(issue.getStatus())) {
                    throw new CheckedException("仅已忽略或已解决异常可以重开");
                }
                update.set(MesMaterialReconcileIssueEntity::getStatus, OPEN)
                        .set(MesMaterialReconcileIssueEntity::getHandleReason, command.getReason())
                        .set(MesMaterialReconcileIssueEntity::getResolvedTime, null)
                        .set(MesMaterialReconcileIssueEntity::getResolvedBy, null);
            }
            default -> throw new CheckedException("不支持的异常处置动作：" + action);
        }
        update(update);
        return getById(command.getId());
    }

    private Map<String, WmsMaterialReservationLedgerDTO> fetchLedgers(Set<String> reservationNos) {
        if (reservationNos.isEmpty()) {
            return Map.of();
        }
        List<String> numbers = new ArrayList<>(reservationNos);
        Map<String, WmsMaterialReservationLedgerDTO> result = new LinkedHashMap<>();
        for (int from = 0; from < numbers.size(); from += 200) {
            Set<String> batch = new LinkedHashSet<>(numbers.subList(from, Math.min(from + 200, numbers.size())));
            R<List<WmsMaterialReservationLedgerDTO>> response;
            try {
                response = remoteReservationService.ledgers(new WmsMaterialReservationLedgerQueryDTO().setReservationNos(batch));
            } catch (Exception ex) {
                throw new CheckedException("WMS预留账本不可用，本次对账未改变任何异常状态", ex);
            }
            if (response == null || !response.isOk() || response.getData() == null) {
                throw new CheckedException("WMS预留账本查询失败，本次对账未改变任何异常状态："
                        + (response == null ? "无响应" : response.getMsg()));
            }
            response.getData().forEach(item -> result.put(item.getReservationNo(), item));
        }
        return result;
    }

    private Map<Long, PlanMatMrpReconcileEvidenceDTO> fetchPlanEvidence(
            Collection<WmsMaterialReservationLedgerDTO> ledgers) {
        List<Long> sourceIds = ledgers.stream()
                .filter(item -> "PLAN_MRP_SUBSTITUTE".equals(item.getSourceType()))
                .map(WmsMaterialReservationLedgerDTO::getSourceId).filter(Objects::nonNull).distinct().toList();
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PlanMatMrpReconcileEvidenceDTO> result = new LinkedHashMap<>();
        for (int from = 0; from < sourceIds.size(); from += 200) {
            Set<Long> batch = new LinkedHashSet<>(sourceIds.subList(from, Math.min(from + 200, sourceIds.size())));
            R<List<PlanMatMrpReconcileEvidenceDTO>> response;
            try {
                response = remotePlanReconcileService.evidence(new PlanMatMrpReconcileQueryDTO().setSourceIds(batch));
            } catch (Exception ex) {
                throw new CheckedException("PLAN的MRP证据不可用，本次对账未改变任何异常状态", ex);
            }
            if (response == null || !response.isOk() || response.getData() == null) {
                throw new CheckedException("PLAN的MRP证据查询失败，本次对账未改变任何异常状态："
                        + (response == null ? "无响应" : response.getMsg()));
            }
            response.getData().forEach(item -> result.put(item.getSourceId(), item));
        }
        return result;
    }

    private MesMaterialReconcileIssueEntity planIssue(WmsMaterialReservationLedgerDTO ledger,
                                                      PlanMatMrpReconcileEvidenceDTO plan,
                                                      String type, String severity, String title, String description) {
        MesMaterialReconcileIssueEntity issue = issue(ledger, null, type, severity, title, description,
                BigDecimal.ONE, BigDecimal.ZERO);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("planEvidence", plan);
        snapshot.put("wmsLedger", ledger);
        issue.setEvidenceSnapshot(JSONUtil.toJsonStr(snapshot));
        return issue;
    }

    private MesMaterialReconcileIssueEntity issue(WmsMaterialReservationLedgerDTO ledger,
                                                  MesMaterialReconcileEvidenceDTO evidence,
                                                  String type, String severity, String title, String description,
                                                  BigDecimal expected, BigDecimal actual) {
        String reservationNo = evidence == null ? ledger.getReservationNo() : evidence.getReservationNo();
        String requirement = evidence == null ? null : evidence.getRequirementMatCode();
        String material = evidence == null ? null : evidence.getActualMatCode();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("wmsLedger", ledger);
        snapshot.put("mesEvidence", evidence);
        return new MesMaterialReconcileIssueEntity()
                .setIssueKey(issueKey(type, reservationNo, requirement, material))
                .setIssueType(type).setSeverity(severity).setTitle(title).setDescription(description)
                .setReservationNo(reservationNo)
                .setSourceType(ledger == null ? null : ledger.getSourceType())
                .setSourceId(ledger == null ? null : ledger.getSourceId())
                .setSourceNo(ledger == null ? null : ledger.getSourceNo())
                .setRequirementMatCode(requirement).setActualMatCode(material)
                .setWmsStatus(ledger == null ? "MISSING" : ledger.getStatus())
                .setExpectedNum(optional(expected)).setActualNum(optional(actual))
                .setVarianceNum(optional(actual).subtract(optional(expected)))
                .setEvidenceSnapshot(JSONUtil.toJsonStr(snapshot))
                .setFactoryCode(ledger == null ? null : ledger.getFactoryCode());
    }

    private String issueKey(String type, String reservationNo, String requirement, String actual) {
        return type + "|" + StrUtil.nullToEmpty(reservationNo) + "|"
                + StrUtil.nullToEmpty(requirement) + "|" + StrUtil.nullToEmpty(actual);
    }

    private String lineKey(MesMaterialReconcileEvidenceDTO evidence) {
        return lineKey(evidence.getReservationNo(), evidence.getRequirementMatCode(), evidence.getActualMatCode());
    }

    private String lineKey(String reservationNo, String requirement, String actual) {
        return StrUtil.nullToEmpty(reservationNo) + "|" + StrUtil.nullToEmpty(requirement) + "|" + StrUtil.nullToEmpty(actual);
    }

    private String integritySeverity(String type) {
        return "ACTIVE_RESERVED_STOCK_UNAVAILABLE".equals(type) || "ACTIVE_RESERVED_STOCK_DEFICIT".equals(type)
                || type.startsWith("COMPLIANCE_") || type.startsWith("CUSTOMER_AUTHORIZATION_")
                || type.startsWith("SUPPLIER_") || type.startsWith("QUALITY_RESULT_")
                || type.startsWith("DEVIATION_PERMIT_")
                ? "CRITICAL" : "HIGH";
    }

    private String integrityTitle(String type) {
        return switch (type) {
            case "DETAIL_QUANTITY_BROKEN" -> "WMS预留数量账不平";
            case "RETURN_LINEAGE_BROKEN" -> "生产退料回补代次断链";
            case "ACTIVE_RESERVED_STOCK_UNAVAILABLE" -> "有效预留绑定不可用库存";
            case "ACTIVE_RESERVED_STOCK_DEFICIT" -> "预留余额超过实物库存";
            case "COMPLIANCE_SNAPSHOT_MISSING" -> "替代料预留缺少合规快照";
            case "COMPLIANCE_SNAPSHOT_BROKEN" -> "替代料预留合规快照损坏";
            case "CUSTOMER_AUTHORIZATION_DRIFT" -> "替代料客户授权已不匹配";
            case "SUPPLIER_CERTIFICATION_INVALID" -> "替代料供应商认证依据不完整";
            case "SUPPLIER_CERTIFICATION_EXPIRED" -> "替代料供应商认证已到期";
            case "SUPPLIER_BATCH_COMPLIANCE_DRIFT" -> "预留批次供应商不在认证范围";
            case "QUALITY_RESULT_COMPLIANCE_DRIFT" -> "预留批次质量结论不再符合授权";
            case "DEVIATION_PERMIT_INVALID" -> "替代料偏离许可依据不完整";
            case "DEVIATION_PERMIT_EXPIRED" -> "替代料偏离许可已到期";
            case "HEADER_STATUS_BROKEN" -> "预留主状态与明细余额不一致";
            default -> "WMS预留账本异常";
        };
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void completeApprovedCompensations(Long issueId, LocalDateTime resolvedTime, boolean succeeded) {
        List<MesMaterialReconcileCompensationEntity> compensations = compensationMapper.selectList(
                new LambdaQueryWrapper<MesMaterialReconcileCompensationEntity>()
                        .eq(MesMaterialReconcileCompensationEntity::getIssueId, issueId)
                        .in(MesMaterialReconcileCompensationEntity::getStatus, "APPROVED", "EXECUTING"));
        for (MesMaterialReconcileCompensationEntity compensation : compensations) {
            compensationMapper.update(null, new LambdaUpdateWrapper<MesMaterialReconcileCompensationEntity>()
                    .eq(MesMaterialReconcileCompensationEntity::getId, compensation.getId())
                    .in(MesMaterialReconcileCompensationEntity::getStatus, "APPROVED", "EXECUTING")
                    .set(MesMaterialReconcileCompensationEntity::getStatus, succeeded ? "SUCCEEDED" : "FAILED")
                    .set(MesMaterialReconcileCompensationEntity::getExecutedBy,
                            StrUtil.blankToDefault(compensation.getExecutedBy(), "MANUAL_RECONCILE"))
                    .set(MesMaterialReconcileCompensationEntity::getExecutedByName,
                            StrUtil.blankToDefault(compensation.getExecutedByName(), "人工修复后复核"))
                    .set(MesMaterialReconcileCompensationEntity::getExecutedTime,
                            compensation.getExecutedTime() == null ? resolvedTime : compensation.getExecutedTime())
                    .set(MesMaterialReconcileCompensationEntity::getResultSnapshot,
                            JSONUtil.toJsonStr(Map.of(
                                    "commandResult", StrUtil.nullToEmpty(compensation.getResultSnapshot()),
                                    "reconcileResult", succeeded ? "最新三方证据已一致，补偿闭环完成"
                                            : "原异常已消失，但相同预留/物料范围仍有其他差异")))
                    .set(MesMaterialReconcileCompensationEntity::getFailureMessage,
                            succeeded ? null : "相同预留或物料范围仍有其他对账异常，不能判定补偿成功"));
        }
    }

    private boolean sameReconcileScope(MesMaterialReconcileIssueEntity source,
                                       MesMaterialReconcileIssueEntity candidate) {
        if (!Objects.equals(source.getReservationNo(), candidate.getReservationNo())) {
            return false;
        }
        boolean sourceHasLine = StrUtil.isNotBlank(source.getRequirementMatCode())
                || StrUtil.isNotBlank(source.getActualMatCode());
        if (!sourceHasLine) {
            return true;
        }
        return Objects.equals(source.getRequirementMatCode(), candidate.getRequirementMatCode())
                && Objects.equals(source.getActualMatCode(), candidate.getActualMatCode());
    }
}
