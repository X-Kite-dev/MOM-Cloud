package com.kitex.mom.plan.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.oms.agent.dto.OmsProductionScopeDTO;
import com.kitex.mom.oms.agent.entity.OmsAgentProjectEntity;
import com.kitex.mom.oms.agent.entity.OmsAgentProjectOrderEntity;
import com.kitex.mom.oms.agent.entity.OmsAgentProjectReviewEntity;
import com.kitex.mom.oms.entity.OmsOrderBaseEntity;
import com.kitex.mom.plan.agent.mapper.OmsAgentProjectMapper;
import com.kitex.mom.plan.agent.mapper.OmsAgentProjectOrderMapper;
import com.kitex.mom.plan.agent.mapper.OmsAgentProjectReviewMapper;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 制造项目的显式订单权威归属。每次提交完整新版本，异人审核后取代旧版，订单不允许同时属于两个生效项目。
 * 项目问数只消费生效精确关联，逐订单/产品/单位展示MES归属账；缺关联为UNKNOWN，不从客户/产品猜测。
 */
@Service
@RequiredArgsConstructor
public class OmsAgentProjectService {

    private final OmsAgentProjectMapper projects;

    private final OmsAgentProjectOrderMapper memberships;

    private final OmsAgentProjectReviewMapper reviews;

    private final OmsOrderBaseMapper orders;

    private final OmsProductionScopeQueryService production;

    private final ObjectMapper mapper;

    private final PlanAgentActualScopeAccess actualScope;

