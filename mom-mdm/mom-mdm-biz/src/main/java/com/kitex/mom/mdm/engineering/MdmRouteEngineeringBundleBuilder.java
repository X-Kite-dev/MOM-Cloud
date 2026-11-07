package com.kitex.mom.mdm.engineering;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.agent.service.MdmAgentActualScopeAccess;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.*;
import com.kitex.mom.mdm.service.impl.MdmBomGovernance;
import com.kitex.mom.mdm.sop.MdmFormalSopSourceService;
import com.kitex.mom.mdm.sop.MdmSopSupport;
import com.kitex.mom.mdm.support.MdmRouteNodeGraphSupport;
import com.kitex.mom.mdm.support.MdmProcedureLaborStandardCodec;
import com.kitex.mom.mdm.vo.MdmFactoryModelAncestorVO;
import com.kitex.mom.mdm.vo.MdmProcessRouteProcedureVO;
import com.kitex.mom.mdm.vo.MdmProcessRouteWorkingVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * 工程版本权威采集器。只封存服务器真实来源，不接受客户端正文，也不改写来源发布状态。
 * 父路线锁先于配置采集；BOM目录锁稳定整棵版本树。跨域来源状态仍仅代表本次观察。
 */
@Component
@RequiredArgsConstructor
public class MdmRouteEngineeringBundleBuilder {
    private static final int MAX_ROWS = 10_000;
    private static final List<String> CATEGORIES = List.of("SOP", "PARAMETER", "MATERIAL", "TOOLING", "MOLD", "SETTING");
    private final MdmAgentActualScopeAccess access;
    private final MdmProcessRouteMapper routes;
    private final MdmProcessRouteService routeService;
    private final MdmBomMapper boms;
    private final MdmBomService bomService;
    private final MdmBomGovernance bomGovernance;
    private final MdmMaterialMapper materials;
    private final MdmProcedureMapper procedures;
    private final MdmProcessProcedureSettingMapper settings;
    private final MdmProcessProcedureMatMapper assignments;
    private final MdmProcedureToolingRequirementMapper tooling;
    private final MdmToolingMapper toolingMaster;
    private final MdmProcessProcedureSopMapper legacySops;
    private final MdmStationSettingService stationService;
    private final MdmFactoryModelService factoryService;
    private final MdmFactoryModelMapper factoryModels;
    private final MdmProcessParameterStandardService parameters;
    private final MdmFormalSopSourceService sops;
    private final MdmRouteEngineeringSourceMapper sources;
    private final MdmProcedureLaborStandardCapture laborStandards;
    private final ObjectMapper objectMapper;

