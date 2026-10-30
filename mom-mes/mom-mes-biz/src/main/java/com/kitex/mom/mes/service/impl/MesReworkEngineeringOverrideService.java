package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesReworkMaterialCommand;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 正式返工的单节点SOP/参数工程口径。
 *
 * <p>本服务只接受MDM已批准覆盖版本对，或者明确继承原实际节点；客户端不能提交SOP、参数正文。
 * 覆盖包始终绑定原生产工程版本和稳定节点，不能借返工改变BOM、路线关系、投料、工装或模具。</p>
 */
@Service
@RequiredArgsConstructor
public class MesReworkEngineeringOverrideService {
    public static final String VERSION = "MES_REWORK_ENGINEERING_SCOPE_V1";
    public static final String INHERIT_SOURCE = "INHERIT_SOURCE";
    public static final String APPROVED_OVERRIDE = "APPROVED_OVERRIDE";

    private final RemoteMdmRouteEngineeringService engineering;
    private final MesProcessParameterStandardFreezeService parameters;
    private final MesSopFreezeService sops;

    /**
     * 当前返工原生产版本及节点下的已批准覆盖候选；历史无工程版本时只能继承。
     */
    public List<MdmRouteEngineeringDTO.ReworkOverrideCandidate> candidates(MesTaskEntity task) {
        requireReworkNode(task);
        if (task.getEngineeringReleaseId() == null || StrUtil.isBlank(task.getEngineeringContentHash())) {
            return List.of();
        }
        R<List<MdmRouteEngineeringDTO.ReworkOverrideCandidate>> response = engineering.reworkOverrideCandidates(
                task.getEngineeringReleaseId(), task.getEngineeringContentHash(), task.getReworkRouteNodeCode());
        require(response != null && response.isOk() && response.getData() != null,
                "MDM返工覆盖候选不可核验，请稍后重试，不能按空列表推断没有覆盖版本");
        List<MdmRouteEngineeringDTO.ReworkOverrideCandidate> rows = response.getData();
        require(rows.size() <= 100, "MDM返工覆盖候选超过100项，禁止截断");
        for (var row : rows) validateCandidate(task, row);
        return rows;
    }

    /**
     * 提案时取得并冻结明确工程口径，同时生成方案作用后的参数与SOP快照。
     */
    public FrozenScope freeze(MesTaskEntity task, MesReworkMaterialCommand.EngineeringOverride command) {
        require(command != null && Set.of(INHERIT_SOURCE, APPROVED_OVERRIDE)
                        .contains(Objects.toString(command.getMode(), "")),
                "首轮返工执行方案须明确继承原节点或选择已批准覆盖版");
        List<MdmProcessRouteWorkingDTO> routes = routes(task);
        if (INHERIT_SOURCE.equals(command.getMode())) {
            require(StrUtil.isBlank(command.getReleaseId()) && StrUtil.isBlank(command.getContentHash()),
                    "继承原节点不能夹带覆盖版本身份");
            parameters.validateReworkSnapshot(task, routes);
            sops.validateReworkSnapshot(task, routes);
            var body = JSONUtil.createObj()
                    .set("version", VERSION).set("mode", INHERIT_SOURCE)
                    .set("baseReleaseId", task.getEngineeringReleaseId() == null
                            ? null : task.getEngineeringReleaseId().toString())
                    .set("baseContentHash", task.getEngineeringContentHash())
                    .set("targetRouteNodeCode", task.getReworkRouteNodeCode())
                    .set("parameterFingerprint", task.getProcessParameterSnapshotFingerprint())
                    .set("routeSnapshotFingerprint", sha256(task.getProcessRouteSnapshot()));
            String snapshot = body.toString();
            return new FrozenScope(INHERIT_SOURCE, null, null, snapshot, sha256(snapshot),
                    task.getProcessRouteSnapshot(), task.getProcessParameterStandardNo(),
                    task.getProcessParameterStandardVersion(), task.getProcessParameterStandardSnapshot(),
                    task.getProcessParameterSnapshotFingerprint());
        }
        require(task.getEngineeringReleaseId() != null && validHash(task.getEngineeringContentHash()),
                "原生产工单没有精确工程版本对，不能选择返工覆盖版；可明确继承原节点");
        Long releaseId = exactId(command.getReleaseId());
        require(validHash(command.getContentHash()), "返工覆盖版本内容指纹无效");
        MdmRouteEngineeringDTO.ReworkOverridePackage pack = resolve(task, releaseId, command.getContentHash());
        return apply(task, pack);
    }

