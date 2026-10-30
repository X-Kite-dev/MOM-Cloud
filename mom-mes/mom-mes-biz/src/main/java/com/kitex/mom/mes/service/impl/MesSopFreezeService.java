package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.feign.RemoteMdmFormalSopService;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesFormalSopNodeDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 正式SOP从MDM权威源冻结到MES稳定节点，并在执行时核精确旧版的安全撤回状态。
 * 没有正式绑定不伪造批准；MDM不可核验则阻断正式源新下达/新开工，不换今日版本。
 */
@Service
@RequiredArgsConstructor
public class MesSopFreezeService {
    private final RemoteMdmFormalSopService sources;

    public static String fingerprint(MesFormalSopNodeDTO node) {
        return hashBytes(JSONUtil.toJsonStr(Arrays.asList(node.getSchemaVersion(), node.getSourceManifestHash(),
                node.getTenantId(), node.getFactoryCode(), node.getRouteCode(), node.getRouteNodeCode(), node.getProcessCode(),
                node.getSources(), node.getGaps())).getBytes(StandardCharsets.UTF_8));
    }

    private static String hashBytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算正式SOP冻结字节指纹", exception);
        }
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean positiveId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}");
    }

    private static String processCode(MdmProcessRouteWorkingDTO route) {
        return route.getProcessCode() == null || route.getProcessCode().isBlank() ? route.getRouteCode() : route.getProcessCode();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 新下达完整解析，调用者在同一MES事务保存包含formalSops的原工艺快照。
     */
    public void freeze(MesTaskEntity task, List<MdmProcessRouteWorkingDTO> routes) {
        require(task != null && task.getTenantId() != null && routes != null && !routes.isEmpty() && routes.size() <= 200,
                "正式SOP下达冻结须明确工单且包含1至200个真实节点");
        Map<String, MdmProcessRouteWorkingDTO> expected = new LinkedHashMap<>();
        List<MdmFormalSopDTO.Node> nodes = new ArrayList<>();
        for (var route : routes) {
            require(route != null && route.getRouteNodeCode() != null && route.getRouteNodeCode().matches("RN-[1-9][0-9]{0,18}")
                    && expected.putIfAbsent(route.getRouteNodeCode(), route) == null, "正式SOP冻结缺少唯一稳定路线节点");
            String process = processCode(route);
            require(process != null && !process.isBlank(), "正式SOP冻结缺少节点主工序");
            nodes.add(new MdmFormalSopDTO.Node().setRouteCode(task.getProcessRouteCode())
                    .setRouteNodeCode(route.getRouteNodeCode()).setProcessCode(process));
        }
        var response = sources.resolveForIssue(new MdmFormalSopDTO.ResolveQuery().setFactoryCode(task.getFactoryCode()).setNodes(nodes));
        require(response != null && response.isOk() && response.getData() != null, "MDM正式SOP完整目录不可核验，禁止新下达");
        applyManifest(task, routes, response.getData());
    }

    /**
     * 直接消费工程联合版本中批准的SOP manifest，不重选今日绑定。
     */
    public void freezeFromEngineeringSnapshot(MesTaskEntity task, List<MdmProcessRouteWorkingDTO> routes,
                                              String snapshot) {
        require(snapshot != null && !snapshot.isBlank(), "工程版本缺少正式SOP清单");
        MdmFormalSopDTO.Manifest manifest;
        try {
            manifest = JSONUtil.toBean(snapshot, MdmFormalSopDTO.Manifest.class);
        } catch (Exception ex) {
            throw new CheckedException("工程版本正式SOP清单JSON损坏");
        }
        applyManifest(task, routes, manifest);
    }

    /**
     * 正式返工沿用质量来源节点当时已冻结的正式SOP，不能在返工下达日重新解析当前绑定。
     * 原节点明确无正式绑定时，空来源及原缺口仍作为批准证据保存。
     */
    public void validateReworkSnapshot(MesTaskEntity task, List<MdmProcessRouteWorkingDTO> routes) {
        require(task != null && task.getTenantId() != null && routes != null && routes.size() == 1,
                "返工正式SOP复核须绑定唯一真实节点");
        MdmProcessRouteWorkingDTO route = routes.get(0);
        String process = processCode(route);
        validateFrozen(route.getFormalSops(), task.getTenantId().toString(), task.getFactoryCode(),
                task.getProcessRouteCode(), route.getRouteNodeCode(), process);
    }

    private void applyManifest(MesTaskEntity task, List<MdmProcessRouteWorkingDTO> routes,
                               MdmFormalSopDTO.Manifest manifest) {
        require(task != null && task.getTenantId() != null && routes != null && !routes.isEmpty()
                && routes.size() <= 200, "正式SOP冻结须明确工单且包含1至200个真实节点");
        Map<String, MdmProcessRouteWorkingDTO> expected = new LinkedHashMap<>();
        for (var route : routes) {
            require(route != null && route.getRouteNodeCode() != null
                            && route.getRouteNodeCode().matches("RN-[1-9][0-9]{0,18}")
                            && expected.putIfAbsent(route.getRouteNodeCode(), route) == null
                            && processCode(route) != null && !processCode(route).isBlank(),
                    "正式SOP冻结缺少唯一稳定路线节点或主工序");
        }
        require(manifest.isComplete() && "FORMAL_SOP_MANIFEST_V1".equals(manifest.getSchemaVersion())
                && Objects.equals(manifest.getTenantId(), task.getTenantId().toString())
                && Objects.equals(manifest.getFactoryCode(), task.getFactoryCode())
                && validHash(manifest.getManifestHash()) && manifest.getNodes() != null
                && manifest.getNodes().size() == expected.size(), "MDM正式SOP目录作用域或完整性不一致");
        Set<String> visited = new HashSet<>();
        int count = 0;
        for (var item : manifest.getNodes()) {
            require(item != null && item.getNode() != null && item.getSources() != null && item.getGaps() != null,
                    "MDM正式SOP节点清单缺失，不允许降级成无正式SOP");
            var node = item.getNode();
            var route = expected.get(node.getRouteNodeCode());
            require(route != null && visited.add(node.getRouteNodeCode())
                    && Objects.equals(node.getRouteCode(), task.getProcessRouteCode())
                    && Objects.equals(node.getProcessCode(), processCode(route)), "MDM正式SOP真实路线节点归属不一致");
            count += item.getSources().size();
            require(count <= 1000, "正式SOP冻结超过1000份，不允许截断");
            Set<String> versionFamilies = new HashSet<>();
            for (var source : item.getSources()) {
                validateSource(source, task.getTenantId().toString(), task.getFactoryCode());
                require(source.isIssueAllowed() && source.isFrozenStartAllowed() && "RELEASED".equals(source.getStatus())
                        && versionFamilies.add(source.getSopNo()), "正式SOP不可下达或同节点同编号出现重复正式版本");
            }
            var frozen = new MesFormalSopNodeDTO();
            frozen.setSchemaVersion("MES_FORMAL_SOP_NODE_V1");
            frozen.setSourceManifestHash(manifest.getManifestHash());
            frozen.setTenantId(task.getTenantId().toString());
            frozen.setFactoryCode(task.getFactoryCode());
            frozen.setRouteCode(node.getRouteCode());
            frozen.setRouteNodeCode(node.getRouteNodeCode());
            frozen.setProcessCode(node.getProcessCode());
            frozen.setSources(item.getSources());
            frozen.setGaps(item.getGaps());
            frozen.setManifestHash(fingerprint(frozen));
            route.setFormalSops(frozen);
        }
        require(visited.equals(expected.keySet()), "部分正式SOP节点未返回，禁止不完整冻结");
    }

    /**
     * 只追加正式SOP字段，原快照未被本DTO建模的工程信息也原样保留，不能序列化DTO时丢失。
     */
    public String mergeFrozenSnapshot(String original, List<MdmProcessRouteWorkingDTO> routes) {
        var array = JSONUtil.parseArray(original);
        require(array.size() == routes.size(), "原路线快照节点数量已变化，不能合并正式SOP");
        Map<String, MesFormalSopNodeDTO> byNode = new LinkedHashMap<>();
        routes.forEach(route -> byNode.put(route.getRouteNodeCode(), route.getFormalSops()));
        Set<String> visited = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            var item = array.getJSONObject(index);
            String node = item.getStr("routeNodeCode");
            require(byNode.containsKey(node) && visited.add(node) && byNode.get(node) != null,
                    "原路线快照与正式SOP冻结节点不一致");
            item.set("formalSops", byNode.get(node));
            array.set(index, item);
        }
        String result = array.toString();
        require(result.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024,
                "合并后的完整SOP路线快照超过2MiB，请拆分真实工艺资料后下达，不允许截断证据");
        return result;
    }

    /**
     * 核MES已冻结节点本体，不访问今日绑定目录，也不从旧附件猜正式来源。
     */
    public void validateFrozen(MesFormalSopNodeDTO node, String tenant, String factory, String routeCode,
                               String nodeCode, String process) {
        require(node != null && "MES_FORMAL_SOP_NODE_V1".equals(node.getSchemaVersion())
                && Objects.equals(node.getTenantId(), tenant) && Objects.equals(node.getFactoryCode(), factory)
                && Objects.equals(node.getRouteCode(), routeCode) && Objects.equals(node.getRouteNodeCode(), nodeCode)
                && Objects.equals(node.getProcessCode(), process) && validHash(node.getSourceManifestHash())
                && node.getSources() != null && node.getSources().size() <= 1000 && node.getGaps() != null
                && Objects.equals(node.getManifestHash(), fingerprint(node)), "MES正式SOP原冻结节点或内容指纹损坏");
        for (var source : node.getSources()) validateSource(source, tenant, factory);
    }

    /**
     * 纯读取证，失败明确UNKNOWN；不写开工事实，也不以错误吞掉正式门禁。
     */
    public Map<String, Object> currentState(MesFormalSopNodeDTO node) {
        if (node == null) return Map.of("status", "LEGACY_REFERENCE", "allowed", true,
                "message", "历史任务没有正式SOP冻结证明，保持旧引用语义，不自动回填批准", "sources", List.of());
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean allowed = true;
        String overall = node.getSources().isEmpty() ? "NO_FORMAL_BINDING" : "VERIFIED";
        for (var source : node.getSources()) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("versionId", source.getVersionId());
            check.put("sopNo", source.getSopNo());
            check.put("versionNo", source.getVersionNo());
            check.put("contentHash", source.getContentHash());
            try {
                var response = sources.verify(new MdmFormalSopDTO.VerifyQuery().setFactoryCode(node.getFactoryCode())
                        .setVersionId(source.getVersionId()).setContentHash(source.getContentHash()));
                require(response != null && response.isOk() && response.getData() != null
                                && response.getData().isMatched() && response.getData().getSource() != null,
                        "MDM未返回原冻结版一致证明");
                var actual = response.getData().getSource();
                validateSource(actual, node.getTenantId(), node.getFactoryCode());
                require(Objects.equals(actual.getVersionId(), source.getVersionId())
                        && Objects.equals(actual.getContentHash(), source.getContentHash())
                        && Objects.equals(actual.getOriginalHash(), source.getOriginalHash())
                        && Objects.equals(actual.getOriginalId(), source.getOriginalId()), "MDM精确版本或原件身份与原冻结版不一致");
                boolean usable = actual.isFrozenStartAllowed() && Set.of("RELEASED", "RETIRED").contains(actual.getStatus());
                check.put("status", actual.getStatus());
                check.put("allowed", usable);
                check.put("message", Objects.toString(response.getData().getMessage(), ""));
                check.put("withdrawalReason", Objects.toString(actual.getWithdrawalReason(), ""));
                if (!usable) {
                    allowed = false;
                    overall = "WITHDRAWN";
                }
            } catch (Exception failure) {
                check.put("status", "UNKNOWN");
                check.put("allowed", false);
                check.put("message", "精确正式SOP源或原件当前不可核验，需恢复MDM/存储或由工艺人员核查；不能放行");
                allowed = false;
                if (!"WITHDRAWN".equals(overall)) overall = "UNKNOWN";
            }
            checks.add(check);
        }
        return Map.of("status", overall, "allowed", allowed, "sources", checks,
                "message", allowed ? "原冻结正式来源允许继续进入MES其他开工检查" : "正式SOP存在安全撤回或不可核验来源，禁止签收及新开工/复工");
    }

    /**
     * 调用者已验证派工与冻结文件key，只向MDM请求精确旧版原件。
     */
    public ResponseEntity<byte[]> original(String factory, String versionId, String contentHash, String originalHash) {
        var response = sources.original(new MdmFormalSopDTO.VerifyQuery().setFactoryCode(factory)
                .setVersionId(versionId).setContentHash(contentHash));
        require(response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                && response.getBody().length > 0 && response.getBody().length <= 20 * 1024 * 1024
                && Objects.equals(originalHash, hashBytes(response.getBody())), "MDM原件传输结果与冻结字节hash不一致");
        return response;
    }

    private void validateSource(MdmFormalSopDTO.Source source, String tenant, String factory) {
        require(source != null && "FORMAL_SOP_VERSION".equals(source.getSourceType())
                        && "FORMAL_SOP_V1".equals(source.getSchemaVersion())
                        && Objects.equals(tenant, source.getTenantId()) && Objects.equals(factory, source.getFactoryCode())
                        && positiveId(source.getVersionId()) && positiveId(source.getOriginalId())
                        && source.getVersionNo() != null && source.getVersionNo() > 0
                        && source.getSopNo() != null && !source.getSopNo().isBlank()
                        && source.getName() != null && !source.getName().isBlank()
                        && validHash(source.getContentHash()) && validHash(source.getOriginalHash())
                        && source.getApprovedBy() != null && source.getApprovedAt() != null && source.getEffectiveFrom() != null,
                "正式SOP来源身份、版本、批准或字节证明不完整");
    }
}
