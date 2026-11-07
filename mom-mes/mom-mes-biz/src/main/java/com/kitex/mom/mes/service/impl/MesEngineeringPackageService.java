package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.dto.MdmProcedureLaborStandardDTO;
import com.kitex.mom.mdm.support.MdmProcedureLaborStandardCodec;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkCenterDTO;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MES生产工单精确工程包冻结器。
 *
 * <p>上游只能传递releaseId+contentHash和产品；路线、BOM及联合包正文
 * 始终由MES持当前实名令牌向MDM精确取回。这样页面或集成方不能用
 * “同一路线编码”替换已批准的工艺、BOM、参数或SOP原文。</p>
 */
@Service
@RequiredArgsConstructor
public class MesEngineeringPackageService {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final RemoteMdmRouteEngineeringService engineeringReleases;

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("工程包指纹计算失败", ex);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 接收PLAN/APS时取回并校验原冻结包。
     */
    public FrozenEngineering resolve(MesTaskReceiveDTO receive) {
        require(receive != null, "MES工程版本核验缺少接收命令");
        FrozenEngineering frozen = resolve(receive.getEngineeringReleaseId(), receive.getEngineeringContentHash(),
                receive.getMatCode(), receive.getFactoryCode());
        require(receive.getEngineeringVersionNo() == null
                        || Objects.equals(receive.getEngineeringVersionNo(), frozen.source().getVersionNo()),
                "PLAN传入的工程显示版本号与MDM原版本不一致");
        require(receive.getProcessRouteCode() == null
                        || Objects.equals(receive.getProcessRouteCode(), frozen.source().getRouteCode()),
                "PLAN传入的路线编码与精确工程版本不一致");
        return frozen;
    }

    /**
     * 首次下达前再次复核版本未撤回、当前来源未漂移，且MES未被改写。
     */
    public FrozenEngineering verifyBeforeIssue(MesTaskEntity task) {
        require(task != null && task.getEngineeringBundleSnapshot() != null,
                "生产工单缺少MDM工程联合包，请从PLAN重新下达");
        FrozenEngineering current = resolve(task.getEngineeringReleaseId(), task.getEngineeringContentHash(),
                task.getMatCode(), task.getFactoryCode());
        require(Objects.equals(task.getEngineeringVersionNo(), current.source().getVersionNo())
                        && Objects.equals(task.getEngineeringBundleSnapshot(), current.source().getBundleSnapshot())
                        && Objects.equals(task.getProcessRouteId(), current.source().getRouteId())
                        && Objects.equals(task.getProcessRouteCode(), current.source().getRouteCode())
                        && sameProjection(task.getProcessRouteSnapshot(), current.routeSnapshot())
                        && Objects.equals(task.getBomId(), current.source().getBomId())
                        && Objects.equals(task.getBomVersion(), current.source().getBomVersion())
                        && sameProjection(task.getBomSnapshot(), current.bomSnapshot()),
                "MES工单工程包、路线或BOM快照已变化，禁止换用配置下达");
        return current;
    }

    /** 已下达工单只解析保存的原包，不查询今日MDM；调用方再核其实际消费投影。 */
    public FrozenEngineering readIssuedOriginal(MesTaskEntity task) {
        require(
                task != null
                        && task.getTenantId() != null
                        && task.getTenantId() > 0
                        && task.getEngineeringReleaseId() != null
                        && task.getEngineeringReleaseId() > 0
                        && task.getEngineeringVersionNo() != null
                        && task.getEngineeringVersionNo() > 0
                        && text(task.getFactoryCode())
                        && text(task.getMatCode())
                        && task.getProcessRouteId() != null
                        && task.getProcessRouteId() > 0
                        && text(task.getProcessRouteCode())
                        && task.getBomId() != null
                        && task.getBomId() > 0
                        && text(task.getBomVersion())
                        && task.getEngineeringBundleSnapshot() != null
                        && Objects.equals(
                                task.getEngineeringContentHash(),
                                sha256(task.getEngineeringBundleSnapshot())),
                "已下达工单工程原件、产品或路线/BOM身份缺失");
        var source = new MdmRouteEngineeringDTO.ProductionPackage();
        source.setSchemaVersion(MdmRouteEngineeringDTO.SCHEMA_VERSION);
        source.setReleaseId(task.getEngineeringReleaseId());
        source.setVersionNo(task.getEngineeringVersionNo());
        source.setContentHash(task.getEngineeringContentHash());
        source.setTenantId(task.getTenantId().toString());
        source.setFactoryCode(task.getFactoryCode());
        source.setProductCode(task.getMatCode());
        source.setRouteId(task.getProcessRouteId());
        source.setRouteCode(task.getProcessRouteCode());
        source.setBomId(task.getBomId());
        source.setBomVersion(task.getBomVersion());
        source.setBundleSnapshot(task.getEngineeringBundleSnapshot());
        return parseFrozenSource(source, task.getMatCode(), task.getFactoryCode());
    }

