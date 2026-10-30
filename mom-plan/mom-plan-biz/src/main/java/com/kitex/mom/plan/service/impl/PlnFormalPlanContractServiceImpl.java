package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.enums.ProductionOrderSyncModeEnum;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnErpIntegrationOutboxService;
import com.kitex.mom.plan.service.PlnFactoryPlanningAuthorityService;
import com.kitex.mom.plan.service.PlnFormalPlanContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 正式计划合同领域实现。
 *
 * <p>ERP 导入先在当前租户/工厂校验权威策略、来源映射、父子结构和内容摘要，再一次性写入
 * 收件箱与不可变合同。任何一步失败都会回滚，不能留下“已接收但不可执行”的半套正式事实。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnFormalPlanContractServiceImpl
        extends ServiceImpl<PlnFormalPlanContractMapper, PlnFormalPlanContractEntity>
        implements PlnFormalPlanContractService {

    private static final Set<String> SUPPLY_TYPES = Set.of("MAKE", "BUY", "SUBCONTRACT");
    private static final String ERP_AUTHORITY = "ERP_MRP";

    private final PlnErpPlanInboxMapper inboxMapper;
    private final PlnErpProductionOrderReceiptMapper productionOrderReceiptMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnProductDemandMapper demandMapper;
    private final PlnFactoryPlanningAuthorityService authorityService;
    private final PlnErpIntegrationOutboxService erpOutboxService;
    private final ObjectMapper objectMapper;

    private static String toContractSupplyType(String sourceType) {
        return switch (StrUtil.blankToDefault(sourceType, "").toLowerCase()) {
            case "mes" -> "MAKE";
            case "pms" -> "BUY";
            case "out" -> "SUBCONTRACT";
            default -> throw new CheckedException("MRP供应类型无法映射到正式合同：" + sourceType);
        };
    }

    private static boolean sameHeader(PlnFormalPlanContractEntity expected, PlnFormalPlanContractEntity row) {
        return Objects.equals(expected.getContractNo(), row.getContractNo())
                && Objects.equals(expected.getContractVersion(), row.getContractVersion())
                && Objects.equals(expected.getSourceSystemCode(), row.getSourceSystemCode())
                && Objects.equals(expected.getSourceFactoryCode(), row.getSourceFactoryCode())
                && Objects.equals(expected.getSourcePlanNo(), row.getSourcePlanNo())
                && Objects.equals(expected.getSourcePlanVersion(), row.getSourcePlanVersion())
                && Objects.equals(expected.getPlanningPolicyId(), row.getPlanningPolicyId())
                && Objects.equals(expected.getPlanningPolicyVersion(), row.getPlanningPolicyVersion())
                && Objects.equals(expected.getPlanningPolicyFingerprint(), row.getPlanningPolicyFingerprint())
                && Objects.equals(expected.getSourceContentFingerprint(), row.getSourceContentFingerprint())
                && Objects.equals(expected.getVerificationStatus(), row.getVerificationStatus())
                && Objects.equals(expected.getContractStatus(), row.getContractStatus());
    }

    private static Scope requireScope() {
        Long tenantId = TenantContextHolder.getTenantId();
        String factoryCode = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        require(tenantId != null && tenantId > 0 && !TenantContextHolder.getTenantSkip(),
                "正式计划合同必须在实际租户上下文执行");
        require(!Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factoryCode),
                "正式计划合同必须选择实际工厂");
        return new Scope(tenantId, factoryCode);
    }

    private static String requireLength(String value, String label, int maximum) {
        String normalized = StrUtil.trim(value);
        require(StrUtil.isNotBlank(normalized) && normalized.length() <= maximum,
                label + "不能为空且不能超过" + maximum + "个字符");
        return normalized;
    }

    private static String optionalLength(String value, String label, int maximum) {
        String normalized = StrUtil.trim(value);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        require(normalized.length() <= maximum, label + "不能超过" + maximum + "个字符");
        return normalized;
    }

    private static BigDecimal normalizeQuantity(BigDecimal value, String lineNo) {
        require(value != null && value.signum() > 0,
                "计划行[" + lineNo + "]计划数量必须大于0");
        require(value.scale() <= 8 && value.precision() - Math.max(value.scale(), 0) <= 16,
                "计划行[" + lineNo + "]计划数量超过numeric(24,8)范围");
        return value.stripTrailingZeros();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String upper(String value) {
        String normalized = StrUtil.trim(value);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private static long parsePositiveLong(String value, String label) {
        try {
            long id = Long.parseLong(value);
            require(id > 0, label + "必须为正整数");
            return id;
        } catch (NumberFormatException ex) {
            throw new CheckedException(label + "格式不合法");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String canonical(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "" : String.valueOf(value);
            result.append(text.length()).append(':').append(text).append('|');
        }
        return result.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("正式计划内容摘要生成失败");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnFormalPlanContractDTO importErpPlan(PlnErpPlanImportCommand command) {
        Scope scope = requireScope();
        MdmFactoryPlanningPolicyDTO policy = authorityService.requireCurrentPolicy(scope.factoryCode());
        require(ERP_AUTHORITY.equals(policy.getMrpAuthority()),
                "当前工厂不是ERP MRP权威模式，禁止导入ERP正式计划");
        NormalizedImport normalized = normalize(command, scope, policy);

        List<String> lockKeys = List.of(
                        "ERP-PLAN-REQUEST|" + scope.tenantId() + "|" + scope.factoryCode() + "|"
                                + normalized.sourceSystemCode() + "|" + normalized.requestId(),
                        "ERP-PLAN-VERSION|" + scope.tenantId() + "|" + scope.factoryCode() + "|"
                                + normalized.sourceSystemCode() + "|" + normalized.sourcePlanNo() + "|"
                                + normalized.sourcePlanVersion())
                .stream().sorted().toList();
        lockKeys.forEach(baseMapper::lock);

        PlnErpPlanInboxEntity requestReceipt = inboxMapper.selectOne(
                new LambdaQueryWrapper<PlnErpPlanInboxEntity>()
                        .eq(PlnErpPlanInboxEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnErpPlanInboxEntity::getSourceSystemCode, normalized.sourceSystemCode())
                        .eq(PlnErpPlanInboxEntity::getRequestId, normalized.requestId())
                        .last("FOR UPDATE"));
        if (requestReceipt != null) {
            require(Objects.equals(requestReceipt.getRequestFingerprint(), normalized.requestFingerprint()),
                    "ERP计划请求号已被不同内容使用，禁止覆盖原收件记录");
            require("VERIFIED".equals(requestReceipt.getStatus()),
                    "ERP计划请求已存在但未通过核验：" + StrUtil.nullToEmpty(requestReceipt.getRejectionReason()));
            return verifiedByReceipt(requestReceipt);
        }

        List<PlnErpPlanInboxEntity> sameSourceVersion = inboxMapper.selectList(
                new LambdaQueryWrapper<PlnErpPlanInboxEntity>()
                        .eq(PlnErpPlanInboxEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnErpPlanInboxEntity::getSourceSystemCode, normalized.sourceSystemCode())
                        .eq(PlnErpPlanInboxEntity::getSourcePlanNo, normalized.sourcePlanNo())
                        .eq(PlnErpPlanInboxEntity::getSourcePlanVersion, normalized.sourcePlanVersion())
                        .eq(PlnErpPlanInboxEntity::getStatus, "VERIFIED")
                        .orderByAsc(PlnErpPlanInboxEntity::getId));
        if (!sameSourceVersion.isEmpty()) {
            PlnErpPlanInboxEntity original = sameSourceVersion.get(0);
            require(sameSourceVersion.stream().allMatch(row -> Objects.equals(row.getContractNo(), original.getContractNo())
                            && Objects.equals(row.getContractVersion(), original.getContractVersion())
                            && Objects.equals(row.getSourceContentFingerprint(), original.getSourceContentFingerprint())),
                    "ERP来源计划版本存在互相冲突的历史回执，请先完成数据治理");
            require(Objects.equals(original.getSourceContentFingerprint(), normalized.sourceContentFingerprint()),
                    "同一ERP计划版本携带了不同内容，禁止覆盖已核验合同");
            authorityService.revalidate(policy);
            insertInbox(normalized, scope, policy, original.getContractNo(), original.getContractVersion());
            return getVerifiedContract(original.getContractNo(), original.getContractVersion());
        }

        ContractIdentity identity = nextIdentity(normalized, scope);
        LocalDateTime now = LocalDateTime.now();
        String actor = "ERP:" + normalized.sourceSystemCode();
        long policyId = parsePositiveLong(policy.getId(), "计划协同策略ID");
        List<PlnFormalPlanContractEntity> contractRows = new ArrayList<>();
        for (PlnErpPlanImportCommand.Line line : normalized.lines()) {
            PlnFormalPlanContractEntity row = new PlnFormalPlanContractEntity()
                    .setContractNo(identity.contractNo())
                    .setContractVersion(identity.contractVersion())
                    .setLineNo(line.getLineNo())
                    .setParentLineNo(line.getParentLineNo())
                    .setSourceSystemCode(normalized.sourceSystemCode())
                    .setSourceFactoryCode(normalized.sourceFactoryCode())
                    .setSourceDemandNo(line.getSourceDemandNo())
                    .setSourcePlanNo(normalized.sourcePlanNo())
                    .setSourcePlanVersion(normalized.sourcePlanVersion())
                    .setSupplyType(line.getSupplyType())
                    .setErpProductionOrderNo(line.getErpProductionOrderNo())
                    .setProductCode(line.getProductCode())
                    .setProductName(line.getProductName())
                    .setPlannedQuantity(line.getPlannedQuantity())
                    .setUnitCode(line.getUnitCode())
                    .setDemandDate(line.getDemandDate())
                    .setCommittedStartTime(line.getCommittedStartTime())
                    .setCommittedEndTime(line.getCommittedEndTime())
                    .setBomVersion(line.getBomVersion())
                    .setRouteVersion(line.getRouteVersion())
                    .setSubstitutePolicyVersion(line.getSubstitutePolicyVersion())
                    .setPlanningPolicyId(policyId)
                    .setPlanningPolicyVersion(policy.getVersionNo())
                    .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                    .setSourceContentFingerprint(normalized.sourceContentFingerprint())
                    .setContentFingerprint(lineFingerprint(identity, normalized, line, policy))
                    .setRequestId(normalized.requestId())
                    .setVerificationStatus("VERIFIED")
                    .setVerifiedTime(now)
                    .setContractStatus("PUBLISHED")
                    .setFactoryCode(scope.factoryCode());
            row.setId(IdWorker.getId());
            row.setTenantId(scope.tenantId());
            row.setCreateBy(actor);
            row.setCreateTime(now);
            row.setDelFlag("0");
            contractRows.add(row);
        }

        authorityService.revalidate(policy);
        require(saveBatch(contractRows), "保存ERP正式计划合同失败");
        insertInbox(normalized, scope, policy, identity.contractNo(), identity.contractVersion());
        return assemble(contractRows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PlnFormalPlanContractDTO> publishMomMrpContract(String mrpRunNo, String requestId) {
        Scope scope = requireScope();
        String runNo = requireLength(mrpRunNo, "MOM MRP批次号", 100);
        String requestSeed = requireLength(requestId, "MOM合同发布请求号", 100);
        MdmFactoryPlanningPolicyDTO policy = authorityService.requireCurrentPolicy(scope.factoryCode());
        require("MOM_MRP".equals(policy.getMrpAuthority()),
                "当前工厂不是MOM MRP权威模式，禁止发布本地MRP正式合同");
        require(ProductionOrderSyncModeEnum.isValid(policy.getProductionOrderSyncMode()),
                "当前工厂生产订单执行模式不可核验");
        baseMapper.lock("MOM-MRP-CONTRACT|" + scope.tenantId() + "|" + scope.factoryCode() + "|" + runNo);
        List<PlanMatMrpEntity> mrpRows = mrpMapper.selectList(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getFactoryCode, scope.factoryCode())
                .eq(PlanMatMrpEntity::getMrpRunNo, runNo)
                .orderByAsc(PlanMatMrpEntity::getPlanDemandNo)
                .orderByAsc(PlanMatMrpEntity::getId));
        require(!mrpRows.isEmpty(), "MOM MRP批次不存在或不属于当前工厂");
        require(mrpRows.stream().allMatch(row -> Boolean.FALSE.equals(row.getStatus())),
                "MOM MRP批次已有下达状态，不能补建或改写正式合同");
        authorityService.revalidate(policy);
        Map<String, List<PlanMatMrpEntity>> rowsByDemand = mrpRows.stream()
                .collect(java.util.stream.Collectors.groupingBy(PlanMatMrpEntity::getPlanDemandNo,
                        java.util.TreeMap::new, java.util.stream.Collectors.toList()));
        List<PlnFormalPlanContractDTO> result = new ArrayList<>();
        for (Map.Entry<String, List<PlanMatMrpEntity>> entry : rowsByDemand.entrySet()) {
            String demandNo = requireLength(entry.getKey(), "MRP来源需求号", 100);
            String demandRequestId = "MMR-" + sha256(canonical(requestSeed, runNo, demandNo));
            result.add(publishMomDemandContract(runNo, demandNo, demandRequestId,
                    entry.getValue(), scope, policy));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnFormalPlanContractDTO receiveErpProductionOrders(
            PlnErpProductionOrderReceiptCommand command) {
        Scope scope = requireScope();
        require(command != null, "ERP生产订单回执不能为空");
        String requestId = requireLength(command.getRequestId(), "ERP回执请求号", 100);
        String sourcePostingRequestId = requireLength(command.getSourcePostingRequestId(),
                "ERP建单投递请求号", 100);
        String erpSystemCode = requireLength(command.getErpSystemCode(), "ERP系统编码", 64);
        String externalFactoryCode = requireLength(command.getExternalFactoryCode(), "ERP工厂编码", 64);
        String contractNo = requireLength(command.getContractNo(), "正式计划合同号", 100);
        require(command.getSourceContractVersion() != null && command.getSourceContractVersion() > 0,
                "来源正式计划合同版本必须为正整数");
        String receiptNo = requireLength(command.getReceiptNo(), "ERP回执号", 100);
        require(command.getReceiptTime() != null, "ERP回执时间不能为空");
        require(command.getMappings() != null && !command.getMappings().isEmpty()
                        && command.getMappings().size() <= 10000,
                "ERP生产订单回执必须包含1至10000条合同自制行映射");

        Map<String, String> orderByLine = new java.util.TreeMap<>();
        for (PlnErpProductionOrderReceiptCommand.LineMapping mapping : command.getMappings()) {
            require(mapping != null, "ERP生产订单映射不能为空");
            String lineNo = requireLength(mapping.getLineNo(), "合同计划行号", 64);
            String orderNo = requireLength(mapping.getErpProductionOrderNo(), "ERP生产订单号", 100);
            require(orderByLine.put(lineNo, orderNo) == null, "ERP生产订单回执存在重复合同计划行：" + lineNo);
        }
        Map<String, Object> receiptPayload = new LinkedHashMap<>();
        receiptPayload.put("schemaVersion", "ERP_PRODUCTION_ORDER_RECEIPT_V1");
        receiptPayload.put("requestId", requestId);
        receiptPayload.put("sourcePostingRequestId", sourcePostingRequestId);
        receiptPayload.put("erpSystemCode", erpSystemCode);
        receiptPayload.put("externalFactoryCode", externalFactoryCode);
        receiptPayload.put("contractNo", contractNo);
        receiptPayload.put("sourceContractVersion", command.getSourceContractVersion());
        receiptPayload.put("receiptNo", receiptNo);
        receiptPayload.put("receiptTime", command.getReceiptTime());
        receiptPayload.put("mappings", orderByLine);
        String normalizedPayload = toJson(receiptPayload);
        StringBuilder canonicalReceipt = new StringBuilder(canonical(
                "ERP_PRODUCTION_ORDER_RECEIPT_V1", requestId, sourcePostingRequestId,
                erpSystemCode, externalFactoryCode, contractNo, command.getSourceContractVersion(),
                receiptNo, command.getReceiptTime()));
        orderByLine.forEach((lineNo, orderNo) -> canonicalReceipt.append(canonical(lineNo, orderNo)));
        String requestFingerprint = sha256(canonicalReceipt.toString());

        MdmFactoryPlanningPolicyDTO policy = authorityService.requireCurrentPolicy(scope.factoryCode());
        require("MOM_MRP".equals(policy.getMrpAuthority())
                        && "ERP_ORDER_REQUIRED".equals(policy.getProductionOrderSyncMode()),
                "当前工厂不是MOM MRP且ERP订单前置模式，禁止接收ERP建单回执");
        require(Objects.equals(erpSystemCode, policy.getErpSystemCode())
                        && Objects.equals(externalFactoryCode, policy.getExternalFactoryCode()),
                "ERP回执系统或工厂与当前工厂计划协同策略不一致");

        List<String> lockKeys = List.of(
                        "ERP-ORDER-RECEIPT|" + scope.tenantId() + '|' + scope.factoryCode() + '|' + requestId,
                        "FORMAL-CONTRACT|" + scope.tenantId() + '|' + scope.factoryCode() + '|' + contractNo)
                .stream().sorted().toList();
        lockKeys.forEach(baseMapper::lock);

        PlnErpProductionOrderReceiptEntity existing = productionOrderReceiptMapper.selectOne(
                new LambdaQueryWrapper<PlnErpProductionOrderReceiptEntity>()
                        .eq(PlnErpProductionOrderReceiptEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnErpProductionOrderReceiptEntity::getRequestId, requestId)
                        .last("FOR UPDATE"));
        if (existing != null) {
            require(Objects.equals(existing.getRequestFingerprint(), requestFingerprint),
                    "ERP生产订单回执请求号已被不同内容使用，禁止覆盖原回执");
            return assemble(loadContractVersion(scope, existing.getContractNo(),
                    existing.getMappedContractVersion(), false));
        }

        PlnErpIntegrationOutboxEntity sourcePosting = erpOutboxService.getOne(
                new LambdaQueryWrapper<PlnErpIntegrationOutboxEntity>()
                        .eq(PlnErpIntegrationOutboxEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnErpIntegrationOutboxEntity::getRequestId, sourcePostingRequestId)
                        .eq(PlnErpIntegrationOutboxEntity::getEventType, "PRODUCTION_ORDER_REQUEST")
                        .eq(PlnErpIntegrationOutboxEntity::getFormalPlanContractNo, contractNo)
                        .eq(PlnErpIntegrationOutboxEntity::getFormalPlanContractVersion,
                                command.getSourceContractVersion())
                        .last("LIMIT 1"), false);
        require(sourcePosting != null && Objects.equals(sourcePosting.getErpSystemCode(), erpSystemCode),
                "ERP回执找不到匹配的生产订单申请投递账");
        require("SUCCEEDED".equals(sourcePosting.getStatus()),
                "ERP生产订单申请尚未取得成功投递回执，不能建立正式订单映射");

        List<PlnFormalPlanContractEntity> sourceRows = loadContractVersion(scope, contractNo,
                command.getSourceContractVersion(), true);
        PlnFormalPlanContractEntity first = sourceRows.get(0);
        require("MOM".equals(first.getSourceSystemCode()), "ERP建单回执只能升级MOM MRP正式合同");
        require(Objects.equals(first.getPlanningPolicyId(), parsePositiveLong(policy.getId(), "计划协同策略ID"))
                        && Objects.equals(first.getPlanningPolicyVersion(), policy.getVersionNo())
                        && Objects.equals(first.getPlanningPolicyFingerprint(), policy.getContentFingerprint()),
                "ERP回执对应的正式合同不属于当前工厂计划协同策略");
        Set<String> makeLineNos = sourceRows.stream()
                .filter(row -> "MAKE".equals(row.getSupplyType()))
                .map(PlnFormalPlanContractEntity::getLineNo)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        require(!makeLineNos.isEmpty(), "正式计划合同没有需要ERP建单的自制行");
        require(makeLineNos.equals(orderByLine.keySet()),
                "ERP生产订单回执必须且只能完整覆盖正式合同全部自制行");

        List<PlnErpPlanImportCommand.Line> mappedLines = sourceRows.stream()
                .map(row -> toImportLine(row, orderByLine.get(row.getLineNo())))
                .sorted(Comparator.comparing(PlnErpPlanImportCommand.Line::getLineNo)).toList();
        String mappedFingerprint = sha256(canonicalLines(mappedLines));
        int mappedVersion = list(new LambdaQueryWrapper<PlnFormalPlanContractEntity>()
                .eq(PlnFormalPlanContractEntity::getFactoryCode, scope.factoryCode())
                .eq(PlnFormalPlanContractEntity::getContractNo, contractNo))
                .stream().map(PlnFormalPlanContractEntity::getContractVersion)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        ContractIdentity identity = new ContractIdentity(contractNo, mappedVersion);
        NormalizedImport normalized = new NormalizedImport(requestId, requestFingerprint,
                first.getSourceSystemCode(), first.getSourceFactoryCode(), first.getSourcePlanNo(),
                first.getSourcePlanVersion(), mappedFingerprint, mappedLines);
        LocalDateTime now = LocalDateTime.now();
        String actor = "ERP:" + erpSystemCode;
        List<PlnFormalPlanContractEntity> mappedRows = new ArrayList<>(mappedLines.size());
        for (PlnErpPlanImportCommand.Line line : mappedLines) {
            PlnFormalPlanContractEntity row = newContractRow(identity, normalized, line, policy, now, actor);
            mappedRows.add(row);
        }

        authorityService.revalidate(policy);
        require(saveBatch(mappedRows), "保存ERP生产订单映射合同版本失败");
        require(update(new LambdaUpdateWrapper<PlnFormalPlanContractEntity>()
                        .set(PlnFormalPlanContractEntity::getContractStatus, "SUPERSEDED")
                        .set(PlnFormalPlanContractEntity::getUpdateBy, actor)
                        .set(PlnFormalPlanContractEntity::getUpdateTime, now)
                        .eq(PlnFormalPlanContractEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnFormalPlanContractEntity::getContractNo, contractNo)
                        .eq(PlnFormalPlanContractEntity::getContractVersion, command.getSourceContractVersion())
                        .eq(PlnFormalPlanContractEntity::getContractStatus, "PUBLISHED")),
                "ERP生产订单映射生效时原合同版本已变化，请重新获取合同后提交");

        PlnErpProductionOrderReceiptEntity receipt = new PlnErpProductionOrderReceiptEntity()
                .setRequestId(requestId).setRequestFingerprint(requestFingerprint)
                .setSourcePostingRequestId(sourcePostingRequestId).setErpSystemCode(erpSystemCode)
                .setExternalFactoryCode(externalFactoryCode).setContractNo(contractNo)
                .setSourceContractVersion(command.getSourceContractVersion())
                .setMappedContractVersion(mappedVersion).setReceiptNo(receiptNo)
                .setNormalizedPayloadJson(normalizedPayload).setStatus("VERIFIED")
                .setPlanningPolicyId(parsePositiveLong(policy.getId(), "计划协同策略ID"))
                .setPlanningPolicyVersion(policy.getVersionNo())
                .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                .setReceivedTime(command.getReceiptTime()).setFactoryCode(scope.factoryCode());
        receipt.setId(IdWorker.getId());
        receipt.setTenantId(scope.tenantId());
        receipt.setCreateBy(actor);
        receipt.setCreateTime(now);
        receipt.setDelFlag("0");
        require(productionOrderReceiptMapper.insert(receipt) == 1, "保存ERP生产订单映射回执失败");

        PlnFormalPlanContractDTO result = assemble(mappedRows);
        enqueueMomContractPosting(result, policy, "COMMITMENT_UPDATE", toJson(result.getLines()));
        return result;
    }

    private PlnFormalPlanContractDTO publishMomDemandContract(
            String runNo, String demandNo, String requestId, List<PlanMatMrpEntity> mrpRows,
            Scope scope, MdmFactoryPlanningPolicyDTO policy) {
        List<PlnErpPlanImportCommand.Line> normalizedLines = momContractLines(mrpRows, scope);
        String sourceContentFingerprint = sha256(canonicalLines(normalizedLines));
        List<PlnFormalPlanContractEntity> sameSource = list(
                new LambdaQueryWrapper<PlnFormalPlanContractEntity>()
                        .eq(PlnFormalPlanContractEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnFormalPlanContractEntity::getSourceSystemCode, "MOM")
                        .eq(PlnFormalPlanContractEntity::getSourcePlanNo, runNo)
                        .eq(PlnFormalPlanContractEntity::getSourceDemandNo, demandNo)
                        .orderByAsc(PlnFormalPlanContractEntity::getLineNo));
        if (!sameSource.isEmpty()) {
            PlnFormalPlanContractEntity first = sameSource.get(0);
            require(sameSource.stream().allMatch(row -> sameHeader(first, row)),
                    "MOM MRP批次已存在不一致的合同头：" + demandNo);
            require(Objects.equals(first.getSourceContentFingerprint(), sourceContentFingerprint),
                    "同一MOM MRP批次内容发生变化，禁止覆盖正式合同：" + demandNo);
            PlnFormalPlanContractDTO existing = getVerifiedContract(first.getContractNo(), first.getContractVersion());
            enqueueMomContractPostings(existing, policy, mrpRows);
            return existing;
        }

        List<PlnFormalPlanContractEntity> previousRequest = list(
                new LambdaQueryWrapper<PlnFormalPlanContractEntity>()
                        .eq(PlnFormalPlanContractEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnFormalPlanContractEntity::getSourceSystemCode, "MOM")
                        .eq(PlnFormalPlanContractEntity::getRequestId, requestId));
        require(previousRequest.isEmpty(), "MOM合同发布请求号已被其他计划使用");

        ContractIdentity identity = new ContractIdentity("FPC-" + IdWorker.getId(), 1);
        NormalizedImport normalized = new NormalizedImport(requestId,
                sha256(canonical("MOM_MRP_CONTRACT_V1", scope.tenantId(), scope.factoryCode(), runNo,
                        demandNo, sourceContentFingerprint, policy.getId(), policy.getVersionNo(),
                        policy.getContentFingerprint())),
                "MOM", scope.factoryCode(), runNo, "1", sourceContentFingerprint, normalizedLines);
        LocalDateTime now = LocalDateTime.now();
        String actor = "MOM:MRP";
        long policyId = parsePositiveLong(policy.getId(), "计划协同策略ID");
        List<PlnFormalPlanContractEntity> contractRows = new ArrayList<>();
        for (PlnErpPlanImportCommand.Line line : normalizedLines) {
            PlnFormalPlanContractEntity row = new PlnFormalPlanContractEntity()
                    .setContractNo(identity.contractNo()).setContractVersion(identity.contractVersion())
                    .setLineNo(line.getLineNo()).setParentLineNo(line.getParentLineNo())
                    .setSourceSystemCode("MOM").setSourceFactoryCode(scope.factoryCode())
                    .setSourceDemandNo(demandNo).setSourcePlanNo(runNo).setSourcePlanVersion("1")
                    .setSupplyType(line.getSupplyType()).setErpProductionOrderNo(line.getErpProductionOrderNo())
                    .setProductCode(line.getProductCode())
                    .setProductName(line.getProductName()).setPlannedQuantity(line.getPlannedQuantity())
                    .setUnitCode(line.getUnitCode()).setDemandDate(line.getDemandDate())
                    .setCommittedStartTime(line.getCommittedStartTime())
                    .setCommittedEndTime(line.getCommittedEndTime()).setBomVersion(line.getBomVersion())
                    .setRouteVersion(line.getRouteVersion()).setSubstitutePolicyVersion(line.getSubstitutePolicyVersion())
                    .setPlanningPolicyId(policyId).setPlanningPolicyVersion(policy.getVersionNo())
                    .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                    .setSourceContentFingerprint(sourceContentFingerprint)
                    .setContentFingerprint(lineFingerprint(identity, normalized, line, policy))
                    .setRequestId(requestId).setVerificationStatus("VERIFIED").setVerifiedTime(now)
                    .setContractStatus("PUBLISHED").setFactoryCode(scope.factoryCode());
            row.setId(IdWorker.getId());
            row.setTenantId(scope.tenantId());
            row.setCreateBy(actor);
            row.setCreateTime(now);
            row.setDelFlag("0");
            contractRows.add(row);
        }

        Set<ContractIdentity> priorContracts = list(new LambdaQueryWrapper<PlnFormalPlanContractEntity>()
                .eq(PlnFormalPlanContractEntity::getFactoryCode, scope.factoryCode())
                .eq(PlnFormalPlanContractEntity::getSourceSystemCode, "MOM")
                .eq(PlnFormalPlanContractEntity::getSourceDemandNo, demandNo)
                .eq(PlnFormalPlanContractEntity::getContractStatus, "PUBLISHED"))
                .stream().map(row -> new ContractIdentity(row.getContractNo(), row.getContractVersion()))
                .collect(java.util.stream.Collectors.toSet());
        for (ContractIdentity prior : priorContracts) {
            update(new LambdaUpdateWrapper<PlnFormalPlanContractEntity>()
                    .set(PlnFormalPlanContractEntity::getContractStatus, "SUPERSEDED")
                    .set(PlnFormalPlanContractEntity::getUpdateBy, actor)
                    .set(PlnFormalPlanContractEntity::getUpdateTime, now)
                    .eq(PlnFormalPlanContractEntity::getFactoryCode, scope.factoryCode())
                    .eq(PlnFormalPlanContractEntity::getContractNo, prior.contractNo())
                    .eq(PlnFormalPlanContractEntity::getContractVersion, prior.contractVersion())
                    .eq(PlnFormalPlanContractEntity::getContractStatus, "PUBLISHED"));
        }
        require(saveBatch(contractRows), "保存MOM MRP正式计划合同失败：" + demandNo);
        PlnFormalPlanContractDTO published = assemble(contractRows);
        enqueueMomContractPostings(published, policy, mrpRows);
        return published;
    }

    /**
     * MOM正式合同与ERP网络调用解耦：同一事务只形成永久幂等投递账。
     * ERP订单前置模式先申请ERP订单，MOM先执行模式直接回传已承诺计划；两者均不会改写MRP原结果。
     */
    private void enqueueMomContractPostings(PlnFormalPlanContractDTO contract,
                                            MdmFactoryPlanningPolicyDTO policy,
                                            List<PlanMatMrpEntity> mrpRows) {
        String payload = toJson(contract.getLines());
        enqueueMomContractPosting(contract, policy, "COMMITMENT_UPDATE", payload);
        if ("ERP_ORDER_REQUIRED".equals(policy.getProductionOrderSyncMode())) {
            enqueueMomContractPosting(contract, policy, "PRODUCTION_ORDER_REQUEST", payload);
        }
        List<Map<String, Object>> approvedSubstitutes = mrpRows.stream()
                .filter(row -> Set.of("APPROVED", "PARTIAL_APPROVED").contains(row.getSubstituteStatus()))
                .map(row -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("mrpId", String.valueOf(row.getId()));
                    item.put("sourceDemandNo", row.getPlanDemandNo());
                    item.put("mainMaterialCode", row.getMatCode());
                    item.put("decisionStatus", row.getSubstituteStatus());
                    item.put("allocation", row.getSubstituteAllocation());
                    item.put("decisionRequestId", row.getSubstituteDecisionRequestId());
                    item.put("decisionReason", row.getSubstituteDecisionReason());
                    return item;
                }).toList();
        if (!approvedSubstitutes.isEmpty()) {
            enqueueMomContractPosting(contract, policy, "SUBSTITUTE_DECISION", toJson(approvedSubstitutes));
        }
    }

    private void enqueueMomContractPosting(PlnFormalPlanContractDTO contract,
                                           MdmFactoryPlanningPolicyDTO policy,
                                           String eventType, String payload) {
        if (ProductionOrderSyncModeEnum.isLocalOnly(policy.getProductionOrderSyncMode())) {
            return;
        }
        PlnErpPostingEnvelope envelope = new PlnErpPostingEnvelope()
                .setContractVersion("MOM_ERP_POSTING_V1")
                .setRequestId("PLAN-ERP-" + eventType + '-' + contract.getContractNo() + '-'
                        + contract.getContractVersion())
                .setEventType(eventType).setSourceModule("PLAN")
                .setSourceDocumentNo(contract.getContractNo())
                .setSourceDocumentVersion(String.valueOf(contract.getContractVersion()))
                .setErpSystemCode(policy.getErpSystemCode()).setFactoryCode(policy.getFactoryCode())
                .setMrpAuthority(policy.getMrpAuthority())
                .setProductionOrderSyncMode(policy.getProductionOrderSyncMode())
                .setSubstituteGovernanceMode(policy.getSubstituteGovernanceMode())
                .setPlanningPolicyId(policy.getId()).setPlanningPolicyVersion(policy.getVersionNo())
                .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                .setFormalPlanContractNo(contract.getContractNo())
                .setFormalPlanContractVersion(contract.getContractVersion())
                .setFormalPlanContractFingerprint(contract.getSourceContentFingerprint())
                .setPayloadJson(payload).setOccurredTime(LocalDateTime.now());
        erpOutboxService.enqueue(envelope);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new CheckedException("MOM正式计划无法形成ERP规范载荷");
        }
    }

    @Override
    public PlnFormalPlanContractDTO getVerifiedContract(String contractNo, Integer contractVersion) {
        Scope scope = requireScope();
        requireLength(contractNo, "正式计划合同号", 100);
        require(contractVersion != null && contractVersion > 0, "正式计划合同版本必须为正整数");
        List<PlnFormalPlanContractEntity> rows = list(new LambdaQueryWrapper<PlnFormalPlanContractEntity>()
                .eq(PlnFormalPlanContractEntity::getFactoryCode, scope.factoryCode())
                .eq(PlnFormalPlanContractEntity::getContractNo, contractNo.trim())
                .eq(PlnFormalPlanContractEntity::getContractVersion, contractVersion)
                .eq(PlnFormalPlanContractEntity::getVerificationStatus, "VERIFIED")
                .eq(PlnFormalPlanContractEntity::getContractStatus, "PUBLISHED")
                .orderByAsc(PlnFormalPlanContractEntity::getLineNo));
        require(!rows.isEmpty(), "正式计划合同不存在、未核验或已失效");
        MdmFactoryPlanningPolicyDTO current = authorityService.requireCurrentPolicy(scope.factoryCode());
        PlnFormalPlanContractEntity first = rows.get(0);
        require(rows.stream().allMatch(row -> sameHeader(first, row)),
                "正式计划合同头信息不一致，禁止作为排程或执行来源");
        require(Objects.equals(String.valueOf(first.getPlanningPolicyId()), current.getId())
                        && Objects.equals(first.getPlanningPolicyVersion(), current.getVersionNo())
                        && Objects.equals(first.getPlanningPolicyFingerprint(), current.getContentFingerprint()),
                "正式计划合同对应的工厂计划权威已变化，请重新取得有效合同");
        return assemble(rows);
    }

    /**
     * 读取指定不可变合同版本；回执幂等重放时允许读取已经被后续版本替代的历史版本。
     */
    private List<PlnFormalPlanContractEntity> loadContractVersion(
            Scope scope, String contractNo, Integer contractVersion, boolean requirePublished) {
        LambdaQueryWrapper<PlnFormalPlanContractEntity> query =
                new LambdaQueryWrapper<PlnFormalPlanContractEntity>()
                        .eq(PlnFormalPlanContractEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnFormalPlanContractEntity::getContractNo, contractNo)
                        .eq(PlnFormalPlanContractEntity::getContractVersion, contractVersion)
                        .eq(PlnFormalPlanContractEntity::getVerificationStatus, "VERIFIED")
                        .orderByAsc(PlnFormalPlanContractEntity::getLineNo);
        if (requirePublished) {
            query.eq(PlnFormalPlanContractEntity::getContractStatus, "PUBLISHED");
        }
        List<PlnFormalPlanContractEntity> rows = list(query);
        require(!rows.isEmpty(), "ERP回执对应的正式计划合同不存在、未核验或已失效");
        PlnFormalPlanContractEntity first = rows.get(0);
        require(rows.stream().allMatch(row -> sameHeader(first, row)),
                "ERP回执对应的正式计划合同头信息不一致");
        return rows;
    }

    /**
     * 将已有合同事实转换为新版本的规范行，只允许替换自制行 ERP 订单号。
     */
    private PlnErpPlanImportCommand.Line toImportLine(
            PlnFormalPlanContractEntity source, String mappedOrderNo) {
        PlnErpPlanImportCommand.Line line = new PlnErpPlanImportCommand.Line();
        line.setLineNo(source.getLineNo());
        line.setParentLineNo(source.getParentLineNo());
        line.setSourceDemandNo(source.getSourceDemandNo());
        line.setSupplyType(source.getSupplyType());
        line.setErpProductionOrderNo("MAKE".equals(source.getSupplyType())
                ? mappedOrderNo : source.getErpProductionOrderNo());
        line.setProductCode(source.getProductCode());
        line.setProductName(source.getProductName());
        line.setPlannedQuantity(source.getPlannedQuantity());
        line.setUnitCode(source.getUnitCode());
        line.setDemandDate(source.getDemandDate());
        line.setCommittedStartTime(source.getCommittedStartTime());
        line.setCommittedEndTime(source.getCommittedEndTime());
        line.setBomVersion(source.getBomVersion());
        line.setRouteVersion(source.getRouteVersion());
        line.setSubstitutePolicyVersion(source.getSubstitutePolicyVersion());
        return line;
    }

    /**
     * 创建完整的新合同事实行，避免在旧行上原地补写 ERP 订单号。
     */
    private PlnFormalPlanContractEntity newContractRow(
            ContractIdentity identity, NormalizedImport normalized, PlnErpPlanImportCommand.Line line,
            MdmFactoryPlanningPolicyDTO policy, LocalDateTime now, String actor) {
        PlnFormalPlanContractEntity row = new PlnFormalPlanContractEntity()
                .setContractNo(identity.contractNo()).setContractVersion(identity.contractVersion())
                .setLineNo(line.getLineNo()).setParentLineNo(line.getParentLineNo())
                .setSourceSystemCode(normalized.sourceSystemCode())
                .setSourceFactoryCode(normalized.sourceFactoryCode())
                .setSourceDemandNo(line.getSourceDemandNo()).setSourcePlanNo(normalized.sourcePlanNo())
                .setSourcePlanVersion(normalized.sourcePlanVersion()).setSupplyType(line.getSupplyType())
                .setErpProductionOrderNo(line.getErpProductionOrderNo()).setProductCode(line.getProductCode())
                .setProductName(line.getProductName()).setPlannedQuantity(line.getPlannedQuantity())
                .setUnitCode(line.getUnitCode()).setDemandDate(line.getDemandDate())
                .setCommittedStartTime(line.getCommittedStartTime()).setCommittedEndTime(line.getCommittedEndTime())
                .setBomVersion(line.getBomVersion()).setRouteVersion(line.getRouteVersion())
                .setSubstitutePolicyVersion(line.getSubstitutePolicyVersion())
                .setPlanningPolicyId(parsePositiveLong(policy.getId(), "计划协同策略ID"))
                .setPlanningPolicyVersion(policy.getVersionNo())
                .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                .setSourceContentFingerprint(normalized.sourceContentFingerprint())
                .setContentFingerprint(lineFingerprint(identity, normalized, line, policy))
                .setRequestId(normalized.requestId()).setVerificationStatus("VERIFIED")
                .setVerifiedTime(now).setContractStatus("PUBLISHED").setFactoryCode(policy.getFactoryCode());
        row.setId(IdWorker.getId());
        row.setTenantId(TenantContextHolder.getTenantId());
        row.setCreateBy(actor);
        row.setCreateTime(now);
        row.setDelFlag("0");
        return row;
    }

    private NormalizedImport normalize(PlnErpPlanImportCommand command, Scope scope,
                                       MdmFactoryPlanningPolicyDTO policy) {
        require(command != null, "ERP正式计划不能为空");
        String requestId = requireLength(command.getRequestId(), "ERP请求号", 100);
        String sourceSystem = requireLength(command.getSourceSystemCode(), "ERP系统编码", 64);
        String sourceFactory = requireLength(command.getSourceFactoryCode(), "ERP工厂编码", 64);
        String sourcePlanNo = requireLength(command.getSourcePlanNo(), "ERP计划号", 100);
        String sourcePlanVersion = requireLength(command.getSourcePlanVersion(), "ERP计划版本", 64);
        require(Objects.equals(sourceSystem, policy.getErpSystemCode()),
                "ERP来源系统与当前工厂计划协同策略不一致");
        require(Objects.equals(sourceFactory, policy.getExternalFactoryCode()),
                "ERP来源工厂与当前工厂外部映射不一致");
        require(command.getLines() != null && !command.getLines().isEmpty(), "ERP正式计划至少包含一条计划行");
        require(command.getLines().size() <= 10000, "单次ERP正式计划不能超过10000行");

        List<PlnErpPlanImportCommand.Line> lines = new ArrayList<>(command.getLines().size());
        Set<String> lineNos = new LinkedHashSet<>();
        for (PlnErpPlanImportCommand.Line source : command.getLines()) {
            require(source != null, "ERP正式计划行不能为空");
            PlnErpPlanImportCommand.Line line = new PlnErpPlanImportCommand.Line();
            line.setLineNo(requireLength(source.getLineNo(), "计划行号", 64));
            line.setParentLineNo(optionalLength(source.getParentLineNo(), "父计划行号", 64));
            line.setSourceDemandNo(optionalLength(source.getSourceDemandNo(), "来源需求号", 100));
            line.setSupplyType(upper(source.getSupplyType()));
            require(SUPPLY_TYPES.contains(line.getSupplyType()),
                    "计划行[" + line.getLineNo() + "]供应类型只允许MAKE、BUY、SUBCONTRACT");
            line.setErpProductionOrderNo(optionalLength(source.getErpProductionOrderNo(),
                    "ERP生产订单号", 100));
            require(!"ERP_ORDER_REQUIRED".equals(policy.getProductionOrderSyncMode())
                            || !"MAKE".equals(line.getSupplyType())
                            || StrUtil.isNotBlank(line.getErpProductionOrderNo()),
                    "计划行[" + line.getLineNo() + "]缺少ERP正式生产订单号");
            line.setProductCode(requireLength(source.getProductCode(), "物料编码", 100));
            line.setProductName(optionalLength(source.getProductName(), "物料名称", 200));
            line.setPlannedQuantity(normalizeQuantity(source.getPlannedQuantity(), line.getLineNo()));
            line.setUnitCode(requireLength(source.getUnitCode(), "计量单位", 32));
            require(source.getDemandDate() != null, "计划行[" + line.getLineNo() + "]需求日期不能为空");
            require(source.getCommittedEndTime() != null,
                    "计划行[" + line.getLineNo() + "]承诺完成时间不能为空");
            require(source.getCommittedStartTime() == null
                            || !source.getCommittedStartTime().isAfter(source.getCommittedEndTime()),
                    "计划行[" + line.getLineNo() + "]承诺开始时间不能晚于完成时间");
            line.setDemandDate(source.getDemandDate());
            line.setCommittedStartTime(source.getCommittedStartTime());
            line.setCommittedEndTime(source.getCommittedEndTime());
            line.setBomVersion(optionalLength(source.getBomVersion(), "BOM版本", 64));
            line.setRouteVersion(optionalLength(source.getRouteVersion(), "工艺版本", 64));
            line.setSubstitutePolicyVersion(optionalLength(source.getSubstitutePolicyVersion(), "替代策略版本", 64));
            require(lineNos.add(line.getLineNo()), "ERP正式计划行号重复：" + line.getLineNo());
            lines.add(line);
        }
        validateParentGraph(lines, lineNos);
        lines.sort(Comparator.comparing(PlnErpPlanImportCommand.Line::getLineNo));

        String contentFingerprint = sha256(canonicalLines(lines));
        require(isSha256(command.getSourceContentFingerprint()), "ERP计划内容摘要必须为小写SHA-256");
        require(Objects.equals(contentFingerprint, command.getSourceContentFingerprint()),
                "ERP计划内容摘要与规范化计划行不一致");
        String requestFingerprint = sha256(canonical("ERP_PLAN_IMPORT_V1", scope.tenantId(), scope.factoryCode(),
                sourceSystem, sourceFactory, requestId, sourcePlanNo, sourcePlanVersion,
                contentFingerprint, policy.getId(), policy.getVersionNo(), policy.getContentFingerprint()));
        return new NormalizedImport(requestId, requestFingerprint, sourceSystem, sourceFactory,
                sourcePlanNo, sourcePlanVersion, contentFingerprint, lines);
    }

    /**
     * 把聚合MRP行恢复为可追溯的需求根与BOM路径合同，避免丢失多层父子关系。
     */
    private List<PlnErpPlanImportCommand.Line> momContractLines(List<PlanMatMrpEntity> mrpRows, Scope scope) {
        Set<String> demandNos = new LinkedHashSet<>();
        for (PlanMatMrpEntity row : mrpRows) {
            require(StrUtil.isNotBlank(row.getPlanDemandNo()), "MRP行缺少来源需求号");
            demandNos.add(row.getPlanDemandNo());
        }
        List<PlnProductDemandEntity> demands = demandMapper.selectList(
                new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getFactoryCode, scope.factoryCode())
                        .in(PlnProductDemandEntity::getDemandNo, demandNos));
        Map<String, PlnProductDemandEntity> demandByNo = new HashMap<>();
        for (PlnProductDemandEntity demand : demands) {
            require(demandByNo.put(demand.getDemandNo(), demand) == null,
                    "来源需求号不唯一，不能生成正式合同：" + demand.getDemandNo());
        }
        require(demandByNo.keySet().containsAll(demandNos), "MRP批次存在无法核验的来源需求");

        List<PlnErpPlanImportCommand.Line> lines = new ArrayList<>();
        Map<String, String> lineNoByDemandAndPath = new HashMap<>();
        for (String demandNo : demandNos.stream().sorted().toList()) {
            PlnProductDemandEntity demand = demandByNo.get(demandNo);
            require(demand.getNeedNum() != null && demand.getNeedNum().signum() > 0
                            && demand.getPlanEndDate() != null && StrUtil.isNotBlank(demand.getProductCode())
                            && StrUtil.isNotBlank(demand.getProductUnit()),
                    "来源需求[" + demandNo + "]缺少产品、数量、单位或交期，不能发布正式合同");
            String rootLineNo = "D-" + demand.getId();
            lineNoByDemandAndPath.put(demandNo + "\u0001ROOT:" + demand.getProductCode(), rootLineNo);
            PlnErpPlanImportCommand.Line root = new PlnErpPlanImportCommand.Line();
            root.setLineNo(rootLineNo);
            root.setSourceDemandNo(demandNo);
            root.setSupplyType("MAKE");
            root.setProductCode(demand.getProductCode());
            root.setProductName(demand.getProductName());
            root.setPlannedQuantity(demand.getNeedNum().stripTrailingZeros());
            root.setUnitCode(demand.getProductUnit());
            root.setDemandDate(demand.getPlanEndDate());
            root.setCommittedEndTime(LocalDateTime.of(demand.getPlanEndDate(), LocalTime.MAX));
            lines.add(root);
        }

        List<PathLine> pathLines = new ArrayList<>();
        for (PlanMatMrpEntity row : mrpRows) {
            for (PlanBomRequirementPathDTO path : parsePaths(row)) {
                BigDecimal net = path.getNetDemandNum() == null ? BigDecimal.ZERO : path.getNetDemandNum();
                if (net.signum() <= 0) {
                    continue;
                }
                String stableKey = row.getPlanDemandNo() + "\u0001" + path.getBomPath();
                String lineNo = "P-" + sha256(stableKey).substring(0, 32);
                require(lineNoByDemandAndPath.put(stableKey, lineNo) == null,
                        "MRP批次BOM路径重复：" + path.getBomPath());
                pathLines.add(new PathLine(row, path, lineNo));
            }
        }
        pathLines.sort(Comparator.comparing((PathLine item) -> item.path().getBomLevel(),
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(item -> item.path().getBomPath()));
        for (PathLine item : pathLines) {
            PlanMatMrpEntity row = item.row();
            PlanBomRequirementPathDTO path = item.path();
            String parentLineNo = lineNoByDemandAndPath.get(row.getPlanDemandNo() + "\u0001" + path.getParentBomPath());
            require(parentLineNo != null, "MRP路径缺少已发布父节点：" + path.getBomPath());
            PlnErpPlanImportCommand.Line line = new PlnErpPlanImportCommand.Line();
            line.setLineNo(item.lineNo());
            line.setParentLineNo(parentLineNo);
            line.setSourceDemandNo(row.getPlanDemandNo());
            line.setSupplyType(toContractSupplyType(path.getSupplyType()));
            line.setProductCode(path.getMaterialCode());
            line.setProductName(path.getMaterialName());
            line.setPlannedQuantity(path.getNetDemandNum().stripTrailingZeros());
            line.setUnitCode(requireLength(row.getMatUnit(), "MRP物料单位", 32));
            require(path.getRequirementDate() != null,
                    "MRP路径[" + path.getBomPath() + "]需求日期不能为空");
            line.setDemandDate(path.getRequirementDate());
            if (path.getPlannedReleaseDate() != null) {
                line.setCommittedStartTime(LocalDateTime.of(path.getPlannedReleaseDate(), LocalTime.MIN));
            }
            line.setCommittedEndTime(LocalDateTime.of(path.getRequirementDate(), LocalTime.MAX));
            line.setBomVersion(path.getBomId() == null ? null : String.valueOf(path.getBomId()));
            line.setSubstitutePolicyVersion(StrUtil.isBlank(row.getSubstitutePolicySnapshot())
                    ? null : sha256(row.getSubstitutePolicySnapshot()).substring(0, 32));
            lines.add(line);
        }
        require(lines.size() > demandNos.size(), "MOM MRP批次没有可发布的净需求供应行");
        lines.sort(Comparator.comparing(PlnErpPlanImportCommand.Line::getLineNo));
        validateParentGraph(lines, lines.stream().map(PlnErpPlanImportCommand.Line::getLineNo)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return lines;
    }

    private List<PlanBomRequirementPathDTO> parsePaths(PlanMatMrpEntity row) {
        require(StrUtil.isNotBlank(row.getBomPathSnapshot()),
                "MRP行[" + row.getId() + "]缺少BOM路径快照，不能发布正式合同");
        try {
            List<PlanBomRequirementPathDTO> paths = objectMapper.readValue(row.getBomPathSnapshot(),
                    new TypeReference<List<PlanBomRequirementPathDTO>>() {
                    });
            require(paths != null && !paths.isEmpty(),
                    "MRP行[" + row.getId() + "]BOM路径快照为空");
            return paths;
        } catch (JsonProcessingException ex) {
            throw new CheckedException("MRP行[" + row.getId() + "]BOM路径快照无法解析");
        }
    }

    private void validateParentGraph(List<PlnErpPlanImportCommand.Line> lines, Set<String> lineNos) {
        Map<String, String> parentByLine = new HashMap<>();
        for (PlnErpPlanImportCommand.Line line : lines) {
            if (line.getParentLineNo() != null) {
                require(!Objects.equals(line.getLineNo(), line.getParentLineNo()),
                        "计划行不能引用自身为父行：" + line.getLineNo());
                require(lineNos.contains(line.getParentLineNo()),
                        "计划行[" + line.getLineNo() + "]引用了不存在的父行：" + line.getParentLineNo());
            }
            parentByLine.put(line.getLineNo(), line.getParentLineNo());
        }
        for (String lineNo : lineNos) {
            Set<String> path = new HashSet<>();
            String cursor = lineNo;
            while (cursor != null) {
                require(path.add(cursor), "ERP正式计划父子关系存在循环：" + lineNo);
                cursor = parentByLine.get(cursor);
            }
        }
    }

    private ContractIdentity nextIdentity(NormalizedImport normalized, Scope scope) {
        List<PlnErpPlanInboxEntity> previous = inboxMapper.selectList(
                new LambdaQueryWrapper<PlnErpPlanInboxEntity>()
                        .eq(PlnErpPlanInboxEntity::getFactoryCode, scope.factoryCode())
                        .eq(PlnErpPlanInboxEntity::getSourceSystemCode, normalized.sourceSystemCode())
                        .eq(PlnErpPlanInboxEntity::getSourcePlanNo, normalized.sourcePlanNo())
                        .eq(PlnErpPlanInboxEntity::getStatus, "VERIFIED")
                        .orderByDesc(PlnErpPlanInboxEntity::getContractVersion)
                        .orderByAsc(PlnErpPlanInboxEntity::getId));
        if (previous.isEmpty()) {
            return new ContractIdentity("FPC-" + IdWorker.getId(), 1);
        }
        PlnErpPlanInboxEntity latest = previous.get(0);
        require(latest.getContractVersion() != null && latest.getContractVersion() > 0
                        && StrUtil.isNotBlank(latest.getContractNo()),
                "ERP计划历史回执缺少正式合同身份");
        return new ContractIdentity(latest.getContractNo(), latest.getContractVersion() + 1);
    }

    private void insertInbox(NormalizedImport normalized, Scope scope, MdmFactoryPlanningPolicyDTO policy,
                             String contractNo, Integer contractVersion) {
        LocalDateTime now = LocalDateTime.now();
        PlnErpPlanInboxEntity inbox = new PlnErpPlanInboxEntity()
                .setRequestId(normalized.requestId())
                .setRequestFingerprint(normalized.requestFingerprint())
                .setSourceSystemCode(normalized.sourceSystemCode())
                .setSourceFactoryCode(normalized.sourceFactoryCode())
                .setSourcePlanNo(normalized.sourcePlanNo())
                .setSourcePlanVersion(normalized.sourcePlanVersion())
                .setSourceContentFingerprint(normalized.sourceContentFingerprint())
                .setNormalizedPayloadJson(toJson(normalized))
                .setStatus("VERIFIED")
                .setContractNo(contractNo)
                .setContractVersion(contractVersion)
                .setPlanningPolicyId(parsePositiveLong(policy.getId(), "计划协同策略ID"))
                .setPlanningPolicyVersion(policy.getVersionNo())
                .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                .setVerifiedTime(now)
                .setFactoryCode(scope.factoryCode());
        inbox.setId(IdWorker.getId());
        inbox.setTenantId(scope.tenantId());
        inbox.setCreateBy("ERP:" + normalized.sourceSystemCode());
        inbox.setCreateTime(now);
        inbox.setDelFlag("0");
        require(inboxMapper.insertVerified(inbox) == 1, "保存ERP正式计划收件回执失败");
    }

    private PlnFormalPlanContractDTO verifiedByReceipt(PlnErpPlanInboxEntity receipt) {
        require(StrUtil.isNotBlank(receipt.getContractNo()) && receipt.getContractVersion() != null,
                "ERP收件回执缺少正式计划合同身份");
        return getVerifiedContract(receipt.getContractNo(), receipt.getContractVersion());
    }

    private PlnFormalPlanContractDTO assemble(List<PlnFormalPlanContractEntity> rows) {
        PlnFormalPlanContractEntity first = rows.get(0);
        PlnFormalPlanContractDTO dto = new PlnFormalPlanContractDTO();
        dto.setContractNo(first.getContractNo());
        dto.setContractVersion(first.getContractVersion());
        dto.setSourceSystemCode(first.getSourceSystemCode());
        dto.setSourceFactoryCode(first.getSourceFactoryCode());
        dto.setSourcePlanNo(first.getSourcePlanNo());
        dto.setSourcePlanVersion(first.getSourcePlanVersion());
        dto.setPlanningPolicyId(String.valueOf(first.getPlanningPolicyId()));
        dto.setPlanningPolicyVersion(first.getPlanningPolicyVersion());
        dto.setPlanningPolicyFingerprint(first.getPlanningPolicyFingerprint());
        dto.setSourceContentFingerprint(first.getSourceContentFingerprint());
        dto.setVerificationStatus(first.getVerificationStatus());
        dto.setContractStatus(first.getContractStatus());
        dto.setVerifiedTime(first.getVerifiedTime());
        dto.setLines(rows.stream().sorted(Comparator.comparing(PlnFormalPlanContractEntity::getLineNo))
                .map(this::toLine).toList());
        return dto;
    }

    private PlnFormalPlanContractDTO.Line toLine(PlnFormalPlanContractEntity row) {
        PlnFormalPlanContractDTO.Line line = new PlnFormalPlanContractDTO.Line();
        line.setId(String.valueOf(row.getId()));
        line.setLineNo(row.getLineNo());
        line.setParentLineNo(row.getParentLineNo());
        line.setSourceDemandNo(row.getSourceDemandNo());
        line.setSupplyType(row.getSupplyType());
        line.setErpProductionOrderNo(row.getErpProductionOrderNo());
        line.setProductCode(row.getProductCode());
        line.setProductName(row.getProductName());
        line.setPlannedQuantity(row.getPlannedQuantity());
        line.setUnitCode(row.getUnitCode());
        line.setDemandDate(row.getDemandDate());
        line.setCommittedStartTime(row.getCommittedStartTime());
        line.setCommittedEndTime(row.getCommittedEndTime());
        line.setBomVersion(row.getBomVersion());
        line.setRouteVersion(row.getRouteVersion());
        line.setSubstitutePolicyVersion(row.getSubstitutePolicyVersion());
        line.setContentFingerprint(row.getContentFingerprint());
        return line;
    }

    private String lineFingerprint(ContractIdentity identity, NormalizedImport normalized,
                                   PlnErpPlanImportCommand.Line line, MdmFactoryPlanningPolicyDTO policy) {
        return sha256(canonical("FORMAL_PLAN_LINE_V1", identity.contractNo(), identity.contractVersion(),
                normalized.sourceSystemCode(), normalized.sourceFactoryCode(), normalized.sourcePlanNo(),
                normalized.sourcePlanVersion(), line.getLineNo(), line.getParentLineNo(),
                line.getSourceDemandNo(), line.getSupplyType(), line.getErpProductionOrderNo(),
                line.getProductCode(), line.getProductName(),
                decimal(line.getPlannedQuantity()), line.getUnitCode(), line.getDemandDate(),
                line.getCommittedStartTime(), line.getCommittedEndTime(), line.getBomVersion(),
                line.getRouteVersion(), line.getSubstitutePolicyVersion(), policy.getId(),
                policy.getVersionNo(), policy.getContentFingerprint()));
    }

    private String canonicalLines(List<PlnErpPlanImportCommand.Line> lines) {
        StringBuilder result = new StringBuilder("ERP_PLAN_CONTENT_V1");
        for (PlnErpPlanImportCommand.Line line : lines) {
            result.append('\n').append(canonical(line.getLineNo(), line.getParentLineNo(), line.getSourceDemandNo(),
                    line.getSupplyType(), line.getErpProductionOrderNo(), line.getProductCode(), line.getProductName(),
                    decimal(line.getPlannedQuantity()), line.getUnitCode(), line.getDemandDate(),
                    line.getCommittedStartTime(), line.getCommittedEndTime(), line.getBomVersion(),
                    line.getRouteVersion(), line.getSubstitutePolicyVersion()));
        }
        return result.toString();
    }

    private String toJson(NormalizedImport normalized) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "schemaVersion", "ERP_PLAN_IMPORT_V1",
                    "requestId", normalized.requestId(),
                    "sourceSystemCode", normalized.sourceSystemCode(),
                    "sourceFactoryCode", normalized.sourceFactoryCode(),
                    "sourcePlanNo", normalized.sourcePlanNo(),
                    "sourcePlanVersion", normalized.sourcePlanVersion(),
                    "sourceContentFingerprint", normalized.sourceContentFingerprint(),
                    "lines", normalized.lines()));
        } catch (JsonProcessingException ex) {
            throw new CheckedException("ERP正式计划规范载荷序列化失败");
        }
    }

    private record Scope(Long tenantId, String factoryCode) {
    }

    private record ContractIdentity(String contractNo, Integer contractVersion) {
    }

    private record NormalizedImport(String requestId, String requestFingerprint,
                                    String sourceSystemCode, String sourceFactoryCode,
                                    String sourcePlanNo, String sourcePlanVersion,
                                    String sourceContentFingerprint,
                                    List<PlnErpPlanImportCommand.Line> lines) {
    }

    private record PathLine(PlanMatMrpEntity row, PlanBomRequirementPathDTO path, String lineNo) {
    }
}
