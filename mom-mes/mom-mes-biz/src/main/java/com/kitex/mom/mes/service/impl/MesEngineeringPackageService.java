package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final RemoteMdmRouteEngineeringService engineeringReleases;

    /** 接收PLAN/APS时取回并校验原冻结包。 */
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

    /** 首次下达前再次复核版本未撤回、当前来源未漂移，且MES未被改写。 */
    public FrozenEngineering verifyBeforeIssue(MesTaskEntity task) {
        require(task != null && task.getEngineeringBundleSnapshot() != null,
                "生产工单缺少MDM工程联合包，请从PLAN重新下达");
        FrozenEngineering current = resolve(task.getEngineeringReleaseId(), task.getEngineeringContentHash(),
                task.getMatCode(), task.getFactoryCode());
        require(Objects.equals(task.getEngineeringVersionNo(), current.source().getVersionNo())
                        && Objects.equals(task.getEngineeringBundleSnapshot(), current.source().getBundleSnapshot())
                        && Objects.equals(task.getProcessRouteId(), current.source().getRouteId())
                        && Objects.equals(task.getProcessRouteCode(), current.source().getRouteCode())
                        && Objects.equals(task.getProcessRouteSnapshot(), current.routeSnapshot())
                        && Objects.equals(task.getBomId(), current.source().getBomId())
                        && Objects.equals(task.getBomVersion(), current.source().getBomVersion())
                        && Objects.equals(task.getBomSnapshot(), current.bomSnapshot()),
                "MES工单工程包、路线或BOM快照已变化，禁止换用配置下达");
        return current;
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
        try {
            JsonNode root = JSON.readTree(source.getBundleSnapshot());
            JsonNode graph = root.path("graph");
            JsonNode procedures = graph.path("procedureList");
            JsonNode frozenNodes = root.path("nodes");
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
            return new FrozenEngineering(source, executionRouteSnapshot(procedures, frozenNodes), bomChildren.toString(),
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
    private String executionRouteSnapshot(JsonNode procedures, JsonNode frozenNodes) {
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
            execution.set("mdmProcessProcedureWorkList", frozen.path("works").deepCopy());
            execution.set("mdmProcessProcedureMatList", frozen.path("materials").deepCopy());
            execution.set("mdmProcessProcedureMoldList", frozen.path("molds").deepCopy());
            execution.set("mdmProcessProcedureSopList", frozen.path("legacySops").deepCopy());
            result.add(execution);
        }
        require(byNode.isEmpty(), "MDM工程包存在未进入路线图的孤立节点来源");
        return result.toString();
    }

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

    /** MES从原冻结包解析出的可持久路线与BOM快照。 */
    public record FrozenEngineering(MdmRouteEngineeringDTO.ProductionPackage source,
                                    String routeSnapshot,
                                    String bomSnapshot,
                                    String parameterCatalogSnapshot,
                                    String sopManifestSnapshot) {
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
