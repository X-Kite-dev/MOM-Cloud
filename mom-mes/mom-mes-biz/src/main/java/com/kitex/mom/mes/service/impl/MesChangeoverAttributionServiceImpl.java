package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesChangeoverAttributionDTO;
import com.kitex.mom.mes.entity.MesChangeoverAttributionEntity;
import com.kitex.mom.mes.entity.MesChangeoverAttributionOperationEntity;
import com.kitex.mom.mes.entity.MesChangeoverExecutionEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.exception.MesChangeoverAttributionRejectedException;
import com.kitex.mom.mes.mapper.MesChangeoverAttributionMapper;
import com.kitex.mom.mes.mapper.MesChangeoverAttributionOperationMapper;
import com.kitex.mom.mes.mapper.MesChangeoverExecutionMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.MesChangeoverAttributionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesChangeoverAttributionFactsReader.*;

/**
 * 已完成本地换型实绩的归属闭环，不写设备、产量或计划。
 * 原号先锁/重放；保存前领域拒绝与保存/提交未知明确分开，错误归属只通过异人撤销解除。
 */
@Service
@RequiredArgsConstructor
public class MesChangeoverAttributionServiceImpl
        extends ServiceImpl<MesChangeoverAttributionMapper, MesChangeoverAttributionEntity>
        implements MesChangeoverAttributionService {
    public static final Set<String> REVIEW_CHECKS = Set.of("SOURCE_COMPLETED", "TARGET_CONFIRMED",
            "RESOURCE_CONFIRMED", "INTERVAL_CONFIRMED", "EVIDENCE_CONFIRMED");
    private final MesActualFactoryAccessService access;
    private final MesChangeoverAttributionFactsReader factsReader;
    private final MesChangeoverAttributionOperationMapper operationMapper;
    private final MesChangeoverExecutionMapper executionMapper;
    private final MesTaskArrangeMapper arrangeMapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesChangeoverAttributionDTO.Preview preview(String executionId, String arrangeId) {
        return factsReader.read(id(executionId), id(arrangeId), access.require("read"), false).preview();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> sources(String arrangeId) {
        id(arrangeId);
        String factory = access.require("read");
        var target = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getId, id(arrangeId)));
        require(target != null && StrUtil.isNotBlank(station(target)) && StrUtil.isNotBlank(target.getPlannedDeviceCode()),
                "目标须属于当前组织且明确实际/计划工位及设备，不能猜候选资源");
        // 候选仅限目标明确资源，但不据候选宣称兼容；必须对选定源进行完整预览。
        var rows = executionMapper.selectList(new LambdaQueryWrapper<MesChangeoverExecutionEntity>()
                .eq(MesChangeoverExecutionEntity::getTenantId, tenant()).eq(MesChangeoverExecutionEntity::getFactoryCode, factory)
                .eq(MesChangeoverExecutionEntity::getStatus, "COMPLETED")
                .eq(MesChangeoverExecutionEntity::getDeviceCode, target.getPlannedDeviceCode())
                .eq(MesChangeoverExecutionEntity::getWorksiteCode, station(target))
                .orderByDesc(MesChangeoverExecutionEntity::getCompletedTime).orderByDesc(MesChangeoverExecutionEntity::getId)
                .last("LIMIT 1001"));
        require(rows.size() <= 1000, "已完成换型超过1000行，请先按现场归档治理范围；禁止默默截断");
        return bounded(rows.stream().map(row -> summary(exactMap(row), "inspectionSnapshot")).toList());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> cases(String arrangeId) {
        String factory = access.require("read");
        var rows = list(scope(factory).eq(MesChangeoverAttributionEntity::getArrangeId, id(arrangeId))
                .orderByDesc(MesChangeoverAttributionEntity::getId).last("LIMIT 1001"));
        require(rows.size() <= 1000, "完整归属案件超过1000行，禁止截断");
        return bounded(rows.stream().map(row -> summary(exactMap(row), "sourceSnapshot", "targetSnapshot")).toList());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(String caseId) { return exactMap(caseRow(id(caseId), access.require("read"), false)); }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> operations(String caseId) {
        String factory = access.require("read");
        var row = caseRow(id(caseId), factory, false);
        var items = operationMapper.selectList(operationScope(factory)
                .eq(MesChangeoverAttributionOperationEntity::getCaseId, row.getId())
                .orderByAsc(MesChangeoverAttributionOperationEntity::getId).last("LIMIT 1001"));
        require(items.size() <= 1000, "完整永久操作超过1000行，禁止截断");
        return bounded(items.stream().map(MesChangeoverAttributionServiceImpl::exactMap).toList());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesChangeoverAttributionDTO.Receipt byRequest(String requestId) {
        text(requestId, 100, "原命令号");
        String factory = access.require("read");
        var row = operationMapper.selectOne(operationScope(factory)
                .eq(MesChangeoverAttributionOperationEntity::getRequestId, requestId)
                .eq(MesChangeoverAttributionOperationEntity::getActorUserId, actor()));
        return row == null ? null : JSONUtil.toBean(row.getResultSnapshot(), MesChangeoverAttributionDTO.Receipt.class);
    }

    @Override
    public MesChangeoverAttributionDTO.Receipt prepareCommand(String action, MesChangeoverAttributionDTO.Command command) {
        String factory = access.require("write");
        var body = normalize(action, command);
        return new MesChangeoverAttributionDTO.Receipt().setRequestId(command.getRequestId())
                .setTenantId(tenant().toString()).setFactoryCode(factory).setActorUserId(actor().toString())
                .setAction(action).setRequestFingerprint(hash(json(List.of(tenant().toString(), factory, actor().toString(), action, body))))
                .setRequestSnapshot(json(body)).setOutcome("PREPARED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesChangeoverAttributionDTO.Receipt execute(String action, MesChangeoverAttributionDTO.Command command) {
        String factory = access.require("write");
        var body = normalize(action, command);
        String snapshot = json(body);
        String fingerprint = hash(json(List.of(tenant().toString(), factory, actor().toString(), action, body)));
        var receipt = new MesChangeoverAttributionDTO.Receipt().setRequestId(command.getRequestId())
                .setTenantId(tenant().toString()).setFactoryCode(factory).setActorUserId(actor().toString())
                .setAction(action).setRequestFingerprint(fingerprint).setRequestSnapshot(snapshot);
        require(operationMapper.tryLock("MES_CHANGEOVER_ATTR_REQUEST:" + tenant() + ":" + factory + ":" + command.getRequestId()),
                "原命令仍在执行，保留原号查询或重试");
        var old = operationMapper.selectOne(operationScope(factory)
                .eq(MesChangeoverAttributionOperationEntity::getRequestId, command.getRequestId()));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fingerprint) && Objects.equals(old.getActorUserId(), actor())
                    && Objects.equals(old.getAction(), action) && Objects.equals(old.getRequestSnapshot(), snapshot),
                    "原号已用于不同人员/动作/完整载荷，禁止覆盖或宣称未提交");
            return JSONUtil.toBean(old.getResultSnapshot(), MesChangeoverAttributionDTO.Receipt.class);
        }
        // 原号冲突/锁冲突都在回滚确认范围之外，不能误清另一份在途命令。
        MesChangeoverAttributionEntity existing = "SUBMIT".equals(action) ? null : caseRow(id(command.getCaseId()), factory, false);
        Long executionId = existing == null ? id(command.getExecutionId()) : existing.getExecutionId();
        require(operationMapper.tryLock("MES_CHANGEOVER_ATTR_SOURCE:" + tenant() + ":" + factory + ":" + executionId),
                "该源归属正在处理，保留完整原号重试");
        Prepared prepared;
        try {
            prepared = prepare(action, command, existing, factory, fingerprint);
        } catch (CheckedException exception) {
            throw new MesChangeoverAttributionRejectedException(receipt, exception.getMessage());
        }
        // 从这里开始所有持久化/触发器/事务提交错误都保持未知，不伪造回滚确认。
        var row = prepared.row();
        require(prepared.insert() ? save(row) : updateById(row), "归属案件落账失败");
        if (prepared.original() != null) require(updateById(prepared.original()), "原批准归属撤销落账失败");
        receipt.setOutcome("APPLIED").setCaseSnapshot(json(row)).setMessage(prepared.message());
        var operation = new MesChangeoverAttributionOperationEntity().setCaseId(row.getId())
                .setRequestId(command.getRequestId()).setRequestFingerprint(fingerprint).setAction(action)
                .setActorUserId(actor()).setActorCode(SecurityUtils.getUser().getUsername())
                .setActorName(SecurityUtils.getUser().getName()).setRequestSnapshot(snapshot)
                .setResultSnapshot(json(receipt)).setFactoryCode(factory);
        operation.setTenantId(tenant());
        require(operationMapper.insert(operation) == 1, "完整永久命令回执落账失败");
        return receipt;
    }

    /** 完成全部领域验证并组装，禁止在该方法里保存任何实体。 */
    private Prepared prepare(String action, MesChangeoverAttributionDTO.Command command,
                             MesChangeoverAttributionEntity existing, String factory, String fingerprint) {
        if ("SUBMIT".equals(action)) {
            var facts = factsReader.read(id(command.getExecutionId()), id(command.getArrangeId()), factory, true);
            require(Boolean.TRUE.equals(facts.preview().getEligible()), String.join("；", facts.preview().getGaps()));
            require(Objects.equals(command.getExpectedFingerprint(), facts.preview().getExpectedFingerprint()), "原事实变化，重新预览并核清后申请");
            require(count(scope(factory).eq(MesChangeoverAttributionEntity::getExecutionId, facts.source().getId())
                    .eq(MesChangeoverAttributionEntity::getCaseType, "ASSIGN")
                    .in(MesChangeoverAttributionEntity::getStatus, "PENDING", "APPROVED")) == 0, "该真实换型已有待审或生效归属");
            var target = facts.arrange();
            var row = new MesChangeoverAttributionEntity().setCaseType("ASSIGN").setExecutionId(facts.source().getId())
                    .setArrangeId(target.getId()).setArrangeCode(target.getCode()).setProduceTaskCode(target.getProduceTaskCode())
                    .setTaskCode(target.getTaskCode()).setProcessCode(target.getRouteCode()).setProductCode(target.getMatCode())
                    .setStationCode(station(target)).setDeviceCode(facts.source().getDeviceCode())
                    .setOriginalQuantity(target.getNoticeNum().toPlainString()).setBasisFingerprint(facts.preview().getExpectedFingerprint())
                    .setSourceSnapshot(facts.preview().getSourceSnapshot()).setTargetSnapshot(facts.preview().getTargetSnapshot());
            initialize(row, command, factory, fingerprint);
            return new Prepared(row, null, true, "归属待异人核定");
        }
        var row = caseRow(existing.getId(), factory, true);
        if ("REVOKE".equals(action)) {
            require("ASSIGN".equals(row.getCaseType()) && "APPROVED".equals(row.getStatus()), "只能对精确生效归属申请撤销");
            require(count(scope(factory).eq(MesChangeoverAttributionEntity::getOriginalCaseId, row.getId())
                    .eq(MesChangeoverAttributionEntity::getCaseType, "REVOKE").eq(MesChangeoverAttributionEntity::getStatus, "PENDING")) == 0,
                    "该生效归属已有待审撤销");
            var revoke = new MesChangeoverAttributionEntity();
            BeanUtil.copyProperties(row, revoke, "id", "createTime", "updateTime", "createBy", "updateBy",
                    "requestId", "requestFingerprint", "reviewRequestId", "reviewFingerprint", "reviewedUserId",
                    "reviewedByCode", "reviewedByName", "reviewedTime", "reviewRemark", "reviewChecksSnapshot", "voidedByCaseId", "voidedTime");
            revoke.setCaseType("REVOKE").setOriginalCaseId(row.getId());
            initialize(revoke, command, factory, fingerprint);
            return new Prepared(revoke, null, true, "撤销待异人核定，原归属暂为争议证据");
        }
        require("PENDING".equals(row.getStatus()), "案件已终结；原号查询返回原结果，不能再覆盖");
        if ("CANCEL".equals(action)) {
            require(Objects.equals(actor(), row.getApplicantUserId()), "只能撤回自己尚未审核的申请");
            markReview(row, command, fingerprint, "CANCELLED");
            return new Prepared(row, null, false, "本人已撤回待审申请");
        }
        require(!Objects.equals(actor(), row.getApplicantUserId()), "审核人不能与申请人是同一实名用户");
        if ("REJECT".equals(command.getDecision())) {
            markReview(row, command, fingerprint, "REJECTED");
            return new Prepared(row, null, false, "异人已驳回申请");
        }
        require(command.getChecks() != null && command.getChecks().size() == REVIEW_CHECKS.size()
                && Set.copyOf(command.getChecks()).equals(REVIEW_CHECKS), "批准须完成全部五项原凭证核验");
        if ("REVOKE".equals(row.getCaseType())) {
            var original = caseRow(row.getOriginalCaseId(), factory, true);
            require("ASSIGN".equals(original.getCaseType()) && "APPROVED".equals(original.getStatus())
                    && Objects.equals(original.getExecutionId(), row.getExecutionId())
                    && Objects.equals(original.getArrangeId(), row.getArrangeId()), "原批准归属已变化，不能撤销其他指向");
            markReview(row, command, fingerprint, "APPROVED");
            original.setStatus("VOIDED").setVoidedByCaseId(row.getId()).setVoidedTime(row.getReviewedTime());
            return new Prepared(row, original, false, "撤销获批；原源可重新明确核定，原凭证不覆盖");
        }
        boolean fresh;
        String gap;
        try {
            var current = factsReader.read(row.getExecutionId(), row.getArrangeId(), factory, true).preview();
            fresh = Boolean.TRUE.equals(current.getEligible()) && Objects.equals(current.getExpectedFingerprint(), row.getBasisFingerprint());
            gap = fresh ? "归属已核定" : "源/目标稳定身份已变化或门禁失败：" + String.join("；", current.getGaps());
        } catch (CheckedException exception) { fresh = false; gap = "当前原事实不可核验：" + exception.getMessage(); }
        markReview(row, command, fingerprint, fresh ? "APPROVED" : "STALE");
        return new Prepared(row, null, false, gap);
    }

    private void initialize(MesChangeoverAttributionEntity row, MesChangeoverAttributionDTO.Command command, String factory, String fingerprint) {
        row.setId(IdWorker.getId());
        row.setTenantId(tenant());
        row.setStatus("PENDING").setFactoryCode(factory).setEvidenceReference(command.getEvidenceReference()).setReason(command.getReason())
                .setApplicantUserId(actor()).setApplicantCode(SecurityUtils.getUser().getUsername()).setApplicantName(SecurityUtils.getUser().getName())
                .setRequestId(command.getRequestId()).setRequestFingerprint(fingerprint);
    }

    private void markReview(MesChangeoverAttributionEntity row, MesChangeoverAttributionDTO.Command command, String fingerprint, String status) {
        row.setStatus(status).setReviewRequestId(command.getRequestId()).setReviewFingerprint(fingerprint).setReviewedUserId(actor())
                .setReviewedByCode(SecurityUtils.getUser().getUsername()).setReviewedByName(SecurityUtils.getUser().getName())
                .setReviewedTime(now()).setReviewRemark(command.getReason()).setReviewChecksSnapshot(json(command.getChecks()));
    }

    /** 动作限定字段，规范化完整正文；客户端不得额外传入另一个目标覆盖被冻结身份。 */
    private Map<String, Object> normalize(String action, MesChangeoverAttributionDTO.Command command) {
        require(command != null && Set.of("SUBMIT", "REVIEW", "CANCEL", "REVOKE").contains(action), "非法归属命令");
        var raw = BeanUtil.beanToMap(command);
        Set<String> allowed = switch (action) {
            case "SUBMIT" -> Set.of("requestId", "executionId", "arrangeId", "expectedFingerprint", "evidenceReference", "reason");
            case "REVOKE" -> Set.of("requestId", "caseId", "evidenceReference", "reason");
            case "CANCEL" -> Set.of("requestId", "caseId", "reason");
            default -> Set.of("requestId", "caseId", "decision", "checks", "reason");
        };
        Map<String, Object> body = new TreeMap<>();
        raw.forEach((key, value) -> {
            if (value == null) return;
            require(allowed.contains(key), "该动作不得携带字段：" + key);
            body.put(key, value instanceof String text ? text.trim() : value);
        });
        BeanUtil.fillBeanWithMap(body, command, false);
        text(command.getRequestId(), 100, "永久号");
        text(command.getReason(), 500, "原因/审核意见");
        if (Set.of("SUBMIT", "REVOKE").contains(action)) text(command.getEvidenceReference(), 500, "真实凭证引用");
        if ("SUBMIT".equals(action)) {
            id(command.getExecutionId()); id(command.getArrangeId());
            require(command.getExpectedFingerprint() != null && command.getExpectedFingerprint().matches("[0-9a-f]{64}"), "预览指纹不完整");
        } else id(command.getCaseId());
        if ("REVIEW".equals(action)) {
            require(Set.of("APPROVE", "REJECT").contains(Objects.toString(command.getDecision(), "")), "请选择明确审核决定");
            if (command.getChecks() != null) {
                require(command.getChecks().size() <= 5 && command.getChecks().stream().allMatch(REVIEW_CHECKS::contains)
                        && command.getChecks().stream().distinct().count() == command.getChecks().size(), "审核检查项不合法");
                command.setChecks(command.getChecks().stream().sorted().toList());
                body.put("checks", command.getChecks());
            }
        }
        return body;
    }

    private MesChangeoverAttributionEntity caseRow(Long caseId, String factory, boolean lock) {
        var row = getOne(scope(factory).eq(MesChangeoverAttributionEntity::getId, caseId)
                .last(lock ? "FOR UPDATE NOWAIT" : ""));
        require(row != null, "实际组织内未找到精确归属案件");
        return row;
    }
    private LambdaQueryWrapper<MesChangeoverAttributionEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesChangeoverAttributionEntity>().eq(MesChangeoverAttributionEntity::getTenantId, tenant())
                .eq(MesChangeoverAttributionEntity::getFactoryCode, factory);
    }
    private LambdaQueryWrapper<MesChangeoverAttributionOperationEntity> operationScope(String factory) {
        return new LambdaQueryWrapper<MesChangeoverAttributionOperationEntity>().eq(MesChangeoverAttributionOperationEntity::getTenantId, tenant())
                .eq(MesChangeoverAttributionOperationEntity::getFactoryCode, factory);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> exactMap(Object value) { return (Map<String, Object>) exact(value); }
    private static Map<String, Object> summary(Map<String, Object> value, String... fields) {
        for (String field : fields) value.remove(field);
        return value;
    }
    private static List<Map<String, Object>> bounded(List<Map<String, Object>> rows) {
        require(bytes(json(rows)) <= 8 * 1024 * 1024, "完整列表超过8MiB，禁止省略原记录");
        return rows;
    }
    private static void text(String value, int maximum, String label) {
        require(StrUtil.isNotBlank(value) && value.equals(value.trim()) && value.length() <= maximum, label + "须非空且不超过" + maximum + "字符");
    }
    private static LocalDateTime now() { return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS); }
    private static Long tenant() { return TenantContextHolder.getTenantId(); }
    private static Long actor() { return SecurityUtils.getUser().getId(); }
    private record Prepared(MesChangeoverAttributionEntity row, MesChangeoverAttributionEntity original, boolean insert, String message) { }
}