    /** 原包UTF-8正文仍逐字核对；派生投影的数字比较不因2与2.0而误拒绝，也不转浮点。 */
    boolean sameProjection(String persisted, String approved) {
        if (persisted == null || approved == null) return false;
        try {
            JsonNode left = JSON.readTree(persisted);
            JsonNode right = JSON.readTree(approved);
            return left != null
                    && right != null
                    && left.equals(
                            (a, b) -> {
                                if (a.isNumber() && b.isNumber())
                                    return a.decimalValue().compareTo(b.decimalValue());
                                return a.equals(b) ? 0 : 1;
                            },
                            right);
        } catch (Exception failure) {
            throw new CheckedException("工程派生投影JSON损坏，禁止生产消费", failure);
        }
    }

    private FrozenEngineering resolve(Long releaseId, String contentHash, String productCode, String factoryCode) {
        require(releaseId != null && releaseId > 0 && contentHash != null
                        && contentHash.matches("[0-9a-f]{64}") && text(productCode) && text(factoryCode),
                "MES接收必须提供完整的工程版本对、产品和工厂");
        R<MdmRouteEngineeringDTO.ProductionPackage> response = engineeringReleases.resolveForProduction(
                new MdmRouteEngineeringDTO.ProductionQuery().setReleaseId(releaseId)
                        .setContentHash(contentHash).setProductCode(productCode));
        require(response != null && response.isOk() && response.getData() != null,
                "MDM精确工程包不可用：" + (response == null ? "无响应" : response.getMsg()));
        MdmRouteEngineeringDTO.ProductionPackage source = response.getData();
        require(Objects.equals(releaseId, source.getReleaseId())
                        && Objects.equals(contentHash, source.getContentHash())
                        && Objects.equals(productCode, source.getProductCode())
                        && text(source.getTenantId())
                        && Objects.equals(factoryCode, source.getFactoryCode())
                        && MdmRouteEngineeringDTO.SCHEMA_VERSION.equals(source.getSchemaVersion())
                        && source.getVersionNo() != null && source.getVersionNo() > 0
                        && source.getRouteId() != null && text(source.getRouteCode())
                        && source.getBomId() != null && text(source.getBomVersion())
                        && source.getBundleSnapshot() != null
                        && Objects.equals(contentHash, sha256(source.getBundleSnapshot())),
                "MDM工程包身份、组织、协议或原文指纹不一致");
        return parseFrozenSource(source, productCode, factoryCode);
    }

    private FrozenEngineering parseFrozenSource(MdmRouteEngineeringDTO.ProductionPackage source,
                                                 String productCode, String factoryCode) {
        try {
            JsonNode root = JSON.readTree(source.getBundleSnapshot());
            JsonNode graph = root.path("graph");
            JsonNode procedures = graph.path("procedureList");
            JsonNode frozenNodes = root.path("nodes");
            MdmProcedureLaborStandardCodec.captureGeneration(source.getBundleSnapshot());
            JsonNode bomChildren = root.path("bomStructure").path("children");
            require(root.isObject() && procedures.isArray() && !procedures.isEmpty() && procedures.size() <= 200
                            && frozenNodes.isArray() && frozenNodes.size() == procedures.size()
                            && bomChildren.isArray()
                            && source.getTenantId().equals(root.path("tenantId").asText())
                            && factoryCode.equals(root.path("factoryCode").asText())
                            && String.valueOf(source.getRouteId()).equals(root.path("route").path("id").asText())
                            && source.getRouteCode().equals(root.path("route").path("routeCode").asText())
                            && productCode.equals(root.path("route").path("productCode").asText())
                            && String.valueOf(source.getBomId()).equals(root.path("bom").path("id").asText())
                            && source.getBomVersion().equals(root.path("bom").path("version").asText()),
                    "MDM工程包内部路线、BOM、产品或组织身份不一致");
            JsonNode parameterCatalog = root.path("parameterCatalog");
            JsonNode sopManifest = root.path("sopManifest");
            require(parameterCatalog.isObject() && sopManifest.isObject(),
                    "MDM工程包缺少精确参数目录或正式SOP清单");
            return new FrozenEngineering(source, executionRouteSnapshot(procedures, frozenNodes, source.getTenantId(), factoryCode), bomChildren.toString(),
                    parameterCatalog.toString(), sopManifest.toString());
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("MDM工程包JSON无法完整解析，禁止生产消费");
        }
    }