    /**
     * 审批时重新向MDM精确取同一版本，不允许批准已漂移的SOP/参数来源。
     */
    public FrozenScope revalidateProposal(MesTaskEntity task, String snapshot, String fingerprint) {
        var body = scope(snapshot, fingerprint);
        String mode = body.getStr("mode");
        if (INHERIT_SOURCE.equals(mode)) {
            MesReworkMaterialCommand.EngineeringOverride inherit =
                    new MesReworkMaterialCommand.EngineeringOverride();
            inherit.setMode(INHERIT_SOURCE);
            FrozenScope current = freeze(task, inherit);
            require(Objects.equals(current.snapshot(), snapshot),
                    "原节点SOP或参数快照已变化，旧返工方案失效，请重新提交");
            return current;
        }
        require(APPROVED_OVERRIDE.equals(mode), "返工工程口径模式无效");
        Long releaseId = exactId(body.getStr("releaseId"));
        String contentHash = body.getStr("contentHash");
        MdmRouteEngineeringDTO.ReworkOverridePackage pack = resolve(task, releaseId, contentHash);
        FrozenScope current = apply(task, pack);
        require(Objects.equals(current.snapshot(), snapshot),
                "返工覆盖版本或其正式SOP/参数来源已变化，旧方案失效，请重新提交");
        return current;
    }

    /**
     * 下达边界只核已批准本地冻结证据，不改选“今天版本”。
     */
    public void validateApproved(MesTaskEntity task) {
        var body = scope(task.getReworkEngineeringScopeSnapshot(),
                task.getReworkEngineeringScopeFingerprint());
        String mode = body.getStr("mode");
        require(Objects.equals(mode, task.getReworkEngineeringMode()),
                "返工工程口径与批准模式不一致");
        if (INHERIT_SOURCE.equals(mode)) {
            require(task.getReworkEngineeringOverrideId() == null
                            && task.getReworkEngineeringOverrideHash() == null,
                    "继承原节点不能保留覆盖版本身份");
        } else {
            require(APPROVED_OVERRIDE.equals(mode)
                            && Objects.equals(task.getReworkEngineeringOverrideId(), exactId(body.getStr("releaseId")))
                            && Objects.equals(task.getReworkEngineeringOverrideHash(), body.getStr("contentHash"))
                            && validHash(body.getStr("packageFingerprint"))
                            && Objects.equals(body.getStr("packageFingerprint"),
                            sha256(body.getStr("packageSnapshot"))),
                    "返工覆盖版本对或原封存包指纹不一致");
        }
        parameters.validateReworkSnapshot(task, routes(task));
        sops.validateReworkSnapshot(task, routes(task));
    }

    private FrozenScope apply(MesTaskEntity task, MdmRouteEngineeringDTO.ReworkOverridePackage pack) {
        List<MdmProcessRouteWorkingDTO> routes = routes(task);
        MesTaskEntity candidate = JSONUtil.toBean(JSONUtil.toJsonStr(task), MesTaskEntity.class);
        String routeSnapshot = task.getProcessRouteSnapshot();
        MesProcessParameterStandardFreezeService.FrozenBundle parameter =
                parameters.validateReworkSnapshot(candidate, routes);
        if (pack.getOverrideCategories().contains("PARAMETER")) {
            require(StrUtil.isNotBlank(pack.getParameterCatalogSnapshot()),
                    "返工覆盖声明参数但MDM未返回参数目录");
            parameter = parameters.freezeFromEngineeringSnapshot(candidate, routes,
                    pack.getParameterCatalogSnapshot());
        } else require(StrUtil.isBlank(pack.getParameterCatalogSnapshot()),
                "返工覆盖未声明参数却夹带参数目录");
        if (pack.getOverrideCategories().contains("SOP")) {
            require(StrUtil.isNotBlank(pack.getSopManifestSnapshot()),
                    "返工覆盖声明SOP但MDM未返回正式清单");
            sops.freezeFromEngineeringSnapshot(candidate, routes, pack.getSopManifestSnapshot());
            routeSnapshot = sops.mergeFrozenSnapshot(task.getProcessRouteSnapshot(), routes);
        } else {
            require(StrUtil.isBlank(pack.getSopManifestSnapshot()),
                    "返工覆盖未声明SOP却夹带正式清单");
            sops.validateReworkSnapshot(candidate, routes);
        }
        var body = JSONUtil.createObj()
                .set("version", VERSION).set("mode", APPROVED_OVERRIDE)
                .set("releaseId", pack.getReleaseId().toString())
                .set("contentHash", pack.getContentHash())
                .set("baseReleaseId", pack.getBaseReleaseId().toString())
                .set("baseContentHash", pack.getBaseContentHash())
                .set("targetRouteNodeCode", pack.getTargetRouteNodeCode())
                .set("overrideCategories", pack.getOverrideCategories())
                .set("packageFingerprint", sha256(pack.getBundleSnapshot()))
                .set("packageSnapshot", pack.getBundleSnapshot());
        String snapshot = body.toString();
        require(snapshot.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024,
                "返工工程覆盖证据超过2MiB，禁止截断");
        return new FrozenScope(APPROVED_OVERRIDE, pack.getReleaseId(), pack.getContentHash(),
                snapshot, sha256(snapshot), routeSnapshot, parameter.catalog().getStandardNo(),
                parameter.catalog().getVersionNo(), parameter.snapshot(), parameter.fingerprint());
    }