    @Transactional(rollbackFor = Exception.class)
    public Long submit(Submit command) throws Exception {
        actualScope.require(command.factoryCode(), "write");
        scope(command.factoryCode());
        List<String> codes = command.orderCodes().stream().map(String::trim).sorted().toList();
        require(codes.size() == Set.copyOf(codes).size() && codes.stream().allMatch(code -> !code.isBlank() &&
                code.length() <= 100), "项目订单不得为空/重复或超过100字符");
        OmsAgentProjectEntity replay = projects.selectOne(new LambdaQueryWrapper<OmsAgentProjectEntity>()
                .eq(OmsAgentProjectEntity::getFactoryCode, factory()).eq(OmsAgentProjectEntity::getRequestId,
                        command.requestId()).last("LIMIT 1"));
        String payload = mapper.writeValueAsString(new Submit(command.factoryCode(), command.projectCode().trim(),
                command.projectName().trim(), codes, command.requestId(), command.reason()));
        if (replay != null) {
            require(mapper.readTree(replay.getScopeSnapshot()).path("command").equals(mapper.readTree(payload)), "项目请求号与原范围不一致");
            return replay.getId();
        }
        List<Map<String, Object>> manifest = new ArrayList<>();
        List<OmsOrderBaseEntity> sources = new ArrayList<>();
        for (String code : codes) {
            OmsOrderBaseEntity order = order(code);
            sources.add(order);
            manifest.add(Map.of("orderId", String.valueOf(order.getId()), "orderCode", order.getOrderCode(), "factoryCode", factory()));
        }
        OmsAgentProjectEntity latest = projects.selectOne(new LambdaQueryWrapper<OmsAgentProjectEntity>()
                .eq(OmsAgentProjectEntity::getFactoryCode, factory()).eq(OmsAgentProjectEntity::getProjectCode,
                        command.projectCode().trim()).orderByDesc(OmsAgentProjectEntity::getVersionNo).last("LIMIT 1 FOR UPDATE"));
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("command", mapper.readTree(payload));
        snapshot.put("orders", manifest);
        String frozen = mapper.writeValueAsString(snapshot);
        OmsAgentProjectEntity project = new OmsAgentProjectEntity().setFactoryCode(factory())
                .setProjectCode(command.projectCode().trim()).setProjectName(command.projectName().trim())
                .setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1).setRequestId(command.requestId())
                .setStatus("WAITING_REVIEW").setScopeSnapshot(frozen).setScopeFingerprint(hash(frozen))
                .setInitiatedBy(actor()).setReason(command.reason());
        projects.insert(project);
        for (OmsOrderBaseEntity order : sources)
            memberships.insert(new OmsAgentProjectOrderEntity()
                    .setFactoryCode(factory()).setProjectId(project.getId()).setOrderId(order.getId())
                    .setOrderCode(order.getOrderCode()).setStatus("WAITING_REVIEW"));
        return project.getId();
    }

    /**
     * 同工厂来源再核验，审核人必须异于编制；替代旧版及订单切换同一事务完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public void review(Review command) throws Exception {
        actualScope.require(command.factoryCode(), "write");
        scope(command.factoryCode());
        OmsAgentProjectEntity project = lock(command.projectId());
        String operation = command.approved() ? "APPROVE" : "REJECT";
        String payload = mapper.writeValueAsString(command);
        if (replay(project, command.requestId(), operation, payload))
            return;
        require("WAITING_REVIEW".equals(project.getStatus()) && !actor().equals(project.getInitiatedBy()), "只能异人审核待审项目订单范围");
        require(project.getScopeFingerprint().equals(command.expectedFingerprint()), "项目订单范围已漂移，请重新读取");
        require(mapper.readTree(command.evidenceRefs()).isArray() && !mapper.readTree(command.evidenceRefs()).isEmpty(),
                "项目归属审核须提交客观依据引用数组");
        List<OmsAgentProjectOrderEntity> rows = rows(project.getId());
        for (OmsAgentProjectOrderEntity row : rows)
            require(Objects.equals(order(row.getOrderCode()).getId(),
                    row.getOrderId()), "订单主键/编号或权限归属已变化，不能换绑生效");
        if (command.approved()) {
            List<OmsAgentProjectEntity> previous = projects.selectList(new LambdaQueryWrapper<OmsAgentProjectEntity>()
                    .eq(OmsAgentProjectEntity::getFactoryCode, factory()).eq(OmsAgentProjectEntity::getProjectCode,
                            project.getProjectCode()).eq(OmsAgentProjectEntity::getStatus, "ACTIVE")
                    .orderByAsc(OmsAgentProjectEntity::getId).last("FOR UPDATE"));
            for (OmsAgentProjectEntity old : previous) {
                require(old.getVersionNo() < project.getVersionNo(), "不能让较旧待审版本覆盖较新已生效范围");
                retireRows(old);
                audit(old, "REPLACED:" + command.requestId(), "SUPERSEDED", "被新版本" + project.getId() + "替代", payload);
            }
            for (OmsAgentProjectOrderEntity row : rows) {
                require(memberships.selectCount(new LambdaQueryWrapper<OmsAgentProjectOrderEntity>()
                        .eq(OmsAgentProjectOrderEntity::getFactoryCode, factory())
                        .eq(OmsAgentProjectOrderEntity::getOrderId, row.getOrderId())
                        .eq(OmsAgentProjectOrderEntity::getStatus, "ACTIVE")) == 0, "订单" + row.getOrderCode() + "已属于其他生效项目，须先审核退役原归属");
            }
        }
        String status = command.approved() ? "ACTIVE" : "REJECTED";
        project.setStatus(status).setReviewedBy(actor());
        projects.updateById(project);
        for (OmsAgentProjectOrderEntity row : rows) {
            row.setStatus(status);
            memberships.updateById(row);
        }
        audit(project, command.requestId(), operation, command.remark(), payload);
    }

    @Transactional(rollbackFor = Exception.class)
    public void retire(Retire command) throws Exception {
        actualScope.require(command.factoryCode(), "write");
        scope(command.factoryCode());
        OmsAgentProjectEntity project = lock(command.projectId());
        String payload = mapper.writeValueAsString(command);
        if (replay(project, command.requestId(), "RETIRE", payload))
            return;
        require("ACTIVE".equals(project.getStatus()) && !actor().equals(project.getInitiatedBy()), "生效归属只能由异于编制人的管理岗位退役");
        require(project.getScopeFingerprint().equals(command.expectedFingerprint()), "项目版本发生变化");
        retireRows(project);
        audit(project, command.requestId(), "RETIRE", command.remark(), payload);
    }

    public Page<OmsAgentProjectEntity> page(long current, long size, String projectCode, String status) {
        scope(factory());
        return projects.selectPage(Page.of(Math.max(1, current), Math.max(1, Math.min(100, size))), new
                LambdaQueryWrapper<OmsAgentProjectEntity>().eq(OmsAgentProjectEntity::getFactoryCode, factory())
                .eq(StringUtils.hasText(projectCode), OmsAgentProjectEntity::getProjectCode, projectCode)
                .eq(StringUtils.hasText(status), OmsAgentProjectEntity::getStatus, status).orderByDesc(OmsAgentProjectEntity::getId));
    }

    public Map<String, Object> detail(Long id) {
        scope(factory());
        OmsAgentProjectEntity project = projects.selectOne(new LambdaQueryWrapper<OmsAgentProjectEntity>()
                .eq(OmsAgentProjectEntity::getId, id).eq(OmsAgentProjectEntity::getFactoryCode, factory()));
        require(project != null, "当前工厂没有此项目范围版本");
        return Map.of("project", project, "orders", rows(id), "reviews", reviews.selectList(new
                LambdaQueryWrapper<OmsAgentProjectReviewEntity>().eq(OmsAgentProjectReviewEntity::getFactoryCode,
                        factory()).eq(OmsAgentProjectReviewEntity::getProjectId, id)
                .orderByAsc(OmsAgentProjectReviewEntity::getId).last("LIMIT 1000")));
    }

    /**
     * 已审核关联精确展开，未知为UNKNOWN；不做项目总量/完成率的无单位加总。
     */
    public Map<String, Object> query(Query command) {
        scope(command.factoryCode());
        OmsAgentProjectEntity project = projects.selectOne(new LambdaQueryWrapper<OmsAgentProjectEntity>()
                .eq(OmsAgentProjectEntity::getFactoryCode, factory()).eq(OmsAgentProjectEntity::getProjectCode,
                        command.projectCode()).eq(OmsAgentProjectEntity::getStatus, "ACTIVE").last("LIMIT 1"));
        List<String> gaps = new ArrayList<>();
        List<OmsProductionScopeDTO.Evidence> evidence = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factoryCode", factory());
        result.put("projectCode", command.projectCode());
        result.put("observedAt", LocalDateTime.now());
        if (project == null)
            gaps.add("项目未配置异人审核生效的精确订单关联，项目来源UNKNOWN，不从同产品/客户猜测");
        else {
            result.put("projectId", String.valueOf(project.getId()));
            result.put("versionNo", project.getVersionNo());
            result.put("scopeFingerprint", project.getScopeFingerprint());
            List<OmsAgentProjectOrderEntity> rows = rows(project.getId());
            // 归属版本的正式成员数与本次成功取证数分别记录，单笔订单不可读时仍能看见覆盖缺口。
            result.put("memberOrderCount", rows.size());
            List<String> unavailableOrders = new ArrayList<>();
            for (OmsAgentProjectOrderEntity row : rows) {
                try {
                    require("ACTIVE".equals(row.getStatus()) && Objects.equals(order(row.getOrderCode()).getId(),
                            row.getOrderId()), "项目精确订单归属已失效");
                    OmsProductionScopeDTO.Evidence item = production.query(new OmsProductionScopeDTO.Query()
                            .setFactoryCode(factory()).setOrderCode(row.getOrderCode()));
                    if (!Boolean.TRUE.equals(item.getScopeComplete()) || Boolean.TRUE.equals(item.getTruncated()))
                        gaps.add("订单" + row.getOrderCode() + "生产范围不完整");
                    for (OmsProductionScopeDTO.Line line : item.getLines())
                        if (!"COMPLETE".equals(line.getQuantityAttributionStatus()))
                            gaps.add("订单" + row.getOrderCode() + "明细" + line.getLineId() + "数量归属未知");
                    evidence.add(item);
                } catch (RuntimeException failure) {
                    unavailableOrders.add(row.getOrderCode());
                    gaps.add("订单" + row.getOrderCode() + "生产来源读取失败，请核对订单归属并重新取证");
                }
            }
            result.put("unavailableOrderCodes", unavailableOrders);
        }
        result.put("scopeStatus", gaps.isEmpty() ? "COMPLETE" : "UNKNOWN");
        result.put("orders", evidence);
        result.put("dataGaps", gaps);
        result.put("evidenceRefs", project == null ? List.of() : List.of("PLAN:oms_agent_project:" + project.getId()));
        result.put("metricDefinition", "显式生效项目-订单映射，数量按订单交付行/产品/单位及合拆批分配守恒分别展示；不等于项目整体排程或客户已交付数量");
        return result;
    }

    private void retireRows(OmsAgentProjectEntity project) {
        project.setStatus("RETIRED");
        projects.updateById(project);
        for (OmsAgentProjectOrderEntity row : rows(project.getId())) {
            row.setStatus("RETIRED");
            memberships.updateById(row);
        }
    }

    private List<OmsAgentProjectOrderEntity> rows(Long id) {
        List<OmsAgentProjectOrderEntity> rows = memberships.selectList(new
                LambdaQueryWrapper<OmsAgentProjectOrderEntity>().eq(OmsAgentProjectOrderEntity::getFactoryCode,
                        factory()).eq(OmsAgentProjectOrderEntity::getProjectId, id)
                .orderByAsc(OmsAgentProjectOrderEntity::getOrderCode).last("LIMIT 51"));
        require(!rows.isEmpty() && rows.size() <= 50, "项目订单集合为空或超过50条，不能截断后判完整");
        return rows;
    }

    private OmsOrderBaseEntity order(String code) {
        List<OmsOrderBaseEntity> matches = orders.selectList(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .eq(OmsOrderBaseEntity::getFactoryCode, factory()).eq(OmsOrderBaseEntity::getOrderCode, code).last("LIMIT 2"));
        require(matches.size() == 1, "订单" + code + "未唯一归属当前租户/工厂");
        return matches.get(0);
    }

    private OmsAgentProjectEntity lock(Long id) {
        OmsAgentProjectEntity value = projects.selectOne(new LambdaQueryWrapper<OmsAgentProjectEntity>()
                .eq(OmsAgentProjectEntity::getFactoryCode, factory()).eq(OmsAgentProjectEntity::getId, id).last("FOR UPDATE"));
        require(value != null, "项目版本不属于当前工厂");
        return value;
    }

    private boolean replay(OmsAgentProjectEntity project, String request, String operation, String payload) throws Exception {
        OmsAgentProjectReviewEntity replay = reviews.selectOne(new LambdaQueryWrapper<OmsAgentProjectReviewEntity>()
                .eq(OmsAgentProjectReviewEntity::getFactoryCode, factory())
                .eq(OmsAgentProjectReviewEntity::getRequestId, request).last("LIMIT 1"));
        if (replay == null)
            return false;
        require(Objects.equals(replay.getProjectId(), project.getId()) && operation.equals(replay.getOperationType()) &&
                hash(payload).equals(replay.getPayloadHash()), "项目永久审核请求与原命令不一致");
        return true;
    }

    private void audit(OmsAgentProjectEntity project, String request, String operation, String remark, String payload) throws Exception {
        reviews.insert(new OmsAgentProjectReviewEntity().setFactoryCode(factory()).setProjectId(project.getId())
                .setRequestId(request).setOperationType(operation).setActorCode(actor()).setRemark(remark)
                .setPayloadHash(hash(payload)).setEvidenceSnapshot(payload));
    }

    private void scope(String requested) {
        actualScope.require(requested, "read");
        require(SecurityUtils.getUser() != null && TenantContextHolder.getTenantId() != null &&
                !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) &&
                StringUtils.hasText(factory()) && Objects.equals(factory(), requested), "项目归属必须实名、实际租户及工厂权限，不允许跳过作用域");
    }

    private String factory() {
        return FactoryContextHolder.getFactoryCode();
    }

    private String actor() {
        return SecurityUtils.getUser().getUsername();
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void require(boolean valid, String reason) {
        if (!valid)
            throw new CheckedException(reason);
    }

    public record Query(@NotBlank String factoryCode, @NotBlank @Size(max = 100) String projectCode) {
    }

    public record Submit(@NotBlank String factoryCode, @NotBlank @Size(max = 100) String projectCode, @NotBlank
    @Size(max = 200) String projectName, @NotEmpty @Size(max = 50) List<String> orderCodes, @NotBlank @Size(max
            = 180) String requestId, @NotBlank @Size(max = 2000) String reason) {
    }

    public record Review(@NotBlank String factoryCode, @NotNull Long projectId, @NotBlank String expectedFingerprint,
                         @NotBlank @Size(max = 180) String requestId, @NotNull Boolean approved,
                         @NotBlank String evidenceRefs,
                         @NotBlank @Size(max = 2000) String remark) {
    }

    public record Retire(@NotBlank String factoryCode, @NotNull Long projectId, @NotBlank String expectedFingerprint,
                         @NotBlank @Size(max = 180) String requestId, @NotBlank @Size(max = 2000) String remark) {
    }
}