    /**
     * 将联合包中的节点图和同版节点来源合成MES执行快照。
     * 图清单保存稳定节点关系；nodes清单保存同版工作中心、投料和旧附件，二者必须一一对应。
     */
    private String executionRouteSnapshot(JsonNode procedures, JsonNode frozenNodes, String tenant, String factory) {
        Map<String, JsonNode> byNode = new LinkedHashMap<>();
        for (JsonNode frozen : frozenNodes) {
            String nodeCode = frozen.path("routeNodeCode").asText();
            require(nodeCode.matches("RN-[1-9][0-9]{0,18}")
                            && byNode.putIfAbsent(nodeCode, frozen) == null
                            && frozen.path("works").isArray()
                            && frozen.path("materials").isArray()
                            && frozen.path("molds").isArray()
                            && frozen.path("legacySops").isArray(),
                    "MDM工程包节点执行来源缺失、重复或格式损坏");
        }
        ArrayNode result = JSON.createArrayNode();
        for (JsonNode procedure : procedures) {
            require(procedure instanceof ObjectNode, "MDM工程包路线节点格式损坏");
            ObjectNode execution = ((ObjectNode) procedure).deepCopy();
            String nodeCode = execution.path("routeNodeCode").asText();
            JsonNode frozen = byNode.remove(nodeCode);
            require(frozen != null && Objects.equals(execution.path("processCode").asText(),
                            frozen.path("processCode").asText()),
                    "MDM工程包路线节点与节点执行来源不一致");
            // 只接受同版nodes权威来源；历史缺失保持缺失，不从当前工序补齐。
            execution.remove("laborStandard");
            if (frozen.has("laborStandard")) {
                require(frozen.path("laborStandard").isObject(), "工程节点标准工时来源格式损坏");
                try {
                    var labor = JSON.treeToValue(frozen.path("laborStandard"), MdmProcedureLaborStandardDTO.class);
                    MdmProcedureLaborStandardCodec.read(labor, tenant, factory, execution.path("processCode").asText());
                } catch (CheckedException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new CheckedException("工程节点标准工时来源无法完整解析", failure);
                }
                execution.set("laborStandard", frozen.path("laborStandard").deepCopy());
            }
            execution.set("mdmProcessProcedureWorkList", frozen.path("works").deepCopy());
            execution.set("mdmProcessProcedureMatList", frozen.path("materials").deepCopy());
            execution.set("mdmProcessProcedureMoldList", frozen.path("molds").deepCopy());
            execution.set("mdmProcessProcedureSopList", frozen.path("legacySops").deepCopy());
            result.add(execution);
        }
        require(byNode.isEmpty(), "MDM工程包存在未进入路线图的孤立节点来源");
        return result.toString();
    }

    /**
     * 下达时从同版原件投影执行工位。原工程包及已保存的来源快照不做重写，
     * 工厂建模证据封套不能直接当作旧版 workCode/workName DTO 反序列化。
     */
    public List<MdmProcessRouteWorkingDTO> executionRoutes(FrozenEngineering frozen) {
        try {
            var rows = cn.hutool.json.JSONUtil.toList(frozen.routeSnapshot(), MdmProcessRouteWorkingDTO.class);
            JsonNode originals = JSON.readTree(frozen.routeSnapshot());
            require(originals.isArray() && originals.size() == rows.size(), "工程执行节点来源不完整");
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                JsonNode works = originals.get(i).path("mdmProcessProcedureWorkList");
                require(works.isArray(), "工程节点缺少原工作中心来源");
                var codes = new HashSet<String>();
                var projected = new ArrayList<MdmProcessRouteWorkCenterDTO>();
                for (JsonNode work : works) {
                    String code;
                    String name;
                    if (work.has("binding") || work.has("actual")) {
                        JsonNode binding = work.path("binding"), actual = work.path("actual");
                        require(binding.isObject() && actual.isObject()
                                        && frozen.source().getTenantId().equals(binding.path("tenantId").asText())
                                        && frozen.source().getFactoryCode().equals(binding.path("factoryCode").asText())
                                        && frozen.source().getFactoryCode().equals(actual.path("factoryCode").asText())
                                        && Objects.equals(row.getProcessCode(), binding.path("procedureCode").asText())
                                        && actual.path("stationCode").asText().equals(binding.path("stationCode").asText()),
                                "工程节点工作中心的人员组织、工序或工位证据不一致");
                        code = actual.path("stationCode").asText();
                        name = actual.path("stationName").asText();
                    } else {
                        // 兼容原已发布的平面工位来源；同样只读原包，不查询今日工位。
                        code = work.path("workCode").asText();
                        name = work.path("workName").asText();
                    }
                    require(text(code) && !code.contains(",") && text(name) && codes.add(code),
                            "工程节点工作中心编码或名称缺失、重复");
                    var target = new MdmProcessRouteWorkCenterDTO();
                    target.setWorkCode(code);
                    target.setWorkName(name);
                    projected.add(target);
                }
                row.setMdmProcessProcedureWorkList(projected);
            }
            return rows;
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("工程节点工作中心来源无法完整转换，禁止下达");
        }
    }

    /**
     * MES从原冻结包解析出的可持久路线与BOM快照。
     */
    public record FrozenEngineering(MdmRouteEngineeringDTO.ProductionPackage source,
                                    String routeSnapshot,
                                    String bomSnapshot,
                                    String parameterCatalogSnapshot,
                                    String sopManifestSnapshot) {
    }
}
