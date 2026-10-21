package com.kitex.mom.mdm.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.agent.service.MdmAgentActualScopeAccess;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.engineering.MdmRouteEngineeringBundleBuilder;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmRouteEngineeringReleaseEntity;
import com.kitex.mom.mdm.entity.MdmRouteEngineeringCommandEntity;
import com.kitex.mom.mdm.mapper.MdmProcessRouteMapper;
import com.kitex.mom.mdm.mapper.MdmRouteEngineeringReleaseMapper;
import com.kitex.mom.mdm.mapper.MdmRouteEngineeringCommandMapper;
import com.kitex.mom.mdm.service.MdmRouteEngineeringReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 工程版本封存与生命周期，真实组织、父锁和永久回执在同一事务核验。 */
@Service
@RequiredArgsConstructor
public class MdmRouteEngineeringReleaseServiceImpl
        extends ServiceImpl<MdmRouteEngineeringReleaseMapper, MdmRouteEngineeringReleaseEntity>
        implements MdmRouteEngineeringReleaseService {
    private final MdmRouteEngineeringCommandMapper commands;
    private final MdmProcessRouteMapper routes;
    private final MdmRouteEngineeringBundleBuilder builder;
    private final MdmAgentActualScopeAccess access;
    private final ObjectMapper objectMapper;

    /** 当前授权路线的真实工程上下文。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public MdmRouteEngineeringDTO.Context context(Long routeId) {
        Scope scope = scope(false);
        lockRoute(routeId, scope);
        return builder.context(routeId);
    }

    /** 锁定真实父路线后完整采集，预检不持久化工程版本。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public MdmRouteEngineeringDTO.Preview preview(MdmRouteEngineeringDTO.PreviewQuery query) {
        require(query != null, "预检请求不能为空，请重新选择路线及BOM");
        Scope scope = scope(false);
        lockRoute(query.getRouteId(), scope);
        return previewFor(query, scope);
    }

    /** 同组织原号先串行；同人同参重放固定回执，其余执行合法状态迁移。 */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("(#command.action == 'SUBMIT' and @pms.hasPermission('mdm_routeEngineering_submit'))"
            + " or ((#command.action == 'APPROVE' or #command.action == 'REJECT') and @pms.hasPermission('mdm_routeEngineering_approve'))"
            + " or ((#command.action == 'RETIRE' or #command.action == 'WITHDRAW') and @pms.hasPermission('mdm_routeEngineering_withdraw'))")
    public MdmRouteEngineeringDTO.Receipt command(MdmRouteEngineeringDTO.Command command) {
        require(command != null, "工程命令不能为空");
        require(validRequest(command.getRequestId()), "永久请求号须为16至64位字母、数字、连字符或下划线");
        require(Set.of("SUBMIT", "APPROVE", "REJECT", "RETIRE", "WITHDRAW").contains(Objects.toString(command.getAction(), "")),
                "不支持的工程动作，请从正式工作台重新操作");
        require(command.getReason() != null && !command.getReason().isBlank() && command.getReason().length() <= 1000,
                "每次工程动作必须填写1至1000字实际依据");
        Scope scope = scope(true);
        String originalCommand = commandSnapshot(command);
        require(originalCommand.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024,
                "工程命令超过2MiB，请核对声明内容，不允许截断");
        String payloadHash = sha256(originalCommand);
        lockRequest(scope, command.getRequestId());
        MdmRouteEngineeringCommandEntity replay = commandRow(command.getRequestId(), scope);
        if (replay != null) {
            require(Objects.equals(replay.getActorUserId(), scope.actor())
                            && Objects.equals(replay.getPayloadHash(), payloadHash)
                            && Objects.equals(replay.getCommandSnapshot(), originalCommand),
                    "永久号已由其他用户或其他载荷使用；未知结果请由原用户恢复原回执，不能改参重投");
            return JSONUtil.toBean(replay.getReceiptSnapshot(), MdmRouteEngineeringDTO.Receipt.class);
        }
        MdmRouteEngineeringReleaseEntity release;
        if ("SUBMIT".equals(command.getAction())) {
            require(command.getReleaseId() == null && command.getExpectedHash() == null,
                    "提交新版本不能混入旧版本身份");
            release = submit(command, scope);
        } else {
            require(command.getRouteId() == null && command.getBomId() == null
                            && command.getExpectedGraphRevision() == null && command.getPreviewHash() == null
                            && (command.getDeclarations() == null || command.getDeclarations().isEmpty())
                            && command.getReleasePurpose() == null && command.getBaseReleaseId() == null
                            && command.getTargetRouteNodeCode() == null
                            && (command.getOverrideCategories() == null || command.getOverrideCategories().isEmpty()),
                    "生命周期动作仅可引用精确版本，不能替换工程配置");
            MdmRouteEngineeringReleaseEntity identified = releaseRow(command.getReleaseId(), scope, false);
            lockRoute(identified.getRouteId(), scope);
            release = releaseRow(command.getReleaseId(), scope, true);
            require(validHash(command.getExpectedHash())
                            && Objects.equals(command.getExpectedHash(), revisionHash(release)),
                    "工程版本状态已变化，请刷新详情后使用新永久号");
            transition(release, command, scope);
            require(baseMapper.updateById(release) == 1, "工程版本状态保存失败，请使用原永久号恢复回执");
        }
        MdmRouteEngineeringDTO.Receipt receipt = new MdmRouteEngineeringDTO.Receipt()
                .setRequestId(command.getRequestId()).setReleaseId(release.getId())
                .setVersionNo(release.getVersionNo()).setStatus(release.getStatus())
                .setContentHash(release.getContentHash()).setRevisionHash(revisionHash(release))
                .setMessage("工程动作已记录；" + MdmRouteEngineeringDTO.EXECUTION_NOTICE);
        MdmRouteEngineeringCommandEntity audit = new MdmRouteEngineeringCommandEntity();
        audit.setTenantId(scope.tenant());
        audit.setFactoryCode(scope.factory());
        audit.setRequestId(command.getRequestId());
        audit.setPayloadHash(payloadHash);
        audit.setCommandSnapshot(originalCommand);
        audit.setActorUserId(scope.actor());
        audit.setActorName(scope.actorName());
        audit.setAction(command.getAction());
        audit.setOutcome("SUCCESS");
        audit.setReleaseId(release.getId());
        audit.setReceiptSnapshot(JSONUtil.toJsonStr(receipt));
        require(commands.insert(audit) == 1, "工程永久回执保存失败，事务已回滚，请使用原号恢复");
        return receipt;
    }

    /**
     * 与实际执行共用请求锁及永久账。失败请求不能仅凭一次回滚清号，须先在锁内核对：
     * 已存在成功/封存回执则原样恢复；否则写入不可删除的SEALED墓碑，后来同号只能重放。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("(#command.action == 'SUBMIT' and @pms.hasPermission('mdm_routeEngineering_submit'))"
            + " or ((#command.action == 'APPROVE' or #command.action == 'REJECT') and @pms.hasPermission('mdm_routeEngineering_approve'))"
            + " or ((#command.action == 'RETIRE' or #command.action == 'WITHDRAW') and @pms.hasPermission('mdm_routeEngineering_withdraw'))")
    public MdmRouteEngineeringDTO.Receipt seal(MdmRouteEngineeringDTO.Command command) {
        require(command != null && validRequest(command.getRequestId()), "请提供已保存的完整原命令与有效永久号");
        require(Set.of("SUBMIT", "APPROVE", "REJECT", "RETIRE", "WITHDRAW").contains(Objects.toString(command.getAction(), "")),
                "永久封存须使用原工程动作，不能更改动作");
        Scope scope = scope(true);
        String original = commandSnapshot(command);
        require(original.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024, "原命令超过2MiB，禁止截断封存");
        String hash = sha256(original);
        lockRequest(scope, command.getRequestId());
        MdmRouteEngineeringCommandEntity existing = commandRow(command.getRequestId(), scope);
        if (existing != null) {
            require(Objects.equals(existing.getActorUserId(), scope.actor())
                            && Objects.equals(existing.getPayloadHash(), hash)
                            && Objects.equals(existing.getCommandSnapshot(), original),
                    "该原号已属于其他用户或载荷，不能封存或覆盖，请由原用户核对原账");
            return JSONUtil.toBean(existing.getReceiptSnapshot(), MdmRouteEngineeringDTO.Receipt.class);
        }
        MdmRouteEngineeringDTO.Receipt receipt = new MdmRouteEngineeringDTO.Receipt()
                .setRequestId(command.getRequestId()).setOutcome("SEALED").setStatus("SEALED")
                .setCommandSnapshot(original).setPayloadHash(hash)
                .setMessage("已在原号锁内确认无执行回执并永久封存；此号今后不能执行，可保留证据后重新预检申请新号");
        MdmRouteEngineeringCommandEntity tombstone = new MdmRouteEngineeringCommandEntity();
        tombstone.setTenantId(scope.tenant());
        tombstone.setFactoryCode(scope.factory());
        tombstone.setRequestId(command.getRequestId());
        tombstone.setPayloadHash(hash);
        tombstone.setCommandSnapshot(original);
        tombstone.setActorUserId(scope.actor());
        tombstone.setActorName(scope.actorName());
        tombstone.setAction(command.getAction());
        tombstone.setOutcome("SEALED");
        tombstone.setReceiptSnapshot(JSONUtil.toJsonStr(receipt));
        require(commands.insert(tombstone) == 1, "封存结果未知，请保留原号查询回执，禁止直接另发新号");
        return receipt;
    }

    /** 实际执行与永久封存必须使用同一锁键，防止两者并发观察为无回执。 */
    private void lockRequest(Scope scope, String requestId) {
        commands.lockRequest("MDM_ROUTE_ENGINEERING:" + scope.tenant() + "|" + scope.factory() + "|" + requestId);
    }

    /** 返回完整有界版本列表，超过容量明确拒绝。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public List<MdmRouteEngineeringReleaseEntity> releases(Long routeId) {
        Scope scope = scope(false);
        lockRoute(routeId, scope);
        List<MdmRouteEngineeringReleaseEntity> rows = baseMapper.selectList(releaseQuery(scope)
                .select(MdmRouteEngineeringReleaseEntity.class, field ->
                        !Set.of("bundleSnapshot", "applicabilitySnapshot").contains(field.getProperty()))
                .eq(MdmRouteEngineeringReleaseEntity::getRouteId, routeId)
                .orderByDesc(MdmRouteEngineeringReleaseEntity::getVersionNo).last("LIMIT 501"));
        require(rows.size() <= 500, "路线工程版本超过500项，请联系管理员提供完整分页入口，不能静默截断");
        return rows;
    }

    /** 精确版本正文和全部有界审计，不返回其他组织记录。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public Detail detail(Long id) {
        Scope scope = scope(false);
        MdmRouteEngineeringReleaseEntity identified = releaseRow(id, scope, false);
        lockRoute(identified.getRouteId(), scope);
        MdmRouteEngineeringReleaseEntity release = releaseRow(id, scope, true);
        List<MdmRouteEngineeringCommandEntity> history = commands.selectList(commandQuery(scope)
                .eq(MdmRouteEngineeringCommandEntity::getReleaseId, id)
                .orderByAsc(MdmRouteEngineeringCommandEntity::getId).last("LIMIT 11"));
        require(history.size() <= 10, "工程版本审计超过合法容量，请联系管理员核查，不允许截断证据");
        Detail result = new Detail();
        result.setRelease(release);
        result.setRevisionHash(revisionHash(release));
        result.setCommands(history);
        return result;
    }

    /** 原用户恢复原号，不能通过菜单读权领取其他用户回执。 */
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public MdmRouteEngineeringDTO.Receipt receipt(String requestId) {
        require(validRequest(requestId), "永久请求号格式无效，请使用原号");
        Scope scope = scope(false);
        MdmRouteEngineeringCommandEntity row = commandRow(requestId, scope);
        require(row != null && Objects.equals(row.getActorUserId(), scope.actor()),
                "未找到当前实名用户的原回执；结果仍可能未知，请保留原号联系管理员核对");
        return JSONUtil.toBean(row.getReceiptSnapshot(), MdmRouteEngineeringDTO.Receipt.class);
    }

    /**
     * 选择阶段只暴露当前组织、当前产品的已发布轻量候选。这里不静默过滤后来发生来源漂移的版本，
     * 因为保存与下达仍须按精确版本对调用resolveForProduction，届时会给出明确阻断原因。
     */
    @Override
    @Transactional(readOnly = true)
    public List<MdmRouteEngineeringDTO.ProductionCandidate> productionCandidates(String productCode) {
        Scope scope = scope(false);
        String normalizedProduct = normalizedProduct(productCode);
        List<MdmRouteEngineeringReleaseEntity> rows = baseMapper.selectList(releaseQuery(scope)
                .select(MdmRouteEngineeringReleaseEntity::getId,
                        MdmRouteEngineeringReleaseEntity::getVersionNo,
                        MdmRouteEngineeringReleaseEntity::getContentHash,
                        MdmRouteEngineeringReleaseEntity::getRouteId,
                        MdmRouteEngineeringReleaseEntity::getRouteCode,
                        MdmRouteEngineeringReleaseEntity::getProductCode,
                        MdmRouteEngineeringReleaseEntity::getBomId,
                        MdmRouteEngineeringReleaseEntity::getBomVersion,
                        MdmRouteEngineeringReleaseEntity::getGraphRevision)
                .eq(MdmRouteEngineeringReleaseEntity::getProductCode, normalizedProduct)
                .eq(MdmRouteEngineeringReleaseEntity::getStatus, "RELEASED")
                .and(q -> q.isNull(MdmRouteEngineeringReleaseEntity::getReleasePurpose)
                        .or().eq(MdmRouteEngineeringReleaseEntity::getReleasePurpose,
                                MdmRouteEngineeringDTO.PURPOSE_PRODUCTION))
                .orderByDesc(MdmRouteEngineeringReleaseEntity::getReviewedTime)
                .orderByDesc(MdmRouteEngineeringReleaseEntity::getId)
                .last("LIMIT 101"));
        require(rows.size() <= 100,
                "当前产品已发布工程版本超过100项，请先退役不再使用的版本，不能静默截断生产候选");
        return rows.stream().map(this::productionCandidate).toList();
    }

    /**
     * 生产消费以父路线锁、精确版本锁、原文自身指纹和当前来源重采四重核验为准。
     * 返回原封存正文而非本次重采正文，确保PLAN、MES持久化的是批准时的原始字节。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MdmRouteEngineeringDTO.ProductionPackage resolveForProduction(MdmRouteEngineeringDTO.ProductionQuery query) {
        require(query != null, "生产取包请求不能为空");
        require(query.getReleaseId() != null && query.getReleaseId() > 0,
                "请选择精确的已发布工程版本");
        require(validHash(query.getContentHash()),
                "工程版本内容指纹无效，请刷新候选后重新选择");
        String productCode = normalizedProduct(query.getProductCode());
        Scope scope = scope(false);

        MdmRouteEngineeringReleaseEntity identified = releaseRow(query.getReleaseId(), scope, false);
        lockRoute(identified.getRouteId(), scope);
        MdmRouteEngineeringReleaseEntity release = releaseRow(query.getReleaseId(), scope, true);
        require("RELEASED".equals(release.getStatus()),
                "工程版本已退役、撤回或尚未批准，禁止继续保存或下达生产");
        require(MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(releasePurpose(release)),
                "返工覆盖版本不能作为普通生产工程版本下达");
        require(Objects.equals(release.getProductCode(), productCode),
                "工程版本产品与计划或工单产品不一致，请重新选择");
        require(Objects.equals(release.getContentHash(), query.getContentHash())
                        && Objects.equals(release.getContentHash(), sha256(release.getBundleSnapshot())),
                "工程版本对或冻结原文指纹不一致，请停止下达并联系管理员核查");
        verifyFrozenIdentity(release, scope);

        MdmRouteEngineeringDTO.Preview current = builder.build(new MdmRouteEngineeringDTO.PreviewQuery()
                .setRouteId(release.getRouteId())
                .setBomId(release.getBomId())
                .setExpectedGraphRevision(release.getGraphRevision())
                .setDeclarations(JSONUtil.toList(release.getApplicabilitySnapshot(),
                        MdmRouteEngineeringDTO.Applicability.class)));
        require(current.isReady(),
                "工程版本当前来源已出现阻断项：" + String.join("；", current.getGaps()));
        require(Objects.equals(current.getGraphRevision(), release.getGraphRevision())
                        && Objects.equals(current.getContentHash(), release.getContentHash())
                        && Objects.equals(current.getBundleSnapshot(), release.getBundleSnapshot()),
                "工程版本批准后的路线、BOM、参数、SOP、投料、工装、模具或设置已变化，须重新发布工程版本");

        MdmRouteEngineeringDTO.ProductionCandidate candidate = productionCandidate(release);
        MdmRouteEngineeringDTO.ProductionPackage productionPackage = new MdmRouteEngineeringDTO.ProductionPackage();
        productionPackage.setSchemaVersion(MdmRouteEngineeringDTO.SCHEMA_VERSION);
        productionPackage.setTenantId(scope.tenant().toString());
        productionPackage.setFactoryCode(scope.factory());
        productionPackage.setBundleSnapshot(release.getBundleSnapshot());
        productionPackage.setReleaseId(candidate.getReleaseId());
        productionPackage.setVersionNo(candidate.getVersionNo());
        productionPackage.setContentHash(candidate.getContentHash());
        productionPackage.setRouteId(candidate.getRouteId());
        productionPackage.setRouteCode(candidate.getRouteCode());
        productionPackage.setProductCode(candidate.getProductCode());
        productionPackage.setBomId(candidate.getBomId());
        productionPackage.setBomVersion(candidate.getBomVersion());
        productionPackage.setGraphRevision(candidate.getGraphRevision());
        return productionPackage;
    }

    /** 已批准覆盖候选必须精确属于原生产版本与目标节点。 */
    @Override
    @Transactional(readOnly = true)
    public List<MdmRouteEngineeringDTO.ReworkOverrideCandidate> reworkOverrideCandidates(
            Long baseReleaseId, String baseContentHash, String targetRouteNodeCode) {
        require(baseReleaseId != null && baseReleaseId > 0 && validHash(baseContentHash),
                "返工覆盖候选缺少原生产工程版本对");
        require(validNode(targetRouteNodeCode), "返工覆盖候选缺少有效稳定节点");
        Scope scope = scope(false);
        MdmRouteEngineeringReleaseEntity base = releaseRow(baseReleaseId, scope, false);
        require(MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(releasePurpose(base))
                        && Objects.equals(base.getContentHash(), baseContentHash)
                        && Objects.equals(base.getContentHash(), sha256(base.getBundleSnapshot())),
                "原生产工程版本对不一致，不能选择返工覆盖");
        verifyFrozenIdentity(base, scope);
        List<MdmRouteEngineeringReleaseEntity> rows = baseMapper.selectList(releaseQuery(scope)
                .eq(MdmRouteEngineeringReleaseEntity::getReleasePurpose,
                        MdmRouteEngineeringDTO.PURPOSE_REWORK_OVERRIDE)
                .eq(MdmRouteEngineeringReleaseEntity::getBaseReleaseId, baseReleaseId)
                .eq(MdmRouteEngineeringReleaseEntity::getBaseContentHash, baseContentHash)
                .eq(MdmRouteEngineeringReleaseEntity::getTargetRouteNodeCode, targetRouteNodeCode)
                .eq(MdmRouteEngineeringReleaseEntity::getStatus, "RELEASED")
                .orderByDesc(MdmRouteEngineeringReleaseEntity::getReviewedTime)
                .orderByDesc(MdmRouteEngineeringReleaseEntity::getId).last("LIMIT 101"));
        require(rows.size() <= 100, "该原生产版本与节点的返工覆盖超过100项，请先退役旧版");
        return rows.stream().map(this::reworkCandidate).toList();
    }

    /** 覆盖消费沿用父路线锁、精确版本锁、原文指纹及当前正式来源重采。 */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MdmRouteEngineeringDTO.ReworkOverridePackage resolveForReworkOverride(
            MdmRouteEngineeringDTO.ReworkOverrideQuery query) {
        require(query != null && query.getReleaseId() != null && query.getReleaseId() > 0
                        && validHash(query.getContentHash()) && query.getBaseReleaseId() != null
                        && query.getBaseReleaseId() > 0 && validHash(query.getBaseContentHash())
                        && validNode(query.getTargetRouteNodeCode()),
                "返工覆盖取包须提供覆盖版本、原生产版本及稳定节点的完整版本对");
        String product = normalizedProduct(query.getProductCode());
        Scope scope = scope(false);
        MdmRouteEngineeringReleaseEntity identified = releaseRow(query.getReleaseId(), scope, false);
        lockRoute(identified.getRouteId(), scope);
        MdmRouteEngineeringReleaseEntity release = releaseRow(query.getReleaseId(), scope, true);
        require(MdmRouteEngineeringDTO.PURPOSE_REWORK_OVERRIDE.equals(releasePurpose(release))
                        && "RELEASED".equals(release.getStatus())
                        && Objects.equals(release.getContentHash(), query.getContentHash())
                        && Objects.equals(release.getContentHash(), sha256(release.getBundleSnapshot()))
                        && Objects.equals(release.getBaseReleaseId(), query.getBaseReleaseId())
                        && Objects.equals(release.getBaseContentHash(), query.getBaseContentHash())
                        && Objects.equals(release.getTargetRouteNodeCode(), query.getTargetRouteNodeCode())
                        && Objects.equals(release.getProductCode(), product),
                "返工覆盖版本已变化、不可用或与原生产版本/节点不一致");
        MdmRouteEngineeringReleaseEntity base = releaseRow(release.getBaseReleaseId(), scope, false);
        require(MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(releasePurpose(base))
                        && Objects.equals(base.getContentHash(), release.getBaseContentHash())
                        && Objects.equals(base.getContentHash(), sha256(base.getBundleSnapshot())),
                "返工覆盖绑定的原生产工程版本损坏或不一致");
        verifyFrozenIdentity(base, scope);
        MdmRouteEngineeringDTO.Preview current = builder.buildReworkOverride(overridePreviewQuery(release), base);
        require(current.isReady() && Objects.equals(current.getContentHash(), release.getContentHash())
                        && Objects.equals(current.getBundleSnapshot(), release.getBundleSnapshot()),
                "返工覆盖批准后的正式SOP或参数来源已变化，须重新发布覆盖版本");
        var bundle = JSONUtil.parseObj(release.getBundleSnapshot());
        MdmRouteEngineeringDTO.ReworkOverrideCandidate candidate = reworkCandidate(release);
        boolean parameterOverride = candidate.getOverrideCategories().contains("PARAMETER");
        boolean sopOverride = candidate.getOverrideCategories().contains("SOP");
        require(!parameterOverride || bundle.getJSONObject("parameterCatalog") != null,
                "返工覆盖原文声明参数但缺少参数目录");
        require(!sopOverride || bundle.getJSONObject("sopManifest") != null,
                "返工覆盖原文声明SOP但缺少正式清单");
        MdmRouteEngineeringDTO.ReworkOverridePackage result = new MdmRouteEngineeringDTO.ReworkOverridePackage();
        result.setSchemaVersion(MdmRouteEngineeringDTO.REWORK_OVERRIDE_SCHEMA_VERSION);
        result.setTenantId(scope.tenant().toString());
        result.setFactoryCode(scope.factory());
        result.setBundleSnapshot(release.getBundleSnapshot());
        result.setParameterCatalogSnapshot(parameterOverride
                ? bundle.getJSONObject("parameterCatalog").toString() : null);
        result.setSopManifestSnapshot(sopOverride
                ? bundle.getJSONObject("sopManifest").toString() : null);
        result.setReleaseId(candidate.getReleaseId());
        result.setVersionNo(candidate.getVersionNo());
        result.setContentHash(candidate.getContentHash());
        result.setBaseReleaseId(candidate.getBaseReleaseId());
        result.setBaseContentHash(candidate.getBaseContentHash());
        result.setRouteCode(candidate.getRouteCode());
        result.setProductCode(candidate.getProductCode());
        result.setTargetRouteNodeCode(candidate.getTargetRouteNodeCode());
        result.setOverrideCategories(candidate.getOverrideCategories());
        result.setReviewedBy(candidate.getReviewedBy());
        result.setReviewedTime(candidate.getReviewedTime());
        return result;
    }

    /** 原冻结包内的租户、工厂、协议及主数据身份必须与版本索引完全一致。 */
    private void verifyFrozenIdentity(MdmRouteEngineeringReleaseEntity release, Scope scope) {
        var bundle = JSONUtil.parseObj(release.getBundleSnapshot());
        var route = bundle.getJSONObject("route");
        var bom = bundle.getJSONObject("bom");
        require(Objects.equals(bundle.getStr("schemaVersion"), MdmRouteEngineeringDTO.SCHEMA_VERSION)
                        && Objects.equals(bundle.getStr("tenantId"), scope.tenant().toString())
                        && Objects.equals(bundle.getStr("factoryCode"), scope.factory())
                        && route != null && Objects.equals(route.getLong("id"), release.getRouteId())
                        && Objects.equals(route.getStr("routeCode"), release.getRouteCode())
                        && Objects.equals(route.getStr("productCode"), release.getProductCode())
                        && Objects.equals(route.getLong("graphRevision"), release.getGraphRevision())
                        && bom != null && Objects.equals(bom.getLong("id"), release.getBomId())
                        && Objects.equals(bom.getStr("version"), release.getBomVersion()),
                "工程版本索引与原冻结包身份不一致，请停止生产消费并联系管理员核查");
    }

    /** 统一生产候选映射，避免PLAN和MES分别解释发布表字段。 */
    private MdmRouteEngineeringDTO.ProductionCandidate productionCandidate(MdmRouteEngineeringReleaseEntity row) {
        return new MdmRouteEngineeringDTO.ProductionCandidate()
                .setReleaseId(row.getId())
                .setVersionNo(row.getVersionNo())
                .setContentHash(row.getContentHash())
                .setRouteId(row.getRouteId())
                .setRouteCode(row.getRouteCode())
                .setProductCode(row.getProductCode())
                .setBomId(row.getBomId())
                .setBomVersion(row.getBomVersion())
                .setGraphRevision(row.getGraphRevision());
    }

    /** 返工覆盖轻量候选只暴露其原生产版本、节点和允许覆盖类别。 */
    private MdmRouteEngineeringDTO.ReworkOverrideCandidate reworkCandidate(
            MdmRouteEngineeringReleaseEntity row) {
        return new MdmRouteEngineeringDTO.ReworkOverrideCandidate()
                .setReleaseId(row.getId()).setVersionNo(row.getVersionNo())
                .setContentHash(row.getContentHash()).setBaseReleaseId(row.getBaseReleaseId())
                .setBaseContentHash(row.getBaseContentHash()).setRouteCode(row.getRouteCode())
                .setProductCode(row.getProductCode()).setTargetRouteNodeCode(row.getTargetRouteNodeCode())
                .setOverrideCategories(normalizedOverrideCategories(
                        JSONUtil.toList(row.getOverrideCategoriesSnapshot(), String.class)))
                .setReviewedBy(row.getReviewedBy())
                .setReviewedTime(row.getReviewedTime() == null ? null : row.getReviewedTime().toString());
    }

    /** 页面预检按用途分流；返工覆盖永远从原生产版身份开始。 */
    private MdmRouteEngineeringDTO.Preview previewFor(MdmRouteEngineeringDTO.PreviewQuery query, Scope scope) {
        String purpose = normalizedPurpose(query.getReleasePurpose());
        query.setReleasePurpose(purpose);
        if (MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(purpose)) {
            require(query.getBaseReleaseId() == null && query.getTargetRouteNodeCode() == null
                            && (query.getOverrideCategories() == null || query.getOverrideCategories().isEmpty()),
                    "生产工程版本不能夹带返工覆盖范围");
            MdmRouteEngineeringDTO.Preview result = builder.build(query);
            result.setReleasePurpose(purpose);
            return result;
        }
        require(query.getBaseReleaseId() != null && query.getBaseReleaseId() > 0,
                "返工覆盖必须选择原生产工程版本");
        require(query.getDeclarations() == null || query.getDeclarations().isEmpty(),
                "返工覆盖沿用原生产版本适用性，不接受客户端改写六类声明");
        MdmRouteEngineeringReleaseEntity base = releaseRow(query.getBaseReleaseId(), scope, false);
        require(MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(releasePurpose(base))
                        && "RELEASED".equals(base.getStatus())
                        && Objects.equals(base.getRouteId(), query.getRouteId())
                        && Objects.equals(base.getBomId(), query.getBomId())
                        && Objects.equals(base.getGraphRevision(), query.getExpectedGraphRevision())
                        && Objects.equals(base.getContentHash(), sha256(base.getBundleSnapshot())),
                "返工覆盖的原生产工程版本未发布、已变化或身份不一致");
        verifyFrozenIdentity(base, scope);
        query.setOverrideCategories(normalizedOverrideCategories(query.getOverrideCategories()));
        return builder.buildReworkOverride(query, base);
    }

    /** 由持久化字段重建批准/消费预检请求，禁止从页面载荷补缺。 */
    private MdmRouteEngineeringDTO.PreviewQuery overridePreviewQuery(MdmRouteEngineeringReleaseEntity release) {
        return new MdmRouteEngineeringDTO.PreviewQuery()
                .setReleasePurpose(MdmRouteEngineeringDTO.PURPOSE_REWORK_OVERRIDE)
                .setRouteId(release.getRouteId()).setBomId(release.getBomId())
                .setExpectedGraphRevision(release.getGraphRevision())
                .setBaseReleaseId(release.getBaseReleaseId())
                .setTargetRouteNodeCode(release.getTargetRouteNodeCode())
                .setOverrideCategories(JSONUtil.toList(release.getOverrideCategoriesSnapshot(), String.class));
    }

    private MdmRouteEngineeringDTO.Preview previewForRelease(
            MdmRouteEngineeringReleaseEntity release, Scope scope) {
        if (MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(releasePurpose(release))) {
            return builder.build(new MdmRouteEngineeringDTO.PreviewQuery()
                    .setReleasePurpose(MdmRouteEngineeringDTO.PURPOSE_PRODUCTION)
                    .setRouteId(release.getRouteId()).setBomId(release.getBomId())
                    .setExpectedGraphRevision(release.getGraphRevision())
                    .setDeclarations(JSONUtil.toList(release.getApplicabilitySnapshot(),
                            MdmRouteEngineeringDTO.Applicability.class)));
        }
        MdmRouteEngineeringReleaseEntity base = releaseRow(release.getBaseReleaseId(), scope, false);
        require(MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(releasePurpose(base))
                        && "RELEASED".equals(base.getStatus())
                        && Objects.equals(base.getContentHash(), release.getBaseContentHash())
                        && Objects.equals(base.getContentHash(), sha256(base.getBundleSnapshot())),
                "返工覆盖的原生产工程版本已变化或不可批准");
        verifyFrozenIdentity(base, scope);
        return builder.buildReworkOverride(overridePreviewQuery(release), base);
    }

    private static String normalizedPurpose(String value) {
        String purpose = value == null || value.isBlank()
                ? MdmRouteEngineeringDTO.PURPOSE_PRODUCTION : value.trim();
        require(Set.of(MdmRouteEngineeringDTO.PURPOSE_PRODUCTION,
                MdmRouteEngineeringDTO.PURPOSE_REWORK_OVERRIDE).contains(purpose),
                "工程发布用途只能是生产工程版或返工覆盖版");
        return purpose;
    }

    private static String releasePurpose(MdmRouteEngineeringReleaseEntity release) {
        return release.getReleasePurpose() == null || release.getReleasePurpose().isBlank()
                ? MdmRouteEngineeringDTO.PURPOSE_PRODUCTION : release.getReleasePurpose();
    }

    private static List<String> normalizedOverrideCategories(List<String> values) {
        require(values != null && !values.isEmpty() && values.size() <= 2,
                "返工覆盖须明确SOP和/或PARAMETER");
        Set<String> normalized = new java.util.TreeSet<>();
        for (String value : values) {
            require(Set.of("SOP", "PARAMETER").contains(value) && normalized.add(value),
                    "返工覆盖类别只能是唯一的SOP、PARAMETER");
        }
        return List.copyOf(normalized);
    }

    private static boolean validNode(String value) {
        return value != null && value.matches("RN-[1-9][0-9]{0,18}");
    }

    /** 产品编码不允许空值、隐式空白或异常超长值参与版本绑定。 */
    private static String normalizedProduct(String value) {
        String normalized = value == null ? "" : value.trim();
        require(!normalized.isEmpty() && normalized.length() <= 100,
                "产品编码不能为空且不能超过100字符");
        return normalized;
    }

    /** 提交锁内重采与页面预检一致的原文，版本号仅在父路线锁内分配。 */
    private MdmRouteEngineeringReleaseEntity submit(MdmRouteEngineeringDTO.Command command, Scope scope) {
        MdmProcessRouteEntity route = lockRoute(command.getRouteId(), scope);
        require(command.getBomId() != null && command.getBomId() > 0
                        && command.getExpectedGraphRevision() != null && validHash(command.getPreviewHash()),
                "提交缺少正式BOM、图版本或预检指纹，请先完整预检");
        String purpose = normalizedPurpose(command.getReleasePurpose());
        MdmRouteEngineeringDTO.PreviewQuery previewQuery = new MdmRouteEngineeringDTO.PreviewQuery()
                .setReleasePurpose(purpose).setRouteId(route.getId()).setBomId(command.getBomId())
                .setExpectedGraphRevision(command.getExpectedGraphRevision()).setDeclarations(command.getDeclarations())
                .setBaseReleaseId(command.getBaseReleaseId())
                .setTargetRouteNodeCode(command.getTargetRouteNodeCode())
                .setOverrideCategories(command.getOverrideCategories());
        MdmRouteEngineeringDTO.Preview preview = previewFor(previewQuery, scope);
        require(preview.isReady() && Objects.equals(preview.getGraphRevision(), command.getExpectedGraphRevision())
                        && Objects.equals(preview.getContentHash(), command.getPreviewHash())
                        && Objects.equals(preview.getContentHash(), sha256(preview.getBundleSnapshot())),
                "工程来源或声明已变化或仍有阻断项，请刷新并重新预检后提交");
        List<MdmRouteEngineeringReleaseEntity> latest = baseMapper.selectList(releaseQuery(scope)
                .eq(MdmRouteEngineeringReleaseEntity::getRouteId, route.getId())
                .orderByDesc(MdmRouteEngineeringReleaseEntity::getVersionNo).last("LIMIT 1"));
        int number = latest.isEmpty() ? 1 : Math.addExact(latest.get(0).getVersionNo(), 1);
        var body = JSONUtil.parseObj(preview.getBundleSnapshot());
        Long frozenBomId = MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(purpose)
                ? body.getJSONObject("bom").getLong("id") : Long.valueOf(body.getStr("bomId"));
        String frozenBomVersion = MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(purpose)
                ? body.getJSONObject("bom").getStr("version") : body.getStr("bomVersion");
        require(Objects.equals(frozenBomId, command.getBomId()), "工程包BOM身份不一致，请重新预检");
        MdmRouteEngineeringReleaseEntity release = new MdmRouteEngineeringReleaseEntity();
        release.setTenantId(scope.tenant());
        release.setFactoryCode(scope.factory());
        release.setReleasePurpose(purpose);
        if (MdmRouteEngineeringDTO.PURPOSE_REWORK_OVERRIDE.equals(purpose)) {
            MdmRouteEngineeringReleaseEntity base = releaseRow(command.getBaseReleaseId(), scope, false);
            release.setBaseReleaseId(base.getId());
            release.setBaseContentHash(base.getContentHash());
            release.setTargetRouteNodeCode(command.getTargetRouteNodeCode());
            release.setOverrideCategoriesSnapshot(JSONUtil.toJsonStr(normalizedOverrideCategories(command.getOverrideCategories())));
        }
        release.setRouteId(route.getId());
        release.setRouteCode(route.getRouteCode());
        release.setProductCode(route.getProductCode());
        release.setBomId(command.getBomId());
        release.setBomVersion(frozenBomVersion);
        release.setGraphRevision(preview.getGraphRevision());
        release.setVersionNo(number);
        release.setStatus("PENDING");
        release.setBundleSnapshot(preview.getBundleSnapshot());
        release.setContentHash(preview.getContentHash());
        if (MdmRouteEngineeringDTO.PURPOSE_PRODUCTION.equals(purpose)) {
            var frozenDeclarations = body.getJSONArray("declarations");
            require(frozenDeclarations != null, "工程包缺少规范化适用声明，请重新预检");
            release.setApplicabilitySnapshot(frozenDeclarations.toString());
        } else {
            MdmRouteEngineeringReleaseEntity base = releaseRow(command.getBaseReleaseId(), scope, false);
            release.setApplicabilitySnapshot(base.getApplicabilitySnapshot());
        }
        release.setSubmitRequestId(command.getRequestId());
        release.setLastRequestId(command.getRequestId());
        release.setSubmittedUserId(scope.actor());
        release.setSubmittedBy(scope.actorName());
        release.setSubmittedTime(LocalDateTime.now());
        release.setLastReason(command.getReason());
        release.setLifecycleRevision(0L);
        require(baseMapper.insert(release) == 1, "工程版本封存失败，请保留原号恢复");
        return release;
    }

    /** 仅修改合法生命周期字段，不触碰封存正文或原来源指纹。 */
    private void transition(MdmRouteEngineeringReleaseEntity release, MdmRouteEngineeringDTO.Command command, Scope scope) {
        String action = command.getAction();
        if ("APPROVE".equals(action) || "REJECT".equals(action)) {
            require("PENDING".equals(release.getStatus()), "仅待审核版本可批准或退回，请刷新状态");
            require(!Objects.equals(release.getSubmittedUserId(), scope.actor()), "工程审核须由提交人之外的实名用户执行");
            if ("APPROVE".equals(action)) {
                require(Objects.equals(release.getContentHash(), sha256(release.getBundleSnapshot())),
                        "封存原文指纹损坏，请联系管理员核查");
                var preview = previewForRelease(release, scope);
                require(preview.isReady() && Objects.equals(preview.getGraphRevision(), release.getGraphRevision())
                                && Objects.equals(preview.getContentHash(), release.getContentHash())
                                && Objects.equals(preview.getContentHash(), sha256(preview.getBundleSnapshot())),
                        "封存后的工程来源已变化或有阻断项，须退回并重新提交，不能批准过期内容");
            }
            release.setStatus("APPROVE".equals(action) ? "RELEASED" : "REJECTED");
            release.setReviewedUserId(scope.actor());
            release.setReviewedBy(scope.actorName());
            release.setReviewedTime(LocalDateTime.now());
        } else {
            require("RELEASED".equals(release.getStatus())
                            || "WITHDRAW".equals(action) && "RETIRED".equals(release.getStatus()),
                    "退役仅限已发布版，安全撤回仅限已发布或已退役版；终态不能复活");
            release.setStatus("RETIRE".equals(action) ? "RETIRED" : "WITHDRAWN");
        }
        release.setLastReason(command.getReason());
        release.setLastRequestId(command.getRequestId());
        release.setLifecycleRevision(Math.addExact(release.getLifecycleRevision(), 1L));
    }

    /** 校验真实用户、租户与工厂权限；不接受跳过组织过滤的上下文。 */
    private Scope scope(boolean write) {
        String factory = FactoryContextHolder.getFactoryCode();
        access.require(factory, write ? "write" : "read");
        var actor = SecurityUtils.getUser();
        String name = actor.getUsername();
        require(name != null && !name.isBlank() && name.length() <= 100,
                "实名用户名缺失或超过100字符，请联系管理员修复账户");
        return new Scope(TenantContextHolder.getTenantId(), factory, actor.getId(), name);
    }

    /** 父路线锁先于版本锁，按原主键与实际组织定位，不以当前编码猜测历史归属。 */
    private MdmProcessRouteEntity lockRoute(Long id, Scope scope) {
        require(id != null && id > 0, "请选择真实工艺路线");
        List<MdmProcessRouteEntity> found = routes.selectList(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                .eq(MdmProcessRouteEntity::getTenantId, scope.tenant()).eq(MdmProcessRouteEntity::getFactoryCode, scope.factory())
                .eq(MdmProcessRouteEntity::getId, id).last("FOR UPDATE"));
        require(found.size() == 1, "当前组织未找到真实路线，请刷新后重试");
        MdmProcessRouteEntity route = found.get(0);
        // 新包预检/批准由builder核当前编码唯一；历史读取和安全退出不能被后来主数据缺口阻断。
        return route;
    }

    /** 精确读取版本，调用方需要修改时已先取得父路线锁。 */
    private MdmRouteEngineeringReleaseEntity releaseRow(Long id, Scope scope, boolean lock) {
        require(id != null && id > 0, "请选择精确工程版本");
        var query = releaseQuery(scope).eq(MdmRouteEngineeringReleaseEntity::getId, id);
        if (lock) {
            query.last("FOR UPDATE");
        }
        MdmRouteEngineeringReleaseEntity row = baseMapper.selectOne(query);
        require(row != null, "当前组织未找到工程版本，请刷新后重试");
        return row;
    }

    /** 显式限制工程版本租户与工厂。 */
    private LambdaQueryWrapper<MdmRouteEngineeringReleaseEntity> releaseQuery(Scope scope) {
        return new LambdaQueryWrapper<MdmRouteEngineeringReleaseEntity>()
                .eq(MdmRouteEngineeringReleaseEntity::getTenantId, scope.tenant())
                .eq(MdmRouteEngineeringReleaseEntity::getFactoryCode, scope.factory());
    }

    /** 显式限制永久命令租户与工厂。 */
    private LambdaQueryWrapper<MdmRouteEngineeringCommandEntity> commandQuery(Scope scope) {
        return new LambdaQueryWrapper<MdmRouteEngineeringCommandEntity>()
                .eq(MdmRouteEngineeringCommandEntity::getTenantId, scope.tenant())
                .eq(MdmRouteEngineeringCommandEntity::getFactoryCode, scope.factory());
    }

    /** 事务请求锁后重读永久账，不依据缓存推断未执行。 */
    private MdmRouteEngineeringCommandEntity commandRow(String requestId, Scope scope) {
        return commands.selectOne(commandQuery(scope).eq(MdmRouteEngineeringCommandEntity::getRequestId, requestId));
    }

    /** ID、状态、原内容指纹及生命周期版本采用明确分隔形成修订指纹。 */
    public static String revisionHash(MdmRouteEngineeringReleaseEntity release) {
        return sha256(release.getId() + "|" + release.getStatus() + "|" + release.getContentHash()
                + "|" + release.getLifecycleRevision() + "|" + releasePurpose(release)
                + "|" + Objects.toString(release.getBaseReleaseId(), "")
                + "|" + Objects.toString(release.getBaseContentHash(), "")
                + "|" + Objects.toString(release.getTargetRouteNodeCode(), "")
                + "|" + Objects.toString(release.getOverrideCategoriesSnapshot(), ""));
    }

    /** 执行与永久封存共用应用序列化合同，长ID不成为浏览器不安全整数。 */
    private String commandSnapshot(MdmRouteEngineeringDTO.Command command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException failure) {
            throw new CheckedException("工程命令无法完整序列化，请核对原载荷");
        }
    }

    /** 原始UTF-8字节SHA256，不通过JSON重排替代原正文。 */
    private static String sha256(String raw) {
        require(raw != null, "工程原文缺失，请联系管理员核查");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("工程原文字节指纹计算失败", failure);
        }
    }

    /** 永久号采用唯一严格格式，不对原号做隐式改写。 */
    private static boolean validRequest(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{16,64}");
    }

    /** 正式指纹须为64位小写十六进制。 */
    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /** 业务检查失败阻断整个事务并提供下一步建议。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    /** 经真实身份与组织授权核验的当前操作范围。 */
    private record Scope(Long tenant, String factory, Long actor, String actorName) { }
}
