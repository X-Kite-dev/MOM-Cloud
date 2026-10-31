package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryPlanningPolicyService;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.mes.dto.MesManualTaskCommand;
import com.kitex.mom.mes.dto.MesManualTaskView;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesManualTaskRequestEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesManualTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 独立MOM手工工单；计划接单接口及其合同守卫不参与或放宽。
 */
@Service
@RequiredArgsConstructor
public class MesManualTaskService {
    public static final String VERSION = "MES_MANUAL_TASK_V1", PREFIX = "MES_MANUAL_V1:";
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    private final MesManualTaskMapper facts;
    private final MesTaskMapper tasks;
    private final MesActualFactoryAccessService access;
    private final MesEngineeringPackageService engineering;
    private final RemoteMaterialService materials;
    private final RemoteMdmFactoryPlanningPolicyService policies;
    private final RemoteSeqConfigService numbers;
    private final RemoteMdmRouteEngineeringService packages;

    private static void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("建单指纹失败", e);
        }
    }

    public static boolean owns(MesTaskEntity task) {
        return task != null
                && "MANUAL".equals(task.getResourceType())
                && task.getSourceRequestId() != null
                && task.getSourceRequestId().startsWith(PREFIX);
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private Long actor() {
        return SecurityUtils.getUser().getId();
    }

    private void installed() {
        require(Objects.equals(facts.ready(), 1), "手工建单需要先安装数据库升级368");
    }

    private MesManualTaskCommand canonical(MesManualTaskCommand c, String factory) {
        require(
                c != null
                        && Objects.equals(c.getExpectedTenantId(), String.valueOf(tenant()))
                        && Objects.equals(c.getExpectedFactoryCode(), factory)
                        && Objects.equals(c.getExpectedActorId(), String.valueOf(actor())),
                "手工建单不属于当前实际租户、工厂或操作人");
        require(
                c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}"),
                "手工建单请求号无效");
        require(
                c.getMatCode() != null
                        && !c.getMatCode().isBlank()
                        && c.getMatCode().equals(c.getMatCode().strip())
                        && c.getMatCode().length() <= 100,
                "请选择有效产品");
        require(
                c.getEngineeringReleaseId() != null
                        && c.getEngineeringReleaseId() > 0
                        && c.getEngineeringContentHash() != null
                        && c.getEngineeringContentHash().matches("[0-9a-f]{64}"),
                "请选择已发布的精确工程版本");
        BigDecimal q = c.getQuantity();
        require(
                q != null
                        && q.signum() > 0
                        && q.stripTrailingZeros().scale() <= 8
                        && q.precision() - q.scale() <= 12,
                "数量须为正数，最多12位整数和8位小数");
        require(Set.of("PART", "PRODUCT").contains(Objects.toString(c.getType(), "")), "请选择工单类型");
        require(
                c.getPlanStartDate() != null
                        && c.getPlanEndDate() != null
                        && !c.getPlanEndDate().isBefore(c.getPlanStartDate())
                        && c.getPlanStartDate().getNano() == 0
                        && c.getPlanEndDate().getNano() == 0,
                "计划日期须完整、精确到秒，结束不能早于开始");
        return new MesManualTaskCommand()
                .setRequestId(c.getRequestId())
                .setExpectedTenantId(c.getExpectedTenantId())
                .setExpectedFactoryCode(factory)
                .setExpectedActorId(c.getExpectedActorId())
                .setMatCode(c.getMatCode())
                .setEngineeringReleaseId(c.getEngineeringReleaseId())
                .setEngineeringContentHash(c.getEngineeringContentHash())
                .setQuantity(new BigDecimal(q.stripTrailingZeros().toPlainString()))
                .setType(c.getType())
                .setPlanStartDate(c.getPlanStartDate())
                .setPlanEndDate(c.getPlanEndDate());
    }

    private String payload(MesManualTaskCommand c) {
        try {
            return JSON.writeValueAsString(c);
        } catch (Exception e) {
            throw new CheckedException("手工建单原件无法生成");
        }
    }

    private MdmFactoryPlanningPolicyDTO independentPolicy(String factory) {
        R<MdmFactoryPlanningPolicyDTO> response = policies.active(factory);
        var p = response == null ? null : response.getData();
        require(
                response != null
                        && response.isOk()
                        && p != null
                        && Objects.equals(factory, p.getFactoryCode())
                        && p.getId() != null
                        && p.getId().matches("[1-9][0-9]{0,18}")
                        && new BigDecimal(p.getId()).compareTo(BigDecimal.valueOf(Long.MAX_VALUE))
                        <= 0
                        && p.getVersionNo() != null
                        && p.getVersionNo() > 0
                        && p.getContentFingerprint() != null
                        && p.getContentFingerprint().matches("[0-9a-f]{64}"),
                "无法核验当前工厂计划策略");
        require(
                "MOM_MRP".equals(p.getMrpAuthority())
                        && "MOM_ORDER_ONLY".equals(p.getProductionOrderSyncMode())
                        && (p.getErpSystemCode() == null || p.getErpSystemCode().isBlank())
                        && (p.getExternalFactoryCode() == null
                        || p.getExternalFactoryCode().isBlank()),
                "当前工厂采用正式外部订单协同，请从生产计划生成工单");
        return p;
    }

    @Transactional(readOnly = true)
    public MesManualTaskView.Candidates candidates(String product) {
        String factory = access.require("read");
        installed();
        independentPolicy(factory);
        require(product != null && !product.isBlank() && product.length() <= 100, "请选择产品");
        var response = packages.productionCandidates(product);
        require(response != null && response.isOk() && response.getData() != null, "已发布工程版本候选读取失败");
        var rows = response.getData();
        require(
                rows.size() <= 500
                        && rows.stream()
                        .allMatch(
                                x ->
                                        x != null
                                                && Objects.equals(
                                                product, x.getProductCode())
                                                && x.getReleaseId() != null
                                                && x.getReleaseId() > 0
                                                && x.getContentHash() != null
                                                && x.getContentHash()
                                                .matches("[0-9a-f]{64}")),
                "工程候选产品或版本依据不完整");
        return new MesManualTaskView.Candidates(
                VERSION,
                String.valueOf(tenant()),
                factory,
                String.valueOf(actor()),
                product,
                List.copyOf(rows));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MesManualTaskView.Receipt create(MesManualTaskCommand input) {
        String factory = access.require("write");
        var c = canonical(input, factory);
        installed();
        facts.lockRequest(tenant() + "/" + factory + "/" + PREFIX + c.getRequestId());
        var old = facts.receipt(tenant(), factory, c.getRequestId());
        if (old != null) return response(old, c, factory);
        require(
                facts.original(tenant(), factory, PREFIX + c.getRequestId()).isEmpty(),
                "原手工工单缺少永久回执，请核查原件，不能重复新建");
        var policy = independentPolicy(factory);
        var materialResponse = materials.getDetails(c.getMatCode());
        var m = materialResponse == null ? null : materialResponse.getData();
        require(
                materialResponse != null
                        && materialResponse.isOk()
                        && m != null
                        && Objects.equals(m.getMaterialCode(), c.getMatCode())
                        && Objects.equals(m.getTenantId(), tenant())
                        && Objects.equals(m.getFactoryCode(), factory)
                        && Boolean.TRUE.equals(m.getStatus())
                        && "0".equals(m.getDelFlag())
                        && m.getMaterialName() != null
                        && !m.getMaterialName().isBlank(),
                "当前工厂没有启用的该产品");
        var query = new MesTaskReceiveDTO();
        query.setFactoryCode(factory);
        query.setMatCode(c.getMatCode());
        query.setEngineeringReleaseId(c.getEngineeringReleaseId());
        query.setEngineeringContentHash(c.getEngineeringContentHash());
        var frozen = engineering.resolve(query);
        var source = frozen.source();
        require(Objects.equals(source.getTenantId(), String.valueOf(tenant())), "工程版本不属于当前实际租户");
        var workshop = frozenWorkshop(frozen);
        var sequence = numbers.create("mes_task");
        require(
                sequence != null
                        && sequence.isOk()
                        && sequence.getData() != null
                        && !sequence.getData().isBlank()
                        && sequence.getData().length() <= 100,
                "生产工单编码生成失败");
        String raw = payload(c);
        var task =
                new MesTaskEntity()
                        .setCode(sequence.getData())
                        .setType(c.getType())
                        .setMatCode(c.getMatCode())
                        .setMatName(m.getMaterialName())
                        .setMatSpec(m.getSpecificationModel())
                        .setPlanStartDate(c.getPlanStartDate())
                        .setPlanEndDate(c.getPlanEndDate())
                        .setNoticeNum(c.getQuantity())
                        .setPlannedNum(BigDecimal.ZERO)
                        .setCompleteNum(BigDecimal.ZERO)
                        .setInStockNum(BigDecimal.ZERO)
                        .setUnInStockNum(BigDecimal.ZERO)
                        .setUnPassNum(BigDecimal.ZERO)
                        .setAlreadyNoticeNum(BigDecimal.ZERO)
                        .setStatus("CREATE")
                        .setResourceType("MANUAL")
                        .setSourceSystem("MOM")
                        .setSourceRequestId(PREFIX + c.getRequestId())
                        .setSourceRequestFingerprint(hash(raw))
                        .setProductionOrder(sequence.getData())
                        .setMrpAuthority("MOM_MRP")
                        .setProductionOrderSyncMode("MOM_ORDER_ONLY")
                        .setPlanningPolicyId(Long.valueOf(policy.getId()))
                        .setPlanningPolicyVersion(policy.getVersionNo())
                        .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                        .setEngineeringReleaseId(source.getReleaseId())
                        .setEngineeringContentHash(source.getContentHash())
                        .setEngineeringVersionNo(source.getVersionNo())
                        .setEngineeringBundleSnapshot(source.getBundleSnapshot())
                        .setProcessRouteId(source.getRouteId())
                        .setProcessRouteCode(source.getRouteCode())
                        .setProcessRouteSnapshot(frozen.routeSnapshot())
                        .setBomId(source.getBomId())
                        .setBomVersion(source.getBomVersion())
                        .setBomSnapshot(frozen.bomSnapshot())
                        .setWorkshopCode(workshop.code())
                        .setWorkshopName(workshop.name())
                        .setFactoryCode(factory);
        task.setTenantId(tenant());
        require(tasks.insert(task) == 1, "手工工单未保存，已回滚");
        var row = record(c, factory, "ACCEPTED", task);
        require(facts.insert(row) == 1, "建单回执未保存，已整体回滚");
        return response(row, c, factory);
    }

    @Transactional(readOnly = true)
    public MesManualTaskView.Receipt resolve(MesManualTaskCommand input) {
        String factory = access.require("read");
        var c = canonical(input, factory);
        installed();
        var old = facts.receipt(tenant(), factory, c.getRequestId());
        return old == null
                ? new MesManualTaskView.Receipt(
                VERSION,
                String.valueOf(tenant()),
                factory,
                String.valueOf(actor()),
                c.getRequestId(),
                "ABSENT",
                false,
                c,
                hash(payload(c)),
                null)
                : response(old, c, factory);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MesManualTaskView.Receipt cancel(MesManualTaskCommand input) {
        String factory = access.require("write");
        var c = canonical(input, factory);
        installed();
        facts.lockRequest(tenant() + "/" + factory + "/" + PREFIX + c.getRequestId());
        var old = facts.receipt(tenant(), factory, c.getRequestId());
        if (old != null) return response(old, c, factory);
        require(
                facts.original(tenant(), factory, PREFIX + c.getRequestId()).isEmpty(),
                "已存在工单但回执不完整，不能认定未创建");
        var row = record(c, factory, "CANCELLED", null);
        require(facts.insert(row) == 1, "结束原请求未保存，已回滚");
        return response(row, c, factory);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void editDraft(MesTaskEntity incoming) {
        String factory = access.require("write");
        require(incoming != null && incoming.getId() != null, "手工工单不存在");
        var current = facts.lockTask(incoming.getId(), tenant(), factory);
        require(owns(current) && "CREATE".equals(current.getStatus()), "手工工单已下达或不存在，不能修改");
        var workshop = frozenWorkshop(engineering.verifyBeforeIssue(current));
        require(sameOrAbsent(incoming.getWorkshopCode(), workshop.code())
                        && sameOrAbsent(incoming.getWorkshopName(), workshop.name()),
                "手工工单车间取自已发布工程版本，不能直接替换");
        require(sameOrAbsent(current.getWorkshopCode(), workshop.code())
                        && sameOrAbsent(current.getWorkshopName(), workshop.name()),
                "手工工单车间与原工程版本不一致，请核查来源");
        var before = new org.springframework.beans.BeanWrapperImpl(current);
        var proposed = new org.springframework.beans.BeanWrapperImpl(incoming);
        for (String field :
                List.of(
                        "code",
                        "tenantId",
                        "factoryCode",
                        "type",
                        "matCode",
                        "matName",
                        "matSpec",
                        "noticeNum",
                        "planStartDate",
                        "planEndDate",
                        "resourceType",
                        "resourceId",
                        "sourceRequestId",
                        "sourceRequestFingerprint",
                        "sourceSystem",
                        "sourcePlanNo",
                        "sourcePlanVersion",
                        "planningPolicyId",
                        "planningPolicyVersion",
                        "planningPolicyFingerprint",
                        "mrpAuthority",
                        "productionOrderSyncMode",
                        "erpProductionOrderNo",
                        "productionOrder",
                        "planNo",
                        "formalPlanContractId",
                        "formalPlanContractNo",
                        "formalPlanContractVersion",
                        "formalPlanContractFingerprint",
                        "formalPlanAllocationSnapshot",
                        "formalPlanAllocationVersion",
                        "materialReservationSnapshot",
                        "apsOperationSnapshot",
                        "apsTaskId",
                        "apsBatchId",
                        "apsBatchNo",
                        "apsBatchType",
                        "apsBatchFingerprint",
                        "reworkRequestId",
                        "sourceTaskCode",
                        "sourceProduceTaskCode",
                        "sourceInspectionType",
                        "sourceInspectionTaskId",
                        "sourceHandleRecordId",
                        "reworkProcessCode",
                        "reworkRouteNodeCode",
                        "engineeringReleaseId",
                        "engineeringContentHash",
                        "engineeringVersionNo",
                        "engineeringBundleSnapshot",
                        "processRouteId",
                        "processRouteCode",
                        "processRouteSnapshot",
                        "bomId",
                        "bomVersion",
                        "bomSnapshot",
                        "createBy",
                        "createTime",
                        "completeNum",
                        "plannedNum",
                        "inStockNum",
                        "unInStockNum",
                        "unPassNum",
                        "alreadyNoticeNum")) {
            Object v = proposed.getPropertyValue(field), original = before.getPropertyValue(field);
            if (v == null || original == null && v instanceof String text && text.isEmpty())
                continue;
            require(
                    v instanceof BigDecimal a && original instanceof BigDecimal b
                            ? a.compareTo(b) == 0
                            : Objects.equals(v, original),
                    "手工工单创建依据已冻结，不能修改：" + field);
        }
        var update =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                        MesTaskEntity>()
                        .eq(MesTaskEntity::getId, current.getId())
                        .eq(MesTaskEntity::getTenantId, tenant())
                        .eq(MesTaskEntity::getFactoryCode, factory)
                        .eq(MesTaskEntity::getStatus, "CREATE")
                        .eq(MesTaskEntity::getSourceRequestId, current.getSourceRequestId())
                        .set(MesTaskEntity::getWorkshopCode, workshop.code())
                        .set(MesTaskEntity::getWorkshopName, workshop.name())
                        .set(
                                incoming.getBatchRuleCode() != null,
                                MesTaskEntity::getBatchRuleCode,
                                incoming.getBatchRuleCode())
                        .set(
                                incoming.getWrapCode() != null,
                                MesTaskEntity::getWrapCode,
                                incoming.getWrapCode())
                        .set(
                                incoming.getWrapCapacity() != null,
                                MesTaskEntity::getWrapCapacity,
                                incoming.getWrapCapacity())
                        .set(
                                MesTaskEntity::getUpdateBy,
                                SecurityUtils.getAuthentication().getName())
                        .set(MesTaskEntity::getUpdateTime, LocalDateTime.now());
        require(tasks.update(null, update) == 1, "手工工单保存失败，已回滚");
    }

    private FrozenWorkshop frozenWorkshop(MesEngineeringPackageService.FrozenEngineering frozen) {
        try {
            var route = JSON.readTree(frozen.source().getBundleSnapshot()).path("route");
            return new FrozenWorkshop(route.path("workshopCode").asText(null),
                    route.path("workshopName").asText(null));
        } catch (Exception ex) {
            throw new CheckedException("工程版本车间信息无法读取，禁止保存工单");
        }
    }

    private boolean sameOrAbsent(String value, String expected) {
        return value == null || value.isBlank() || Objects.equals(value, expected);
    }

    private record FrozenWorkshop(String code, String name) {
    }

    private MesManualTaskRequestEntity record(
            MesManualTaskCommand c, String factory, String state, MesTaskEntity task) {
        String raw = payload(c);
        return new MesManualTaskRequestEntity()
                .setId(IdWorker.getId())
                .setTenantId(tenant())
                .setFactoryCode(factory)
                .setRequestId(c.getRequestId())
                .setActorId(actor())
                .setCommandPayload(raw)
                .setCommandFingerprint(hash(raw))
                .setState(state)
                .setTaskId(task == null ? null : task.getId())
                .setTaskCode(task == null ? null : task.getCode())
                .setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private MesManualTaskView.Receipt response(
            MesManualTaskRequestEntity row, MesManualTaskCommand c, String factory) {
        String raw = payload(c);
        require(
                Objects.equals(row.getTenantId(), tenant())
                        && Objects.equals(row.getFactoryCode(), factory)
                        && Objects.equals(row.getActorId(), actor())
                        && Objects.equals(row.getRequestId(), c.getRequestId())
                        && Objects.equals(row.getCommandPayload(), raw)
                        && Objects.equals(row.getCommandFingerprint(), hash(raw)),
                "原请求已使用，但参数或操作人与原件不一致");
        MesManualTaskView.Task task = null;
        if ("ACCEPTED".equals(row.getState())) {
            var list = facts.original(tenant(), factory, PREFIX + c.getRequestId());
            require(list.size() == 1, "原手工工单无法唯一核实");
            var t = list.get(0);
            require(
                    Objects.equals(t.getId(), row.getTaskId())
                            && Objects.equals(t.getCode(), row.getTaskCode())
                            && Objects.equals(
                            t.getSourceRequestFingerprint(), row.getCommandFingerprint())
                            && "MANUAL".equals(t.getResourceType())
                            && Objects.equals(t.getMatCode(), c.getMatCode())
                            && Objects.equals(
                            t.getEngineeringReleaseId(), c.getEngineeringReleaseId())
                            && Objects.equals(
                            t.getEngineeringContentHash(), c.getEngineeringContentHash())
                            && t.getNoticeNum() != null
                            && t.getNoticeNum().compareTo(c.getQuantity()) == 0,
                    "原工单身份或创建依据与回执不一致");
            task =
                    new MesManualTaskView.Task(
                            String.valueOf(t.getId()),
                            t.getCode(),
                            t.getStatus(),
                            t.getDelFlag(),
                            t.getEngineeringVersionNo(),
                            row.getCreatedAt().toString());
        } else
            require(
                    "CANCELLED".equals(row.getState())
                            && row.getTaskId() == null
                            && row.getTaskCode() == null,
                    "原请求终态无效");
        return new MesManualTaskView.Receipt(
                VERSION,
                String.valueOf(tenant()),
                factory,
                String.valueOf(actor()),
                c.getRequestId(),
                row.getState(),
                true,
                c,
                row.getCommandFingerprint(),
                task);
    }
}