    /**
     * 当前真实授权路线的完整图与正式BOM候选；上下文不表示预检通过。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MdmRouteEngineeringDTO.Context context(Long routeId) {
        MdmProcessRouteEntity route = lockedRoute(routeId);
        MdmProcessRouteProcedureVO graph = graph(route);
        LocalDate today = LocalDate.now();
        List<MdmBomEntity> candidates = boms.selectList(new LambdaQueryWrapper<MdmBomEntity>()
                .eq(MdmBomEntity::getTenantId, tenant())
                .eq(MdmBomEntity::getFactoryCode, factory())
                .eq(MdmBomEntity::getProductCode, route.getProductCode())
                .eq(MdmBomEntity::getReleaseState, "RELEASED")
                .eq(MdmBomEntity::getEnabled, true)
                .and(q -> q.isNull(MdmBomEntity::getStartDate).or().le(MdmBomEntity::getStartDate, today))
                .and(q -> q.isNull(MdmBomEntity::getEndDate).or().ge(MdmBomEntity::getEndDate, today))
                .orderByAsc(MdmBomEntity::getVersion)
                .orderByAsc(MdmBomEntity::getId)
                .last("LIMIT 10001"));
        bounded(candidates.size(), "BOM候选");
        candidates.forEach(bom -> formalBom(bom, route.getProductCode()));
        return new MdmRouteEngineeringDTO.Context()
                .setRoute(route)
                .setGraph(graph)
                .setBoms(candidates);
    }

    /**
     * 锁真实父路线后完整重采；原文指纹对应本次实际返回的UTF-8字节。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MdmRouteEngineeringDTO.Preview build(MdmRouteEngineeringDTO.PreviewQuery query) {
        return build(query, true);
    }

    private MdmRouteEngineeringDTO.Preview build(MdmRouteEngineeringDTO.PreviewQuery query, boolean captureLabor) {
        require(query != null, "请提供路线、精确BOM与节点适用声明");
        MdmProcessRouteEntity route = lockedRoute(query.getRouteId());
        require(query.getExpectedGraphRevision() != null
                        && Objects.equals(route.getGraphRevision(), query.getExpectedGraphRevision()),
                "路线图已变化，请刷新图版本后重新预检");
        MdmProcessRouteProcedureVO graph = graph(route);
        // BOM现有写入口统一锁工厂目录，因此读取整棵来源也沿用同一目录锁。
        bomGovernance.access(factory(), "read", true);
        MdmBomEntity bom = exactBom(query.getBomId(), route.getProductCode());
        require(blank(route.getBomVersion()) || Objects.equals(route.getBomVersion(), bom.getVersion()),
                "所选BOM版本与路线指定版本不一致，请核对工程来源");
        List<MdmBomChildDTO> structure = bomService.getMaintenanceStructure(bom.getId());
        Map<Long, MdmBomEntity> bomSources = new TreeMap<>();
        bomSources.put(bom.getId(), bom);
        Set<String> bomMaterials = new HashSet<>();
        int[] count = {0};
        validateBomTree(structure, bom, bomSources, bomMaterials, new HashSet<>(Set.of(bom.getId())), count);
        Map<String, MdmProcessRouteWorkingVO> nodes = new TreeMap<>();
        graph.getProcedureList().forEach(node -> nodes.put(node.getRouteNodeCode(), node));
        List<String> gaps = new ArrayList<>();
        List<MdmRouteEngineeringDTO.Applicability> declarations = declarations(query.getDeclarations(), nodes.keySet(), gaps);
        MdmProcessParameterStandardDTO.FrozenCatalog catalog = parameters.resolveNodes(
                new MdmProcessParameterStandardDTO.ResolveQuery()
                        .setFactoryCode(factory())
                        .setProcessRouteId(route.getId())
                        .setRouteCode(route.getRouteCode())
                        .setRouteNodeCodes(new ArrayList<>(nodes.keySet())));
        catalog.setResolvedAt(null); // 采集时间不参与工程正文；保留原标准contentFingerprint。
        MdmFormalSopDTO.Manifest manifest = sops.resolve(new MdmFormalSopDTO.ResolveQuery()
                .setFactoryCode(factory())
                .setNodes(nodes.values().stream()
                        .map(node -> new MdmFormalSopDTO.Node()
                                .setRouteCode(route.getRouteCode())
                                .setRouteNodeCode(node.getRouteNodeCode())
                                .setProcessCode(node.getProcessCode()))
                        .toList()));
        List<MdmProcessProcedureSettingEntity> settingRows = settings.selectList(new LambdaQueryWrapper<MdmProcessProcedureSettingEntity>()
                .eq(MdmProcessProcedureSettingEntity::getTenantId, tenant())
                .eq(MdmProcessProcedureSettingEntity::getFactoryCode, factory())
                .eq(MdmProcessProcedureSettingEntity::getRouteCode, route.getRouteCode())
                .orderByAsc(MdmProcessProcedureSettingEntity::getRouteNodeCode)
                .orderByAsc(MdmProcessProcedureSettingEntity::getId)
                .last("LIMIT 10001"));
        MdmMaterialSettingSource.apply(settingRows,
                settings.nullableByRoute(tenant(), factory(), route.getRouteCode()));
        List<MdmProcessProcedureMatEntity> matRows = assignments.selectList(new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
                .eq(MdmProcessProcedureMatEntity::getTenantId, tenant())
                .eq(MdmProcessProcedureMatEntity::getFactoryCode, factory())
                .eq(MdmProcessProcedureMatEntity::getRouteCode, route.getRouteCode())
                .orderByAsc(MdmProcessProcedureMatEntity::getRouteNodeCode)
                .orderByAsc(MdmProcessProcedureMatEntity::getStep)
                .orderByAsc(MdmProcessProcedureMatEntity::getId)
                .last("LIMIT 10001"));
        List<MdmProcedureToolingRequirementEntity> toolRows = tooling.selectList(new LambdaQueryWrapper<MdmProcedureToolingRequirementEntity>()
                .eq(MdmProcedureToolingRequirementEntity::getTenantId, tenant())
                .eq(MdmProcedureToolingRequirementEntity::getFactoryCode, factory())
                .eq(MdmProcedureToolingRequirementEntity::getRouteCode, route.getRouteCode())
                .orderByAsc(MdmProcedureToolingRequirementEntity::getRouteNodeCode)
                .orderByAsc(MdmProcedureToolingRequirementEntity::getRequirementSeq)
                .orderByAsc(MdmProcedureToolingRequirementEntity::getId)
                .last("LIMIT 10001"));
        List<MdmProcessProcedureSopEntity> legacyRows = legacySops.selectList(new LambdaQueryWrapper<MdmProcessProcedureSopEntity>()
                .eq(MdmProcessProcedureSopEntity::getTenantId, tenant())
                .eq(MdmProcessProcedureSopEntity::getFactoryCode, factory())
                .eq(MdmProcessProcedureSopEntity::getRouteCode, route.getRouteCode())
                .orderByAsc(MdmProcessProcedureSopEntity::getRouteNodeCode)
                .orderByAsc(MdmProcessProcedureSopEntity::getId)
                .last("LIMIT 10001"));
        ObjectMapper json = serializer();
        validateRows(json, settingRows, route, nodes, "节点设置");
        validateRows(json, matRows, route, nodes, "节点投料");
        validateRows(json, toolRows, route, nodes, "工装需求");
        validateRows(json, legacyRows, route, nodes, "旧SOP附件");
        ArrayNode molds = molds(json, route, nodes);
        // 同主工序重复节点使用同次原标准；全批按主工序编码排序锁定，避免跨路线反向锁序。
        var laborByProcess = new TreeMap<String, com.kitex.mom.mdm.dto.MdmProcedureLaborStandardDTO>();
        if (captureLabor) {
            nodes.values().stream().map(MdmProcessRouteWorkingVO::getProcessCode).distinct().sorted()
                    .forEach(process -> laborByProcess.put(process, laborStandards.capture(process)));
        }
        List<Map<String, Object>> frozenNodes = new ArrayList<>();
        Map<String, List<Map<String, Object>>> worksByProcess = new HashMap<>();
        for (var node : nodes.values()) {
            String rn = node.getRouteNodeCode();
            List<MdmProcessProcedureSettingEntity> nodeSettings = settingRows.stream().filter(row -> rn.equals(row.getRouteNodeCode())).toList();
            require(nodeSettings.size() <= 1, "节点" + rn + "设置不唯一，请修正后重新预检");
            if (!nodeSettings.isEmpty()) {
                var setting = nodeSettings.get(0);
                require(!Boolean.TRUE.equals(setting.getIsMatWeightConvert())
                                || setting.getMatWeightConvert() != null && setting.getMatWeightConvert().signum() > 0,
                        "节点" + rn + "重量转换设置损坏，请修正");
                require(!Boolean.TRUE.equals(setting.getTimeCheck())
                                || setting.getTimeVal() != null && setting.getTimeVal().signum() >= 0,
                        "节点" + rn + "转序时间设置损坏，请修正");
            }
            List<MdmProcessProcedureMatEntity> nodeMaterials = matRows.stream().filter(row -> rn.equals(row.getRouteNodeCode())).toList();
            for (var row : nodeMaterials) {
                material(row.getMaterialCode());
                require(bomMaterials.contains(row.getMaterialCode()), "节点" + rn + "投料未属于所选完整BOM，请修正物料或BOM");
                require(row.getQuantity() != null && row.getQuantity().signum() > 0 && !blank(row.getUnit()), "节点" + rn + "投料数量或单位无效");
            }
            List<MdmProcedureToolingRequirementEntity> nodeTools = toolRows.stream().filter(row -> rn.equals(row.getRouteNodeCode())).toList();
            Set<Integer> requirementSequences = new HashSet<>();
            for (var row : nodeTools) {
                require(!blank(row.getToolingType()) && row.getRequirementSeq() != null && row.getRequirementSeq() > 0
                        && requirementSequences.add(row.getRequirementSeq())
                        && row.getRequiredQuantity() != null && row.getRequiredQuantity() > 0
                        && row.getUsageValue() != null && row.getUsageValue().signum() > 0
                        && row.getUsageMode() != null && Set.of("FIXED", "PER_QUANTITY", "PER_HOUR").contains(row.getUsageMode())
                        && row.getStatus() != null && row.getMandatory() != null, "节点" + rn + "工装需求格式损坏，请修正");
                if (row.getCandidateToolingCodes() != null) {
                    Set<String> candidateCodes = new HashSet<>();
                    for (String code : row.getCandidateToolingCodes()) {
                        require(!blank(code) && candidateCodes.add(code), "节点" + rn + "工装候选编码为空或重复");
                        List<MdmToolingEntity> candidates = toolingMaster.selectList(new LambdaQueryWrapper<MdmToolingEntity>()
                                .eq(MdmToolingEntity::getTenantId, tenant()).eq(MdmToolingEntity::getFactoryCode, factory())
                                .eq(MdmToolingEntity::getToolingCode, code).last("LIMIT 2"));
                        require(candidates.size() == 1 && Objects.equals(candidates.get(0).getToolingType(), row.getToolingType()),
                                "节点" + rn + "工装候选在实际组织缺失、不唯一或类型变化");
                    }
                }
            }
            List<JsonNode> nodeMolds = new ArrayList<>();
            molds.forEach(row -> {
                if (rn.equals(row.path("routeNodeCode").asText())) {
                    nodeMolds.add(row);
                }
            });
            List<MdmProcessProcedureSopEntity> nodeLegacy = legacyRows.stream().filter(row -> rn.equals(row.getRouteNodeCode())).toList();
            List<Map<String, Object>> works = worksByProcess.computeIfAbsent(node.getProcessCode(), this::works);
            if (works.isEmpty()) {
                gaps.add("节点" + rn + "没有有效真实执行工作中心，请维护工序工位绑定");
            }
            var frozen = new LinkedHashMap<String, Object>();
            frozen.put("routeNodeCode", rn);
            frozen.put("processCode", node.getProcessCode());
            frozen.put("processName", node.getProcessName());
            if (captureLabor) {
                frozen.put("laborStandard", laborByProcess.get(node.getProcessCode()));
            }
            frozen.put("setting", nodeSettings.isEmpty() ? null : nodeSettings.get(0));
            frozen.put("materials", nodeMaterials);
            frozen.put("tooling", nodeTools);
            frozen.put("molds", nodeMolds);
            frozen.put("works", works);
            frozen.put("legacySops", nodeLegacy);
            frozenNodes.add(frozen);
            boolean formalSop = manifest.getNodes().stream().anyMatch(item -> rn.equals(item.getNode().getRouteNodeCode()) && !item.getSources().isEmpty());
            boolean parameter = catalog.getParameters().stream().anyMatch(item -> rn.equals(item.getRouteNodeCode()));
            Map<String, Boolean> configured = Map.of("SOP", formalSop || !nodeLegacy.isEmpty(), "PARAMETER", parameter,
                    "MATERIAL", !nodeMaterials.isEmpty(), "TOOLING", !nodeTools.isEmpty(), "MOLD", !nodeMolds.isEmpty(), "SETTING", !nodeSettings.isEmpty());
            Map<String, Boolean> usable = Map.of("SOP", formalSop, "PARAMETER", parameter,
                    "MATERIAL", !nodeMaterials.isEmpty(), "TOOLING", nodeTools.stream().anyMatch(row -> Boolean.TRUE.equals(row.getStatus())),
                    "MOLD", !nodeMolds.isEmpty(), "SETTING", !nodeSettings.isEmpty());
            for (var declaration : declarations) {
                if (!rn.equals(declaration.getRouteNodeCode())) {
                    continue;
                }
                String category = declaration.getCategory();
                require(!"NOT_APPLICABLE".equals(declaration.getMode()) || !configured.get(category),
                        "节点" + rn + "的" + category + "已有实际配置，不能用不适用声明隐藏来源");
                if ("REQUIRED".equals(declaration.getMode()) && !usable.get(category)) {
                    gaps.add("节点" + rn + "声明必需" + category + "但无有效来源，请维护正式配置");
                }
            }
        }
        var bundle = new LinkedHashMap<String, Object>();
        bundle.put("schemaVersion", MdmRouteEngineeringDTO.SCHEMA_VERSION);
        if (captureLabor) {
            bundle.put("laborStandardSchemaVersion", MdmProcedureLaborStandardCodec.VERSION);
        }
        bundle.put("tenantId", tenant().toString());
        bundle.put("factoryCode", factory());
        bundle.put("route", route);
        bundle.put("graph", graph);
        bundle.put("bom", bom);
        var bomStructure = new LinkedHashMap<String, Object>();
        bomStructure.put("sources", new ArrayList<>(bomSources.values()));
        bomStructure.put("children", structure);
        bundle.put("bomStructure", bomStructure);
        bundle.put("parameterCatalog", catalog);
        bundle.put("sopManifest", manifest);
        bundle.put("nodes", frozenNodes);
        bundle.put("declarations", declarations);
        String raw = write(json, bundle);
        return new MdmRouteEngineeringDTO.Preview()
                .setBundleSnapshot(raw)
                .setContentHash(MdmSopSupport.digest(raw.getBytes(StandardCharsets.UTF_8)))
                .setGraphRevision(route.getGraphRevision())
                .setReady(gaps.isEmpty())
                .setGaps(gaps)
                .setExecutionReady(gaps.isEmpty());
    }

    /** 只按已持久化原包选择采集代际；新预检始终走含标准来源的build，客户端没有降级参数。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MdmRouteEngineeringDTO.Preview rebuildForExisting(MdmRouteEngineeringDTO.PreviewQuery query, String originalBundle) {
        return build(query, MdmProcedureLaborStandardCodec.captureGeneration(originalBundle));
    }

    /**
     * 从一个已批准生产工程版本的冻结身份出发，只重采一个稳定节点的正式SOP和/或参数。
     * 返工覆盖包不复制当前整条路线，因此其他节点后来发生的主数据变化不会被偷偷带入返工。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MdmRouteEngineeringDTO.Preview buildReworkOverride(MdmRouteEngineeringDTO.PreviewQuery query,
                                                              MdmRouteEngineeringReleaseEntity base) {
        require(query != null && base != null, "返工覆盖预检缺少请求或原生产工程版本");
        require(Objects.equals(query.getRouteId(), base.getRouteId())
                        && Objects.equals(query.getBomId(), base.getBomId())
                        && Objects.equals(query.getExpectedGraphRevision(), base.getGraphRevision()),
                "返工覆盖必须沿用原生产工程版本的路线、BOM和图修订");
        MdmProcessRouteEntity route = lockedRoute(base.getRouteId());
        require(Objects.equals(route.getRouteCode(), base.getRouteCode())
                        && Objects.equals(route.getProductCode(), base.getProductCode()),
                "原生产工程版本的路线或产品归属已变化，禁止创建返工覆盖");
        List<String> categories = normalizedOverrideCategories(query.getOverrideCategories());
        String target = query.getTargetRouteNodeCode();
        require(target != null && target.matches("RN-[1-9][0-9]{0,18}"),
                "返工覆盖必须指定有效稳定节点");

        JsonNode baseBundle;
        try {
            baseBundle = objectMapper.readTree(base.getBundleSnapshot());
        } catch (Exception failure) {
            throw new CheckedException("原生产工程版本正文损坏，禁止创建返工覆盖");
        }
        require(baseBundle != null && baseBundle.isObject()
                        && MdmRouteEngineeringDTO.SCHEMA_VERSION.equals(baseBundle.path("schemaVersion").asText())
                        && baseBundle.path("tenantId").asText().equals(tenant().toString())
                        && baseBundle.path("factoryCode").asText().equals(factory()),
                "原生产工程版本协议或组织身份不一致");
        JsonNode baseNode = null;
        for (JsonNode node : baseBundle.path("nodes")) {
            if (target.equals(node.path("routeNodeCode").asText())) {
                require(baseNode == null, "原生产工程版本稳定节点重复，禁止创建返工覆盖");
                baseNode = node;
            }
        }
        require(baseNode != null, "原生产工程版本不包含目标稳定节点");
        String process = baseNode.path("processCode").asText();
        require(!blank(process), "原生产工程版本目标节点缺少主工序");

        MdmProcessParameterStandardDTO.FrozenCatalog parameterOverride = null;
        MdmFormalSopDTO.Manifest sopOverride = null;
        ObjectMapper json = serializer();
        Map<String, Object> originalEvidence = new LinkedHashMap<>();
        if (categories.contains("PARAMETER")) {
            parameterOverride = parameters.resolveNodes(new MdmProcessParameterStandardDTO.ResolveQuery()
                    .setFactoryCode(factory()).setProcessRouteId(base.getRouteId())
                    .setRouteCode(base.getRouteCode()).setRouteNodeCodes(List.of(target)));
            parameterOverride.setResolvedAt(null);
            require(Boolean.TRUE.equals(parameterOverride.getAvailable())
                            && parameterOverride.getDataGaps() != null && parameterOverride.getDataGaps().isEmpty()
                            && parameterOverride.getRouteNodeProcesses() != null
                            && parameterOverride.getRouteNodeProcesses().size() == 1
                            && Objects.equals(parameterOverride.getRouteNodeProcesses().get(target), process)
                            && parameterOverride.getParameters() != null && !parameterOverride.getParameters().isEmpty()
                            && parameterOverride.getParameters().stream().allMatch(item -> item != null
                            && target.equals(item.getRouteNodeCode()) && process.equals(item.getProcessCode())),
                    "目标节点没有完整可用的已发布参数标准，不能形成返工覆盖");
            JsonNode baseCatalog = baseBundle.path("parameterCatalog");
            require(baseCatalog.isObject() && baseCatalog.path("parameters").isArray(),
                    "原生产工程版本缺少参数目录证据");
            ArrayNode oldParameters = json.createArrayNode();
            baseCatalog.path("parameters").forEach(item -> {
                if (target.equals(item.path("routeNodeCode").asText())) oldParameters.add(item);
            });
            JsonNode newParameters = json.valueToTree(parameterOverride.getParameters());
            require(!oldParameters.equals(newParameters),
                    "目标节点参数正文与原生产工程版本完全相同，无需发布返工覆盖");
            originalEvidence.put("parameterFingerprint", MdmSopSupport.digest(
                    write(json, oldParameters).getBytes(StandardCharsets.UTF_8)));
        }
        if (categories.contains("SOP")) {
            sopOverride = sops.resolve(new MdmFormalSopDTO.ResolveQuery().setFactoryCode(factory())
                    .setNodes(List.of(new MdmFormalSopDTO.Node().setRouteCode(base.getRouteCode())
                            .setRouteNodeCode(target).setProcessCode(process))));
            require(sopOverride != null && sopOverride.isComplete() && sopOverride.getNodes() != null
                            && sopOverride.getNodes().size() == 1
                            && sopOverride.getNodes().get(0).getNode() != null
                            && target.equals(sopOverride.getNodes().get(0).getNode().getRouteNodeCode())
                            && process.equals(sopOverride.getNodes().get(0).getNode().getProcessCode())
                            && sopOverride.getNodes().get(0).getSources() != null
                            && !sopOverride.getNodes().get(0).getSources().isEmpty()
                            && sopOverride.getNodes().get(0).getGaps() != null
                            && sopOverride.getNodes().get(0).getGaps().isEmpty(),
                    "目标节点没有完整可用的正式SOP，不能形成返工覆盖");
            JsonNode oldSop = null;
            JsonNode manifest = baseBundle.path("sopManifest");
            require(manifest.isObject() && manifest.path("nodes").isArray(),
                    "原生产工程版本缺少正式SOP清单证据");
            for (JsonNode node : manifest.path("nodes")) {
                if (target.equals(node.path("node").path("routeNodeCode").asText())) {
                    require(oldSop == null, "原生产工程版本正式SOP节点重复");
                    oldSop = node;
                }
            }
            require(oldSop != null, "原生产工程版本缺少目标节点正式SOP证据");
            JsonNode newSop = json.valueToTree(sopOverride.getNodes().get(0));
            require(!oldSop.equals(newSop),
                    "目标节点正式SOP正文与原生产工程版本完全相同，无需发布返工覆盖");
            originalEvidence.put("sopFingerprint", MdmSopSupport.digest(
                    write(json, oldSop).getBytes(StandardCharsets.UTF_8)));
        }

        var policy = new LinkedHashMap<String, Object>();
        policy.put("schemaVersion", MdmRouteEngineeringDTO.REWORK_OVERRIDE_SCHEMA_VERSION);
        policy.put("tenantId", tenant().toString());
        policy.put("factoryCode", factory());
        policy.put("baseReleaseId", base.getId().toString());
        policy.put("baseContentHash", base.getContentHash());
        policy.put("routeId", base.getRouteId().toString());
        policy.put("routeCode", base.getRouteCode());
        policy.put("productCode", base.getProductCode());
        policy.put("bomId", base.getBomId().toString());
        policy.put("bomVersion", base.getBomVersion());
        policy.put("graphRevision", base.getGraphRevision().toString());
        policy.put("targetRouteNodeCode", target);
        policy.put("processCode", process);
        policy.put("overrideCategories", categories);
        policy.put("originalEvidence", originalEvidence);
        policy.put("parameterCatalog", parameterOverride);
        policy.put("sopManifest", sopOverride);
        String raw = write(json, policy);
        require(raw.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024,
                "返工覆盖包超过2MiB，请拆分SOP或参数来源，不能截断");
        return new MdmRouteEngineeringDTO.Preview()
                .setBundleSnapshot(raw)
                .setContentHash(MdmSopSupport.digest(raw.getBytes(StandardCharsets.UTF_8)))
                .setGraphRevision(base.getGraphRevision())
                .setReady(true).setExecutionReady(true)
                .setReleasePurpose(MdmRouteEngineeringDTO.PURPOSE_REWORK_OVERRIDE);
    }

    /**
     * 规范化为稳定顺序，拒绝把材料、工装等越权塞入返工覆盖。
     */
    private List<String> normalizedOverrideCategories(List<String> values) {
        require(values != null && !values.isEmpty() && values.size() <= 2,
                "返工覆盖须明确SOP和/或PARAMETER");
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            require(Set.of("SOP", "PARAMETER").contains(value) && unique.add(value),
                    "返工覆盖类别只能是唯一的SOP、PARAMETER");
        }
        return unique.stream().sorted().toList();
    }

    /**
     * 用当前实名组织核验真实路线，父行锁先于全部工程来源读取。
     */
    private MdmProcessRouteEntity lockedRoute(Long id) {
        require(TransactionSynchronizationManager.isActualTransactionActive()
                        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                "工程采集必须在非只读业务事务中锁定真实路线");
        access.require(factory(), "read");
        require(id != null && id > 0, "请选择真实工艺路线");
        List<MdmProcessRouteEntity> matched = routes.selectList(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                .eq(MdmProcessRouteEntity::getTenantId, tenant())
                .eq(MdmProcessRouteEntity::getFactoryCode, factory())
                .eq(MdmProcessRouteEntity::getId, id)
                .last("LIMIT 2 FOR UPDATE"));
        require(matched.size() == 1, "路线不存在或不属于当前真实组织");
        MdmProcessRouteEntity route = matched.get(0);
        require(Boolean.TRUE.equals(route.getStatus()) && MdmRouteNodeGraphSupport.VERSION.equals(route.getGraphSchemaVersion()),
                "工程发布只支持已启用且已持久保存ROUTE_NODE_GRAPH_V2的路线，请先完善路线图");
        require(routes.selectCount(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                        .eq(MdmProcessRouteEntity::getTenantId, tenant())
                        .eq(MdmProcessRouteEntity::getFactoryCode, factory())
                        .eq(MdmProcessRouteEntity::getRouteCode, route.getRouteCode())) == 1,
                "当前组织路线编码不唯一，请修正主数据");
        material(route.getProductCode());
        return route;
    }

    /**
     * 复用现有图规范化，并以稳定RN排序节点及关系，排除DAO返回顺序。
     */
    private MdmProcessRouteProcedureVO graph(MdmProcessRouteEntity route) {
        MdmProcessRouteProcedureVO graph = routeService.getProcessWorking(route.getId());
        require(graph != null && graph.getProcedureList() != null && !graph.getProcedureList().isEmpty()
                && graph.getProcedureList().size() <= 200, "工程发布必须完整采集1至200个路线节点，不能截断");
        Set<String> seen = new HashSet<>();
        for (var node : graph.getProcedureList()) {
            require(node != null && node.getId() != null && Objects.equals(node.getTenantId(), tenant())
                    && Objects.equals(node.getFactoryCode(), factory()) && !blank(node.getRouteNodeCode())
                    && seen.add(node.getRouteNodeCode()) && !blank(node.getProcessCode()), "路线节点身份或组织损坏，请修正真实图");
            List<MdmProcedureEntity> rows = procedures.selectList(new LambdaQueryWrapper<MdmProcedureEntity>()
                    .eq(MdmProcedureEntity::getTenantId, tenant())
                    .eq(MdmProcedureEntity::getFactoryCode, factory())
                    .eq(MdmProcedureEntity::getProcessCode, node.getProcessCode())
                    .last("LIMIT 2"));
            require(rows.size() == 1 && Boolean.TRUE.equals(rows.get(0).getStatus()), "节点" + node.getRouteNodeCode() + "引用工序缺失、停用或不唯一");
            if (node.getPreRouteNodeCodes() != null) {
                Arrays.sort(node.getPreRouteNodeCodes());
            }
            if (node.getNextRouteNodeCodes() != null) {
                Arrays.sort(node.getNextRouteNodeCodes());
            }
            if (node.getPreRouteCode() != null) {
                Arrays.sort(node.getPreRouteCode());
            }
            if (node.getNextRouteCode() != null) {
                Arrays.sort(node.getNextRouteCode());
            }
        }
        graph.getProcedureList().sort(Comparator.comparing(MdmProcessRouteWorkingVO::getRouteNodeCode));
        return graph;
    }

    /**
     * 读取精确BOM版本，不以默认版或当前头替代指定ID。
     */
    private MdmBomEntity exactBom(Long id, String product) {
        require(id != null && id > 0, "请选择精确正式BOM版本");
        MdmBomEntity row = boms.selectOne(new LambdaQueryWrapper<MdmBomEntity>()
                .eq(MdmBomEntity::getTenantId, tenant())
                .eq(MdmBomEntity::getFactoryCode, factory())
                .eq(MdmBomEntity::getId, id));
        formalBom(row, product);
        return row;
    }

    /**
     * 每个根/子BOM都须具有同组织、同产品的正式有效版本证明。
     */
    private void formalBom(MdmBomEntity row, String product) {
        LocalDate now = LocalDate.now();
        require(row != null && Objects.equals(row.getTenantId(), tenant()) && Objects.equals(row.getFactoryCode(), factory())
                && Objects.equals(row.getProductCode(), product) && "RELEASED".equals(row.getReleaseState()) && Boolean.TRUE.equals(row.getEnabled())
                && !blank(row.getVersion()) && (row.getStartDate() == null || !row.getStartDate().isAfter(now))
                && (row.getEndDate() == null || !row.getEndDate().isBefore(now)), "BOM不存在、串组织/产品或未正式发布启用且日期有效，请选择有效精确版本");
        material(product);
    }

    /**
     * 逐层核验完整版本树，每层按真实明细ID排序；保留原替代认证正文。
     */
    private void validateBomTree(List<MdmBomChildDTO> children, MdmBomEntity parent, Map<Long, MdmBomEntity> references,
                                 Set<String> codes, Set<Long> path, int[] count) {
        require(children != null && path.size() <= 64, "BOM结构缺失或超过64层，不能完整冻结");
        require(children.stream().noneMatch(Objects::isNull), "BOM结构含空明细，禁止冻结");
        children.sort(Comparator.comparing(MdmBomChildDTO::getId, Comparator.nullsFirst(Long::compareTo)));
        bomService.validateEngineeringSubstitutes(parent, children.stream().map(child -> (MdmBomChildEntity) child).toList());
        Set<Long> unique = new HashSet<>();
        for (var child : children) {
            bounded(++count[0], "完整BOM明细");
            require(child != null && child.getId() != null && unique.add(child.getId()) && Objects.equals(child.getBomId(), parent.getId())
                    && Objects.equals(child.getTenantId(), tenant()) && Objects.equals(child.getFactoryCode(), factory())
                    && child.getQuantity() != null && child.getQuantity().signum() > 0 && !blank(child.getUnit()), "BOM明细身份、组织或数量单位损坏");
            material(child.getMaterialCode());
            codes.add(child.getMaterialCode());
            if (!blank(child.getReplaceCodes())) {
                for (String code : child.getReplaceCodes().split(",")) {
                    require(!blank(code), "BOM替代料编码损坏");
                    material(code.trim());
                    codes.add(code.trim());
                }
            }
            Long subId = child.getSubitemBomId();
            if (subId != null && subId != 0) {
                require(path.add(subId), "BOM存在循环版本引用，禁止冻结");
                MdmBomEntity sub = exactBom(subId, child.getMaterialCode());
                references.put(subId, sub);
                validateBomTree(child.getChildren(), sub, references, codes, path, count);
                path.remove(subId);
            } else {
                require(child.getChildren() == null || child.getChildren().isEmpty(),
                        "BOM未引用子版本却含子层正文");
            }
        }
    }

    /**
     * 工程引用的每个物料都必须在当前真实组织唯一且启用。
     */
    private void material(String code) {
        require(!blank(code), "工程物料身份不能为空");
        List<MdmMaterialEntity> matched = materials.selectList(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getTenantId, tenant())
                .eq(MdmMaterialEntity::getFactoryCode, factory())
                .eq(MdmMaterialEntity::getMaterialCode, code)
                .last("LIMIT 2"));
        require(matched.size() == 1 && Boolean.TRUE.equals(matched.get(0).getStatus()), "物料" + code + "在真实组织缺失、停用或不唯一");
    }

    /**
     * 固定六类显式声明并稳定排序，缺声明形成缺口，禁止推断不适用。
     */
    private List<MdmRouteEngineeringDTO.Applicability> declarations(List<MdmRouteEngineeringDTO.Applicability> inputs,
                                                                    Set<String> nodes, List<String> gaps) {
        require(inputs != null && inputs.size() <= 1200, "节点适用声明不能为空且最多1200项");
        Map<String, MdmRouteEngineeringDTO.Applicability> values = new TreeMap<>();
        for (var input : inputs) {
            require(input != null && input.getRouteNodeCode() != null && nodes.contains(input.getRouteNodeCode())
                    && input.getCategory() != null && CATEGORIES.contains(input.getCategory()) && input.getMode() != null
                    && Set.of("REQUIRED", "NOT_APPLICABLE").contains(input.getMode()), "适用声明须明确真实节点、六类来源与合法模式");
            String reason = input.getReason() == null ? "" : input.getReason().trim();
            require(reason.length() <= 1000 && reason.indexOf('\0') < 0
                    && (!"NOT_APPLICABLE".equals(input.getMode()) || !reason.isEmpty()), "不适用声明须填写非空依据，最多1000字符");
            var value = new MdmRouteEngineeringDTO.Applicability()
                    .setRouteNodeCode(input.getRouteNodeCode())
                    .setCategory(input.getCategory())
                    .setMode(input.getMode())
                    .setReason(reason);
            require(values.put(input.getRouteNodeCode() + "\0" + input.getCategory(), value) == null, "同节点同类适用声明不能重复");
        }
        for (String node : nodes) {
            for (String category : CATEGORIES) {
                if (!values.containsKey(node + "\0" + category)) {
                    gaps.add("节点" + node + "缺少" + category + "适用声明");
                }
            }
        }
        return new ArrayList<>(values.values());
    }

    /**
     * 检查全部路线配置，不静默隐藏孤立节点或变化后的主工序归属。
     */
    private void validateRows(ObjectMapper json, List<?> rows, MdmProcessRouteEntity route,
                              Map<String, MdmProcessRouteWorkingVO> nodes, String label) {
        bounded(rows.size(), label);
        for (Object row : rows) {
            JsonNode value = json.valueToTree(row);
            String rn = value.path("routeNodeCode").asText();
            var node = nodes.get(rn);
            require(node != null && value.path("id").asText().matches("[1-9][0-9]*")
                    && tenant().toString().equals(value.path("tenantId").asText()) && factory().equals(value.path("factoryCode").asText())
                    && route.getRouteCode().equals(value.path("routeCode").asText())
                    && node.getProcessCode().equals(value.path("processCode").asText()), label + "存在孤立节点、身份损坏或工序归属变化，请修正后发布");
        }
    }

    /**
     * 限定函数必须返回完整文本ID数组，验证组织/节点后按RN和绑定ID排序。
     */
    private ArrayNode molds(ObjectMapper json, MdmProcessRouteEntity route, Map<String, MdmProcessRouteWorkingVO> nodes) {
        JsonNode value;
        try {
            value = json.readTree(sources.molds(tenant(), factory(), route.getRouteCode()));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new CheckedException("限定模具来源返回的完整JSON损坏，禁止冻结");
        }
        require(value != null && value.isArray(), "限定模具来源必须返回完整绑定数组");
        bounded(value.size(), "模具绑定");
        List<JsonNode> ordered = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        value.forEach(row -> {
            require(row.isObject(), "模具绑定来源格式损坏");
            for (String key : List.of("id", "tenantId", "factoryCode", "routeCode", "routeNodeCode", "processCode", "moldCode", "moldName")) {
                require(row.has(key) && row.get(key).isTextual() && !row.get(key).asText().isBlank(),
                        "模具绑定来源缺少文本字段" + key);
            }
            var node = nodes.get(row.path("routeNodeCode").asText());
            require(ids.add(row.path("id").asText()) && row.path("id").asText().matches("[1-9][0-9]{0,18}")
                    && tenant().toString().equals(row.path("tenantId").asText()) && factory().equals(row.path("factoryCode").asText())
                    && route.getRouteCode().equals(row.path("routeCode").asText()) && node != null
                    && node.getProcessCode().equals(row.path("processCode").asText()), "模具绑定存在重复、串组织、孤立节点或工序归属变化");
            ordered.add(row);
        });
        ordered.sort(Comparator.comparing((JsonNode row) -> row.path("routeNodeCode").asText())
                .thenComparing(row -> row.path("id").asText()));
        ArrayNode result = json.createArrayNode();
        ordered.forEach(result::add);
        return result;
    }

    /**
     * 工作中心沿用真实工序绑定与工厂建模服务，并重新核对当前完整启用祖先链。
     */
    private List<Map<String, Object>> works(String process) {
        // 原按工序读取的SQL未显式限定工厂，使用同一真实服务的组织限定查询，避免同码串厂。
        List<MdmStationSettingEntity> rows = new ArrayList<>(stationService.list(new LambdaQueryWrapper<MdmStationSettingEntity>()
                .eq(MdmStationSettingEntity::getTenantId, tenant())
                .eq(MdmStationSettingEntity::getFactoryCode, factory())
                .eq(MdmStationSettingEntity::getProcedureCode, process)
                .eq(MdmStationSettingEntity::getStatus, true)
                .eq(MdmStationSettingEntity::getDelFlag, "0")
                .orderByAsc(MdmStationSettingEntity::getId)
                .last("LIMIT 10001")));
        bounded(rows.size(), "工序执行工作中心");
        rows.sort(Comparator.comparing(MdmStationSettingEntity::getId, Comparator.nullsFirst(Long::compareTo)));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Long> identities = new HashSet<>();
        for (var binding : rows) {
            require(binding != null && binding.getId() != null && identities.add(binding.getId())
                    && Objects.equals(binding.getTenantId(), tenant()) && Objects.equals(binding.getFactoryCode(), factory())
                    && Objects.equals(binding.getProcedureCode(), process) && Boolean.TRUE.equals(binding.getStatus())
                    && "0".equals(binding.getDelFlag()), "执行工作中心绑定身份、组织或状态损坏");
            List<MdmFactoryModelEntity> chain = modelChain(binding.getStationId());
            List<MdmFactoryModelAncestorVO> ancestors = factoryService.getStationByAllParent(new Long[]{binding.getStationId()});
            require(ancestors.size() == 1, "执行工位完整祖先来源不唯一");
            var actual = ancestors.get(0);
            require(Objects.equals(actual.getStationId(), binding.getStationId()) && Objects.equals(actual.getStationCode(), binding.getStationCode())
                            && Objects.equals(actual.getLineId(), binding.getLineId()) && Objects.equals(actual.getWorkshopId(), binding.getWorkshopId())
                            && Objects.equals(actual.getFactoryId(), binding.getFactoryId()) && factory().equals(actual.getFactoryCode()),
                    "执行工作中心已迁移或不属于实际工厂，请重绑工序工位");
            var work = new LinkedHashMap<String, Object>();
            work.put("binding", binding);
            work.put("actual", actual);
            work.put("models", chain);
            result.add(work);
        }
        return result;
    }

    /**
     * 沿真实父ID核验启用建模链，提前拒绝循环，避免祖先服务无界展开。
     */
    private List<MdmFactoryModelEntity> modelChain(Long stationId) {
        require(stationId != null && stationId > 0, "工作中心缺少真实工位ID");
        List<MdmFactoryModelEntity> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Long id = stationId;
        // 现有建模创建/导入以-1作为产业根父ID；历史null根同样允许。
        while (id != null && !Long.valueOf(-1L).equals(id)) {
            require(id > 0, "工作中心建模父ID无效，根只能使用null或-1终点");
            require(chain.size() < 64 && seen.add(id), "工作中心建模祖先循环或超过64层");
            MdmFactoryModelEntity row = factoryModels.selectOne(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                    .eq(MdmFactoryModelEntity::getTenantId, tenant())
                    .eq(MdmFactoryModelEntity::getId, id));
            require(row != null && Boolean.TRUE.equals(row.getStatus()) && "0".equals(row.getDelFlag()), "工作中心或祖先缺失、停用或串租户");
            chain.add(row);
            id = row.getParentId();
        }
        require(chain.size() == 5
                        && "STATION".equals(chain.get(0).getType())
                        && "LINE".equals(chain.get(1).getType())
                        && "WORKSHOP".equals(chain.get(2).getType())
                        && "FACTORY".equals(chain.get(3).getType())
                        && "INDUSTRY".equals(chain.get(4).getType())
                        && factory().equals(chain.get(3).getCode()),
                "执行工作中心必须具有真实工位、线体、车间、当前工厂和合法产业根关系");
        for (int index = 0; index < chain.size(); index++) {
            require(Integer.valueOf(5 - index).equals(chain.get(index).getLevel()),
                    "执行工作中心建模类型与实际层级不一致");
        }
        return chain;
    }

    /**
     * 复制应用注入的日期/时区配置，仅补长ID文本化，不新建另一套日期表达。
     */
    private ObjectMapper serializer() {
        ObjectMapper json = objectMapper.copy();
        SimpleModule identifiers = new SimpleModule();
        identifiers.addSerializer(Long.class, ToStringSerializer.instance);
        identifiers.addSerializer(Long.TYPE, ToStringSerializer.instance);
        json.registerModule(identifiers);
        return json;
    }

    /**
     * 直接生成待持久化原文；外层对这些字节求hash，不解析重排后替代原文。
     */
    private String write(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new CheckedException("工程完整正文不能序列化，请核对来源格式");
        }
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private String factory() {
        return FactoryContextHolder.getFactoryCode();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 多读一条用于容量判定，任何超界都拒绝，不冻结截断来源。
     */
    private void bounded(int size, String label) {
        require(size <= MAX_ROWS,
                label + "超过10000项完整冻结上限，请拆分工程来源，不允许截断");
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
