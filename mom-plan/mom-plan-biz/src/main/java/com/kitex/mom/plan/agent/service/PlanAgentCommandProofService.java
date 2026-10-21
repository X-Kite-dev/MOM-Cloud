package com.kitex.mom.plan.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.plan.agent.entity.PlanAgentCommandProofEntity;
import com.kitex.mom.plan.agent.mapper.PlanAgentCommandProofMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** 原业务事务之外持久保存执行意图；失败无法证明未写时保持UNCERTAIN、禁止再下发。 */
@Service
public class PlanAgentCommandProofService {
    private final PlanAgentCommandProofMapper proofs;
    private final ObjectMapper mapper;
    private final PlanAgentCommandOccupancyGuard occupancy;
    private final PlanAgentActualScopeAccess scope;

    public PlanAgentCommandProofService(PlanAgentCommandProofMapper proofs, ObjectMapper mapper, PlanAgentCommandOccupancyGuard occupancy, PlanAgentActualScopeAccess scope) {
        this.proofs = proofs;
        this.mapper = mapper;
        this.occupancy = occupancy;
        this.scope = scope;
    }

    /** 仅首次意图返回可执行；已有未确认意图一律故障关闭，不自动恢复写操作。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Begin begin(PlanAgentCommandCodec.Frozen frozen) throws Exception {
        requireScope(FactoryContextHolder.getFactoryCode());
        scope.require(FactoryContextHolder.getFactoryCode(), "write");
        JsonNode command = mapper.readTree(frozen.snapshot());
        if (command.hasNonNull("factoryCode") && !Objects.equals(FactoryContextHolder.getFactoryCode(), command.path("factoryCode").asText())) {
            throw new CheckedException("永久命令载荷工厂与实际授权工厂不一致");
        }
        PlanAgentCommandProofEntity existing = find(frozen.requestId(), true);
        if (existing != null) {
            requireSame(existing, frozen);
            if ("CONFIRMED".equals(existing.getStatus())) {
                return new Begin(existing.getId(), false, mapper.readTree(existing.getReceiptSnapshot()));
            }
            throw new CheckedException("原命令已有" + existing.getStatus()
                    + "永久领域意图，只允许查询原回执或人工核查，禁止重复下发");
        }
        if (!occupancy.absent(frozen, FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("永久号已被旧业务占用且没有完整意图证明，不允许创建新意图或冒认旧回执");
        }
        PlanAgentCommandProofEntity proof = new PlanAgentCommandProofEntity()
                .setFactoryCode(FactoryContextHolder.getFactoryCode()).setToolCode(frozen.toolCode())
                .setCommandVariant(frozen.variant()).setRequestId(frozen.requestId())
                .setPayloadHash(frozen.hash()).setCommandSnapshot(frozen.snapshot())
                .setStatus("IN_FLIGHT").setActorCode(SecurityUtils.getUser().getUsername());
        proofs.insert(proof);
        return new Begin(proof.getId(), true, null);
    }

    /** 只保存真实领域回执。已确认回执不可被另一响应或失败状态覆盖。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void confirm(Long id, Object receipt) throws Exception {
        PlanAgentCommandProofEntity proof = lock(id);
        String snapshot = mapper.writeValueAsString(receipt);
        if ("CONFIRMED".equals(proof.getStatus())) {
            if (!mapper.readTree(proof.getReceiptSnapshot()).equals(mapper.readTree(snapshot))) {
                throw new CheckedException("永久领域回执不能改写");
            }
            return;
        }
        proof.setStatus("CONFIRMED").setReceiptSnapshot(snapshot).setFailureReason(null);
        proofs.updateById(proof);
    }

    /** 原业务失败可能已在跨服务提交，诊断不能被解释为未执行。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void uncertain(Long id, String reason) {
        PlanAgentCommandProofEntity proof = lock(id);
        if (!"CONFIRMED".equals(proof.getStatus())) {
            proof.setStatus("UNCERTAIN").setFailureReason(reason == null ? "业务回执未确认"
                    : reason.substring(0, Math.min(1000, reason.length())));
            proofs.updateById(proof);
        }
    }

    public PlanAgentCommandProofEntity find(String requestId, boolean lock) {
        requireScope(FactoryContextHolder.getFactoryCode());
        return proofs.selectOne(new LambdaQueryWrapper<PlanAgentCommandProofEntity>()
                .eq(PlanAgentCommandProofEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(PlanAgentCommandProofEntity::getRequestId, requestId)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
    }

    public void requireSame(PlanAgentCommandProofEntity proof, PlanAgentCommandCodec.Frozen frozen) {
        if (!Objects.equals(proof.getToolCode(), frozen.toolCode())
                || !Objects.equals(proof.getCommandVariant(), frozen.variant())
                || !Objects.equals(proof.getPayloadHash(), frozen.hash())) {
            throw new CheckedException("永久请求号与原工具/命令完整载荷不一致");
        }
    }

    /** 不能使用内部跳过租户/工厂标记查询或写入永久意图账。 */
    public void requireScope(String requestedFactory) {
        scope.require(requestedFactory, "read");
        if (SecurityUtils.getUser() == null || TenantContextHolder.getTenantId() == null
                || TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || !StringUtils.hasText(requestedFactory)
                || !Objects.equals(requestedFactory, FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("永久领域回执必须使用实名实际租户和授权工厂");
        }
    }

    private PlanAgentCommandProofEntity lock(Long id) {
        requireScope(FactoryContextHolder.getFactoryCode());
        PlanAgentCommandProofEntity proof = proofs.selectOne(new LambdaQueryWrapper<PlanAgentCommandProofEntity>()
                .eq(PlanAgentCommandProofEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(PlanAgentCommandProofEntity::getId, id).last("FOR UPDATE"));
        if (proof == null) {
            throw new CheckedException("当前工厂没有此永久命令证据");
        }
        return proof;
    }

    public record Begin(Long proofId, boolean mayExecute, JsonNode receipt) {
    }
}