    private MdmRouteEngineeringDTO.ReworkOverridePackage resolve(MesTaskEntity task, Long releaseId,
                                                                 String contentHash) {
        R<MdmRouteEngineeringDTO.ReworkOverridePackage> response = engineering.resolveForReworkOverride(
                new MdmRouteEngineeringDTO.ReworkOverrideQuery()
                        .setReleaseId(releaseId).setContentHash(contentHash)
                        .setBaseReleaseId(task.getEngineeringReleaseId())
                        .setBaseContentHash(task.getEngineeringContentHash())
                        .setTargetRouteNodeCode(task.getReworkRouteNodeCode())
                        .setProductCode(task.getMatCode()));
        require(response != null && response.isOk() && response.getData() != null,
                "MDM未返回可冻结的返工工程覆盖包");
        MdmRouteEngineeringDTO.ReworkOverridePackage pack = response.getData();
        require(MdmRouteEngineeringDTO.REWORK_OVERRIDE_SCHEMA_VERSION.equals(pack.getSchemaVersion())
                        && Objects.equals(pack.getTenantId(), task.getTenantId().toString())
                        && Objects.equals(pack.getFactoryCode(), task.getFactoryCode())
                        && Objects.equals(pack.getReleaseId(), releaseId)
                        && Objects.equals(pack.getContentHash(), contentHash)
                        && Objects.equals(pack.getBaseReleaseId(), task.getEngineeringReleaseId())
                        && Objects.equals(pack.getBaseContentHash(), task.getEngineeringContentHash())
                        && Objects.equals(pack.getTargetRouteNodeCode(), task.getReworkRouteNodeCode())
                        && Objects.equals(pack.getProductCode(), task.getMatCode())
                        && pack.getOverrideCategories() != null && !pack.getOverrideCategories().isEmpty()
                        && pack.getOverrideCategories().size() <= 2
                        && Set.of("SOP", "PARAMETER").containsAll(pack.getOverrideCategories())
                        && validHash(pack.getContentHash())
                        && Objects.equals(pack.getContentHash(), sha256(pack.getBundleSnapshot())),
                "MDM返工覆盖包组织、原生产版本、节点、产品或原文指纹不一致");
        return pack;
    }

    private void validateCandidate(MesTaskEntity task,
                                   MdmRouteEngineeringDTO.ReworkOverrideCandidate row) {
        require(row != null && row.getReleaseId() != null && row.getReleaseId() > 0
                        && validHash(row.getContentHash())
                        && Objects.equals(row.getBaseReleaseId(), task.getEngineeringReleaseId())
                        && Objects.equals(row.getBaseContentHash(), task.getEngineeringContentHash())
                        && Objects.equals(row.getTargetRouteNodeCode(), task.getReworkRouteNodeCode())
                        && Objects.equals(row.getProductCode(), task.getMatCode())
                        && row.getOverrideCategories() != null && !row.getOverrideCategories().isEmpty()
                        && row.getOverrideCategories().size() <= 2
                        && Set.of("SOP", "PARAMETER").containsAll(row.getOverrideCategories()),
                "MDM返工覆盖候选身份或范围不一致");
    }

    private List<MdmProcessRouteWorkingDTO> routes(MesTaskEntity task) {
        requireReworkNode(task);
        List<MdmProcessRouteWorkingDTO> routes;
        try {
            routes = JSONUtil.toList(task.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class);
        } catch (Exception failure) {
            throw new CheckedException("返工单节点路线快照损坏");
        }
        require(routes.size() == 1 && Objects.equals(routes.get(0).getRouteNodeCode(),
                        task.getReworkRouteNodeCode()),
                "返工工程口径必须精确绑定唯一稳定节点");
        return routes;
    }

    private void requireReworkNode(MesTaskEntity task) {
        require(task != null && "REWORK".equals(task.getResourceType())
                        && task.getTenantId() != null && StrUtil.isNotBlank(task.getFactoryCode())
                        && task.getReworkRouteNodeCode() != null
                        && task.getReworkRouteNodeCode().matches("RN-[1-9][0-9]{0,18}"),
                "返工工程口径只适用于已锁定唯一稳定节点的正式返工工单");
    }

    private cn.hutool.json.JSONObject scope(String snapshot, String fingerprint) {
        require(StrUtil.isNotBlank(snapshot) && validHash(fingerprint)
                        && Objects.equals(fingerprint, sha256(snapshot)),
                "返工工程口径原文缺失或指纹不一致");
        var body = JSONUtil.parseObj(snapshot);
        require(VERSION.equals(body.getStr("version"))
                        && Set.of(INHERIT_SOURCE, APPROVED_OVERRIDE).contains(body.getStr("mode")),
                "返工工程口径协议或模式无效");
        return body;
    }

    private Long exactId(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"),
                "返工覆盖版本ID必须是无损正整数字符串");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException failure) {
            throw new CheckedException("返工覆盖版本ID超出长整型范围");
        }
    }

    private boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String sha256(String value) {
        require(value != null, "返工工程证据原文不能为空");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("返工工程证据指纹计算失败", failure);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 首轮方案冻结并准备应用的完整结果。
     */
    public record FrozenScope(String mode, Long releaseId, String contentHash,
                              String snapshot, String fingerprint, String resultRouteSnapshot,
                              String parameterStandardNo, Integer parameterStandardVersion,
                              String parameterSnapshot, String parameterFingerprint) {
    }
}
