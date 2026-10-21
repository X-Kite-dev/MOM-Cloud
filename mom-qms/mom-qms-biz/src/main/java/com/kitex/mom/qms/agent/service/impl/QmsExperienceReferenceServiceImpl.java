package com.kitex.mom.qms.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.agent.dto.QmsExperienceReferenceCommand;
import com.kitex.mom.qms.agent.service.QmsAgentFactoryAccessService;
import com.kitex.mom.qms.agent.service.QmsCapaEvidenceQueryService;
import com.kitex.mom.qms.agent.service.QmsExperienceReferenceService;
import com.kitex.mom.qms.agent.service.RemoteAiExperienceProof;
import com.kitex.mom.qms.entity.QmsCapaEntity;
import com.kitex.mom.qms.entity.QmsCapaExperienceReferenceEntity;
import com.kitex.mom.qms.mapper.QmsCapaExperienceReferenceMapper;
import com.kitex.mom.qms.mapper.QmsCapaMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * QMS持有正式引用回执。新引用反查AI实际批准版，再锁真实CAPA核周期和源指纹；回执与CAPA业务状态独立。
 * 同号重交只读旧回执，不再次判断为新的质量事实；任何外部来源不可达都不绕过核验。
 */
@Service
public class QmsExperienceReferenceServiceImpl
        extends ServiceImpl<QmsCapaExperienceReferenceMapper, QmsCapaExperienceReferenceEntity>
        implements QmsExperienceReferenceService {
    private final QmsAgentFactoryAccessService factoryAccess;
    private final PermissionService permission;
    private final QmsCapaEvidenceQueryService evidence;
    private final QmsCapaMapper capas;
    private final RemoteAiExperienceProof ai;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public QmsExperienceReferenceServiceImpl(QmsAgentFactoryAccessService factoryAccess, PermissionService permission,
                                             QmsCapaEvidenceQueryService evidence, QmsCapaMapper capas,
                                             RemoteAiExperienceProof ai, ObjectMapper json, JdbcTemplate jdbc,
                                             PlatformTransactionManager manager) {
        this.factoryAccess = factoryAccess;
        this.permission = permission;
        this.evidence = evidence;
        this.capas = capas;
        this.ai = ai;
        this.json = json;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public QmsCapaExperienceReferenceEntity reference(QmsExperienceReferenceCommand command) {
        access();
        require(permission.hasPermission("capa:analyze"), "需要CAPA分析权限才可登记经验引用");
        String hash = digest(write(List.of(actor(), command)));
        var previous = find(command.requestId());
        if (previous != null) {
            require(hash.equals(previous.getCommandHash()), "原引用请求已用于其他内容或人员");
            history(previous);
            return previous;
        }
        Map<String, Object> proof = proof(command.experienceId(), command.capaId(), true,
                command.requestId(), referenceBinding(List.of(actor(), command.requestId(), command.capaId(),
                        command.experienceId(), command.documentId(), command.documentContentHash(), command.expectedCaseFingerprint(),
                        command.applicabilityReview(), command.differences(), command.exclusionReview())));
        require(command.documentId().equals(text(proof, "documentId"))
                && command.documentContentHash().equals(text(proof, "documentContentHash")), "知识精确版本与AI当前发布证明不一致");
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                    "QMS-EXP:" + TenantContextHolder.getTenantId() + ":" + FactoryContextHolder.getFactoryCode() + ":" + command.requestId());
            require(!sealed(command.requestId()), "原经验引用请求已封存，迟到命令不能再受理");
            var replay = find(command.requestId());
            if (replay != null) {
                require(hash.equals(replay.getCommandHash()), "原请求已用于不同引用");
                return replay;
            }
            Long capaId = Long.valueOf(command.capaId());
            var capa = capas.selectOne(new LambdaQueryWrapper<QmsCapaEntity>().eq(QmsCapaEntity::getId, capaId)
                    .eq(QmsCapaEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(QmsCapaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("FOR UPDATE"));
            require(capa != null && !Set.of("CLOSED", "CANCELLED").contains(capa.getStatus()), "目标CAPA不存在或已关闭/取消，不能新增经验引用");
            var observed = evidence.query(capaId);
            require(command.expectedCaseFingerprint().equals(observed.getSourceFingerprint()) && observed.getCurrentCycle() != null,
                    "CAPA当前周期或事实已变化，请重新检索，不得把旧判断挂到新周期");
            require(observed.getMissingEvidence().stream().noneMatch(gap -> gap.contains("PERMISSION")), "当前岗位无法读取目标质量来源");
            var receipt = new QmsCapaExperienceReferenceEntity().setRequestId(command.requestId()).setCommandHash(hash).setActorId(actor())
                    .setCapaId(capaId).setCycleNo(observed.getCurrentCycle()).setCaseFingerprint(observed.getSourceFingerprint())
                    .setExperienceId(command.experienceId()).setDocumentId(command.documentId()).setDocumentContentHash(command.documentContentHash())
                    .setSourceProofJson(write(proof)).setApplicabilityReview(command.applicabilityReview())
                    .setDifferences(command.differences()).setExclusionReview(command.exclusionReview()).setFactoryCode(FactoryContextHolder.getFactoryCode());
            receipt.setTenantId(TenantContextHolder.getTenantId());
            baseMapper.insert(receipt);
            return receipt;
        });
    }

    @Override
    public Page<QmsCapaExperienceReferenceEntity> page(Long capaId, long current, long size) {
        access();
        require(current > 0 && size > 0 && size <= 100, "分页范围不合法");
        evidence.query(capaId);
        var page = baseMapper.selectPage(new Page<>(current, size), scope().eq(QmsCapaExperienceReferenceEntity::getCapaId, capaId)
                .orderByDesc(QmsCapaExperienceReferenceEntity::getId));
        page.getRecords().forEach(this::history);
        return page;
    }

    @Override
    public Object request(String requestId) {
        access();
        require(requestId != null && requestId.matches("[A-Za-z0-9._:-]{8,100}"), "原请求号不合法");
        var receipt = find(requestId);
        if (receipt == null) return Map.of("status", sealed(requestId) ? "SEALED" : "NOT_FOUND", "requestId", requestId,
                "notice", "未查到不是未受理证明；保留原号继续查询，或明确封存原号以拒绝迟到引用。");
        require(actor().equals(receipt.getActorId()), "原引用回执不属于当前人员");
        evidence.query(receipt.getCapaId());
        history(receipt);
        return receipt;
    }

    /**
     * 未受理取消同原写命令共享锁，关闭“查不到→换号→迟到旧命令”窗口。
     */
    @Override
    public Object seal(String requestId) {
        access();
        require(permission.hasPermission("capa:analyze"), "封存引用需要CAPA分析权限");
        require(requestId != null && requestId.matches("[A-Za-z0-9._:-]{8,100}"), "原请求号不合法");
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                    "QMS-EXP:" + TenantContextHolder.getTenantId() + ":" + FactoryContextHolder.getFactoryCode() + ":" + requestId);
            var receipt = find(requestId);
            if (receipt != null) {
                require(actor().equals(receipt.getActorId()), "原请求不属于当前人员");
                return;
            }
            if (!sealed(requestId)) jdbc.update("INSERT INTO mom_qms.qms_capa_experience_request_seal"
                            + "(tenant_id,factory_code,request_id,actor_id,create_time) VALUES(?,?,?,?,now())",
                    TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), requestId, actor());
        });
        return request(requestId);
    }

    /**
     * 原调用人显式核账：只有QMS同原号锁下确认的受理/封存才是终态。
     * 不要求原NCR正文仍可见，不回传人工说明或冻结来源；源撤权后仍可结束结果未知的原请求。
     */
    @Override
    public Map<String, Object> reconcile(String requestId, boolean cancel) {
        access();
        require(requestId != null && requestId.matches("[A-Za-z0-9._:-]{8,100}"), "原请求号不合法");
        if (cancel) {
            require(permission.hasPermission("capa:analyze"), "封存引用需要CAPA分析权限");
        }
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                    "QMS-EXP:" + TenantContextHolder.getTenantId() + ":" + FactoryContextHolder.getFactoryCode() + ":" + requestId);
            var receipt = find(requestId);
            boolean isSealed = sealed(requestId);
            if (receipt != null) {
                require(actor().equals(receipt.getActorId()), "原引用请求不属于当前人员");
                require(!isSealed, "原请求同时存在引用与封存，请核对数据库完整性");
            } else if (cancel && !isSealed) {
                jdbc.update("INSERT INTO mom_qms.qms_capa_experience_request_seal"
                                + "(tenant_id,factory_code,request_id,actor_id,create_time) VALUES(?,?,?,?,now())",
                        TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), requestId, actor());
                isSealed = true;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("contract", "QMS_8D_EXPERIENCE_REQUEST@1");
            result.put("tenantId", TenantContextHolder.getTenantId().toString());
            result.put("factoryCode", FactoryContextHolder.getFactoryCode());
            result.put("actorId", actor());
            result.put("requestId", requestId);
            result.put("status", receipt != null ? "SUCCEEDED" : isSealed ? "SEALED" : "NOT_FOUND");
            if (receipt != null) {
                result.put("receiptId", receipt.getId().toString());
                result.put("capaId", receipt.getCapaId().toString());
                result.put("experienceId", receipt.getExperienceId());
                result.put("documentId", receipt.getDocumentId());
                result.put("documentContentHash", receipt.getDocumentContentHash());
                result.put("bindingHash", referenceBinding(receipt));
            }
            return result;
        });
    }

    /**
     * 与AI预约同一长度前缀顺序，核对实际人员、精确源版、目标指纹及所有人工判断。
     */
    private String referenceBinding(QmsCapaExperienceReferenceEntity receipt) {
        return referenceBinding(List.of(receipt.getActorId(), receipt.getRequestId(), receipt.getCapaId().toString(),
                receipt.getExperienceId(), receipt.getDocumentId(), receipt.getDocumentContentHash(), receipt.getCaseFingerprint(),
                receipt.getApplicabilityReview(), receipt.getDifferences(), receipt.getExclusionReview()));
    }

    private String referenceBinding(List<String> values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            canonical.append(value.length()).append(':').append(value);
        }
        return digest(canonical.toString());
    }

    private boolean sealed(String requestId) {
        List<String> actors = jdbc.query("SELECT actor_id FROM mom_qms.qms_capa_experience_request_seal"
                        + " WHERE tenant_id=? AND factory_code=? AND request_id=?", (row, number) -> row.getString(1),
                TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), requestId);
        require(actors.isEmpty() || actor().equals(actors.get(0)), "原封存请求不属于当前人员");
        return !actors.isEmpty();
    }

    private void history(QmsCapaExperienceReferenceEntity receipt) {
        Map<String, Object> original = proof(receipt.getExperienceId(), receipt.getCapaId().toString(), false, null, null);
        require(receipt.getDocumentContentHash().equals(text(original, "documentContentHash")), "历史引用来源摘要不匹配");
    }

    private Map<String, Object> proof(String experienceId, String capaId, boolean current, String requestId, String bindingHash) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("experienceId", experienceId);
        request.put("capaId", capaId);
        request.put("current", current);
        if (current) {
            request.put("requestId", requestId);
            request.put("bindingHash", bindingHash);
        }
        var response = ai.proof(request);
        require(response != null && response.getCode() == 0 && response.getData() != null, "AI经验权威来源当前不可核验");
        var proof = response.getData();
        require("QMS_8D_EXPERIENCE_REFERENCE@1".equals(text(proof, "contract"))
                && TenantContextHolder.getTenantId().toString().equals(text(proof, "tenantId"))
                && FactoryContextHolder.getFactoryCode().equals(text(proof, "factoryCode"))
                && experienceId.equals(text(proof, "experienceId")) && capaId.equals(text(proof, "capaId")), "AI经验来源组织/对象合同不匹配");
        return proof;
    }

    private QmsCapaExperienceReferenceEntity find(String request) {
        return baseMapper.selectOne(scope().eq(QmsCapaExperienceReferenceEntity::getRequestId, request));
    }

    private LambdaQueryWrapper<QmsCapaExperienceReferenceEntity> scope() {
        return new LambdaQueryWrapper<QmsCapaExperienceReferenceEntity>().eq(QmsCapaExperienceReferenceEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(QmsCapaExperienceReferenceEntity::getFactoryCode, FactoryContextHolder.getFactoryCode());
    }

    private void access() {
        factoryAccess.requireRead(FactoryContextHolder.getFactoryCode());
        require(permission.hasPermission("capa:show") && permission.hasPermission("ai:8d:view"), "需要原CAPA及8D经验查看权限");
        require(SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null, "必须使用实际登录人员");
    }

    private String actor() {
        return SecurityUtils.getUser().getId().toString();
    }

    private String text(Map<String, Object> value, String key) {
        return Objects.toString(value.get(key), "");
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new CheckedException("经验回执无法序列化");
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256不可用", failure);
        }
    }

    private void require(boolean okay, String message) {
        if (!okay) {
            throw new CheckedException(message);
        }
    }
}
