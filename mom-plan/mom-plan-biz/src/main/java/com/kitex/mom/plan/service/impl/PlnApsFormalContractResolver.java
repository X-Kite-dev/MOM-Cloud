package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.entity.PlnFormalPlanContractEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;
import com.kitex.mom.plan.mapper.PlnFormalPlanContractMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper;
import com.kitex.mom.plan.service.PlnFactoryPlanningAuthorityService;
import com.kitex.mom.plan.service.PlnFormalPlanContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * APS 正式发布前的计划合同解析器。
 *
 * <p>仿真阶段可以使用尚未发布的业务需求，但正式发布必须把候选中的每个生产计划精确归入
 * 当前工厂、当前策略下的同一份已核验合同。当前案件模型只冻结一份合同，因此跨合同候选必须
 * 拆分案件，不能只选择其中一份合同作为代表而丢失其余来源。</p>
 */
@Component
@RequiredArgsConstructor
public class PlnApsFormalContractResolver {

    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnFormalPlanContractMapper contractMapper;
    private final PlnFormalPlanContractService contractService;
    private final PlnFactoryPlanningAuthorityService authorityService;

    /** 解析并复核选中候选唯一可发布的正式计划合同。 */
    public Resolution resolve(Long selectedTaskId, String factoryCode) {
        require(selectedTaskId != null && selectedTaskId > 0, "APS候选任务主键不能为空");
        require(StrUtil.isNotBlank(factoryCode), "APS正式发布缺少工厂上下文");
        MdmFactoryPlanningPolicyDTO policy = authorityService.requireCurrentPolicy(factoryCode);

        List<PlnSchedulingTaskDetailEntity> details = taskDetailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, selectedTaskId)
                        .eq(PlnSchedulingTaskDetailEntity::getFactoryCode, factoryCode)
                        .orderByAsc(PlnSchedulingTaskDetailEntity::getIssueId));
        require(!details.isEmpty(), "APS候选没有可核验的来源生产计划");
        List<Long> issueIds = details.stream().map(PlnSchedulingTaskDetailEntity::getIssueId)
                .filter(Objects::nonNull).distinct().toList();
        require(issueIds.size() == details.size(), "APS候选来源生产计划存在重复或缺失");
        List<PlnIssueEntity> issues = issueMapper.selectList(new LambdaQueryWrapper<PlnIssueEntity>()
                .in(PlnIssueEntity::getId, issueIds)
                .eq(PlnIssueEntity::getFactoryCode, factoryCode)
                .orderByAsc(PlnIssueEntity::getId));
        require(issues.size() == issueIds.size(), "APS候选来源生产计划已变化或不属于当前工厂");
        return resolveIssues(issues, factoryCode, policy);
    }

    /** 供普通下达和 APS 下达共同使用，避免旧入口绕过正式合同门禁。 */
    public Resolution resolveIssues(List<PlnIssueEntity> issues, String factoryCode) {
        require(StrUtil.isNotBlank(factoryCode), "生产计划下达缺少工厂上下文");
        return resolveIssues(issues, factoryCode, authorityService.requireCurrentPolicy(factoryCode));
    }

    private Resolution resolveIssues(List<PlnIssueEntity> issues, String factoryCode,
                                     MdmFactoryPlanningPolicyDTO policy) {
        require(issues != null && !issues.isEmpty(), "没有可核验的生产计划");
        require(issues.stream().allMatch(issue -> StrUtil.isNotBlank(issue.getSourceDemandNo())
                        && StrUtil.isNotBlank(issue.getProductCode())
                        && issue.getNum() != null && issue.getNum().signum() > 0),
                "APS候选存在缺少来源需求、产品或数量的生产计划，不能正式发布");

        Set<String> demandNos = issues.stream().map(PlnIssueEntity::getSourceDemandNo)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PlnFormalPlanContractEntity> contractRows = contractMapper.selectList(
                new LambdaQueryWrapper<PlnFormalPlanContractEntity>()
                        .eq(PlnFormalPlanContractEntity::getFactoryCode, factoryCode)
                        .in(PlnFormalPlanContractEntity::getSourceDemandNo, demandNos)
                        .eq(PlnFormalPlanContractEntity::getPlanningPolicyId, parseId(policy.getId()))
                        .eq(PlnFormalPlanContractEntity::getPlanningPolicyVersion, policy.getVersionNo())
                        .eq(PlnFormalPlanContractEntity::getPlanningPolicyFingerprint,
                                policy.getContentFingerprint())
                        .eq(PlnFormalPlanContractEntity::getVerificationStatus, "VERIFIED")
                        .eq(PlnFormalPlanContractEntity::getContractStatus, "PUBLISHED")
                        .orderByAsc(PlnFormalPlanContractEntity::getContractNo)
                        .orderByAsc(PlnFormalPlanContractEntity::getContractVersion)
                        .orderByAsc(PlnFormalPlanContractEntity::getLineNo));
        Set<ContractKey> contracts = contractRows.stream()
                .map(row -> new ContractKey(row.getContractNo(), row.getContractVersion()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        require(!contracts.isEmpty(), "APS候选尚未关联当前策略下的已核验正式计划合同");
        require(contracts.size() == 1,
                "当前候选跨越多份正式计划合同；请按合同拆分APS案件后分别发布");
        ContractKey key = contracts.iterator().next();
        PlnFormalPlanContractDTO contract = contractService.getVerifiedContract(
                key.contractNo(), key.contractVersion());
        verifyIssueCoverage(issues, contract);
        authorityService.revalidate(policy);
        Map<Long, BigDecimal> issueQuantities = issues.stream().collect(Collectors.toMap(
                PlnIssueEntity::getId, PlnIssueEntity::getNum, (left, right) -> {
                    throw new CheckedException("生产计划主键重复，不能形成MES订单数量");
                }, LinkedHashMap::new));
        return new Resolution(policy, contract, resolveProductionOrders(issues, contract, policy),
                Map.copyOf(issueQuantities));
    }

    private Map<Long, String> resolveProductionOrders(List<PlnIssueEntity> issues,
                                                       PlnFormalPlanContractDTO contract,
                                                       MdmFactoryPlanningPolicyDTO policy) {
        Map<DemandProduct, Set<String>> erpOrders = contract.getLines().stream()
                .filter(line -> "MAKE".equals(line.getSupplyType()))
                .collect(Collectors.groupingBy(
                        line -> new DemandProduct(line.getSourceDemandNo(), line.getProductCode()),
                        Collectors.mapping(PlnFormalPlanContractDTO.Line::getErpProductionOrderNo,
                                Collectors.toCollection(LinkedHashSet::new))));
        Map<Long, String> result = new LinkedHashMap<>();
        for (PlnIssueEntity issue : issues) {
            Set<String> candidates = erpOrders.getOrDefault(
                    new DemandProduct(issue.getSourceDemandNo(), issue.getProductCode()), Set.of()).stream()
                    .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
            require(candidates.size() <= 1,
                    "生产计划[" + issue.getIssueNo() + "]匹配到多个ERP生产订单，禁止猜测映射");
            if ("ERP_ORDER_REQUIRED".equals(policy.getProductionOrderSyncMode())) {
                require(candidates.size() == 1,
                        "生产计划[" + issue.getIssueNo() + "]尚未取得ERP正式生产订单，不能下达MES");
                result.put(issue.getId(), candidates.iterator().next());
            } else {
                result.put(issue.getId(), StrUtil.blankToDefault(
                        candidates.stream().findFirst().orElse(null), issue.getIssueNo()));
            }
        }
        return Map.copyOf(result);
    }

    private void verifyIssueCoverage(List<PlnIssueEntity> issues, PlnFormalPlanContractDTO contract) {
        Map<DemandProduct, BigDecimal> contracted = contract.getLines().stream()
                .filter(line -> "MAKE".equals(line.getSupplyType()))
                .collect(Collectors.groupingBy(
                        line -> new DemandProduct(line.getSourceDemandNo(), line.getProductCode()),
                        Collectors.reducing(BigDecimal.ZERO, PlnFormalPlanContractDTO.Line::getPlannedQuantity,
                                BigDecimal::add)));
        Map<DemandProduct, BigDecimal> selected = issues.stream().collect(Collectors.groupingBy(
                issue -> new DemandProduct(issue.getSourceDemandNo(), issue.getProductCode()),
                Collectors.reducing(BigDecimal.ZERO, PlnIssueEntity::getNum, BigDecimal::add)));
        for (Map.Entry<DemandProduct, BigDecimal> entry : selected.entrySet()) {
            BigDecimal available = contracted.get(entry.getKey());
            require(available != null && available.compareTo(entry.getValue()) >= 0,
                    "生产计划[" + entry.getKey().demandNo() + "/" + entry.getKey().productCode()
                            + "]未被正式合同覆盖或数量超出合同");
        }
        Set<String> selectedDemands = selected.keySet().stream().map(DemandProduct::demandNo)
                .collect(Collectors.toSet());
        Set<String> contractDemands = contracted.keySet().stream().map(DemandProduct::demandNo)
                .collect(Collectors.toSet());
        require(contractDemands.containsAll(selectedDemands), "正式计划合同未覆盖APS候选全部来源需求");
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            require(id > 0, "当前计划权威策略主键不合法");
            return id;
        } catch (RuntimeException exception) {
            if (exception instanceof CheckedException checked) {
                throw checked;
            }
            throw new CheckedException("当前计划权威策略主键不合法");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    /** 发布时同时冻结策略与合同，调用方不能只保存合同号。 */
    public record Resolution(MdmFactoryPlanningPolicyDTO policy,
                             PlnFormalPlanContractDTO contract,
                             Map<Long, String> productionOrderByIssueId,
                             Map<Long, BigDecimal> issueQuantityById) {
    }

    private record ContractKey(String contractNo, Integer contractVersion) {
    }

    private record DemandProduct(String demandNo, String productCode) {
    }
}
