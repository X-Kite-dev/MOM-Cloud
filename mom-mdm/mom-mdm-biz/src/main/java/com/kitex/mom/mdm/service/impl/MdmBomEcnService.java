package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.mdm.dto.MdmBomEcnCommand;
import com.kitex.mom.mdm.dto.MdmBomImpact;
import com.kitex.mom.mdm.dto.MdmBomImpactPages;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.feign.RemoteMesBomImpactService;
import com.kitex.mom.mdm.feign.RemotePlanBomImpactService;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmBomService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.mdm.service.impl.MdmBomGovernance.require;

@Service
@RequiredArgsConstructor
public class MdmBomEcnService {
    private static final String REVIEW_POLICY = "BOM_ECN_REVIEW_V1";
    private static final List<String> REVIEW_ROLES = List.of("PROCESS", "QUALITY", "PRODUCTION_PLAN");
    private static final Map<String, String> ROLE_PERMISSIONS = Map.of("PROCESS", "mdm_bomEcn_signProcess",
            "QUALITY", "mdm_bomEcn_signQuality", "PRODUCTION_PLAN", "mdm_bomEcn_signPlan");
    private final MdmBomMapper bomMapper;
    private final MdmBomChildMapper childMapper;
    private final MdmMaterialMapper materialMapper;
    private final MdmBomEcnMapper mapper;
    private final MdmBomEcnBindingMapper bindingMapper;
    private final MdmBomEcnOperationMapper operationMapper;
    private final MdmBomGovernance governance;
    private final MdmBomService bomService;
    private final RemotePlanBomImpactService planImpact;
    private final RemoteMesBomImpactService mesImpact;
    private final org.mybatis.spring.SqlSessionTemplate sqlSession;

    @Value("${mom.bom-ecn.max-evidence-bytes:33554432}")
    private long maxEvidenceBytes;
    @Value("${mom.bom-ecn.scan-timeout-seconds:120}")
    private long scanTimeoutSeconds;
    @Value("${mom.bom-ecn.review-hours:72}")
    private int reviewHours;

    public Page<MdmBomEcnEntity> page(String factory, long current, long size) {
        access(factory, "read", false);
        return mapper.selectPage(new Page<>(Math.max(1, current), Math.min(100, Math.max(1, size))),
                new LambdaQueryWrapper<MdmBomEcnEntity>().select(MdmBomEcnEntity.class, f -> !"snapshot_json".equals(f.getColumn()))
                        .eq(MdmBomEcnEntity::getFactoryCode, factory).orderByDesc(MdmBomEcnEntity::getId));
    }

    public MdmBomEcnEntity detail(Long id) {
        MdmBomEcnEntity row = mapper.selectById(id);
        require(row != null, "工程变更不存在");
        access(row.getFactoryCode(), "read", false);
        return row;
    }

    public List<MdmBomEcnOperationEntity> operations(Long id) {
        detail(id);
        return operationMapper.selectList(new LambdaQueryWrapper<MdmBomEcnOperationEntity>()
                .eq(MdmBomEcnOperationEntity::getEcnId, id).orderByAsc(MdmBomEcnOperationEntity::getId));
    }

    public Snapshot preview(Long targetId) {
        MdmBomEntity target = bomMapper.selectById(targetId);
        require(target != null, "请选择目标BOM草案");
        access(target.getFactoryCode(), "read", false);
        return snapshot(targetId);
    }

    @Transactional(rollbackFor = Exception.class)
    public MdmBomEcnEntity submit(MdmBomEcnCommand c) {
        validate(c);
        require(c.getTargetBomId() != null && c.getEcnId() == null && c.getRoleCode() == null && "KEEP_EXISTING".equals(c.getDisposition()),
                "须指定新版BOM并确认保留既有计划/在制旧版；不支持就地替换在制BOM");
        MdmBomEntity target = bomMapper.selectById(c.getTargetBomId());
        require(target != null, "目标BOM不存在");
        access(target.getFactoryCode(), "write", true);
        MdmBomEcnEntity replay = replay(c, "SUBMIT");
        if (replay != null) return replay;
        target = governance.editable(c.getTargetBomId());
        require(mapper.selectCount(new LambdaQueryWrapper<MdmBomEcnEntity>()
                .eq(MdmBomEcnEntity::getFactoryCode, target.getFactoryCode()).eq(MdmBomEcnEntity::getProductCode, target.getProductCode())
                .eq(MdmBomEcnEntity::getStatus, "PENDING")) == 0, "该产品已有待审工程变更，请先处理");
        Snapshot snapshot = snapshot(target.getId());
        require(Objects.equals(snapshot.getFingerprint(), c.getExpectedFingerprint()), "预览后版本或引用事实已变化，请重新分析并确认");
        LocalDateTime submitted = LocalDateTime.now().withNano(0);
        MdmBomEcnEntity row = new MdmBomEcnEntity().setEcnNo("ECN-" + UUID.randomUUID()).setProductCode(target.getProductCode())
                .setSourceBomId(snapshot.getSourceBomId()).setTargetBomId(target.getId()).setStatus("PENDING")
                .setReason(c.getReason()).setDisposition(c.getDisposition()).setSnapshotJson(snapshotJson(snapshot))
                .setFingerprint(snapshot.getFingerprint()).setApplicantId(governance.actor()).setSubmittedTime(submitted)
                .setReviewPolicy(REVIEW_POLICY).setExpiresTime(submitted.plusHours(snapshot.getReviewHours()))
                .setFactoryCode(target.getFactoryCode());
        require(mapper.insert(row) == 1, "工程变更申请保存失败");
        for (Long id : snapshot.getFrozenBomIds())
            require(bindingMapper.insert(new MdmBomEcnBindingEntity()
                    .setEcnId(row.getId()).setBomId(id).setFactoryCode(row.getFactoryCode())) == 1, "工程版本引用冻结失败");
        audit(row, c, "SUBMIT");
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public MdmBomEcnEntity act(MdmBomEcnCommand c, String action) {
        validate(c);
        require(c.getEcnId() != null && c.getTargetBomId() == null && c.getDisposition() == null && c.getRoleCode() == null,
                "审批操作只能指定工程变更及核实依据，不接受代签岗位");
        require(Set.of("PUBLISH", "REJECT", "WITHDRAW").contains(action), "工程变更操作不支持");
        MdmBomEcnEntity before = detail(c.getEcnId());
        access(before.getFactoryCode(), "WITHDRAW".equals(action) ? "write" : "admin", true);
        MdmBomEcnEntity replay = replay(c, action);
        if (replay != null) return replay;
        MdmBomEcnEntity row = mapper.selectOne(new LambdaQueryWrapper<MdmBomEcnEntity>()
                .eq(MdmBomEcnEntity::getId, c.getEcnId()).last("FOR UPDATE"));
        require(row != null && "PENDING".equals(row.getStatus()), "只有待审工程变更可审批或撤回");
        require(Objects.equals(row.getFingerprint(), c.getExpectedFingerprint()), "待审快照已变化，请刷新");
        Long actor = governance.actor();
        if ("WITHDRAW".equals(action)) {
            require(Objects.equals(actor, row.getApplicantId()), "仅申请人可撤回待审工程变更");
            row.setStatus("WITHDRAWN");
        } else {
            require(!Objects.equals(actor, row.getApplicantId()), "工程变更申请与复核须分岗");
            row.setReviewerId(actor).setReviewedTime(LocalDateTime.now()).setReviewReason(c.getReason());
            if ("REJECT".equals(action)) row.setStatus("REJECTED");
            else {
                freshReview(row);
                require(allSigned(row), "须工艺、质量、生产计划三个不同岗位人员完成会签后才能发布");
                require(Integer.valueOf(2).equals(JSONUtil.parseObj(row.getSnapshotJson()).getInt("evidenceVersion")),
                        "工程影响证据契约已升级，旧待审申请须撤回/驳回后重新分析，历史已发布记录不改写");
                Snapshot live = snapshot(row.getTargetBomId());
                require(Objects.equals(live.getFingerprint(), row.getFingerprint()),
                        "申请后BOM、引用或在制状态已变化；请驳回/撤回后重新分析申请，不沿用陈旧影响结论");
                freshReview(row);
                MdmBomEntity target = bomMapper.selectById(row.getTargetBomId());
                LocalDate today = LocalDate.now();
                require((target.getStartDate() == null || !target.getStartDate().isAfter(today))
                                && (target.getEndDate() == null || !target.getEndDate().isBefore(today)),
                        "当前仅支持批准即时生效，目标BOM今日须在有效期内；未来日期应到期复核发布");
                bomMapper.update(null, new LambdaUpdateWrapper<MdmBomEntity>()
                        .eq(MdmBomEntity::getFactoryCode, row.getFactoryCode()).eq(MdmBomEntity::getProductCode, row.getProductCode())
                        .set(MdmBomEntity::getIsDefault, false));
                require(bomMapper.update(null, new LambdaUpdateWrapper<MdmBomEntity>().eq(MdmBomEntity::getId, target.getId())
                        .set(MdmBomEntity::getEnabled, true).set(MdmBomEntity::getIsDefault, true)
                        .set(MdmBomEntity::getReleaseState, "RELEASED")) == 1, "BOM新版发布失败");
                row.setStatus("PUBLISHED");
            }
        }
        require(mapper.updateById(row) == 1, "工程变更状态保存失败");
        audit(row, c, action);
        return row;
    }

    public Map<String, Object> review(Long id) {
        MdmBomEcnEntity row = detail(id);
        List<MdmBomEcnOperationEntity> history = operations(id);
        boolean policyValid = REVIEW_POLICY.equals(row.getReviewPolicy());
        return Map.of("policy", REVIEW_POLICY, "requiredRoles", REVIEW_ROLES, "operations", history,
                "legacy", !policyValid, "complete", policyValid && allSigned(row),
                "expired", row.getExpiresTime() == null || !LocalDateTime.now().isBefore(row.getExpiresTime()));
    }

    @Transactional(rollbackFor = Exception.class)
    public MdmBomEcnEntity reviewAction(MdmBomEcnCommand c, String action) {
        validate(c);
        require(Set.of("SIGN", "VETO").contains(action) && c.getEcnId() != null && c.getTargetBomId() == null
                && c.getDisposition() == null && c.getRoleCode() != null && ROLE_PERMISSIONS.containsKey(c.getRoleCode()), "请选择有效工程会签岗位和单据");
        require(new PermissionService().hasPermission(ROLE_PERMISSIONS.get(c.getRoleCode())), "当前用户没有该工程会签岗位权限");
        MdmBomEcnEntity before = detail(c.getEcnId());
        access(before.getFactoryCode(), "write", true);
        MdmBomEcnEntity replay = replay(c, action);
        if (replay != null) return replay;
        MdmBomEcnEntity row = mapper.selectOne(new LambdaQueryWrapper<MdmBomEcnEntity>()
                .eq(MdmBomEcnEntity::getId, c.getEcnId()).last("FOR UPDATE"));
        require(row != null && "PENDING".equals(row.getStatus()), "只有待审工程变更可以会签或否决");
        require(Objects.equals(row.getFingerprint(), c.getExpectedFingerprint()), "请先读取并核对本单冻结证据");
        require(REVIEW_POLICY.equals(row.getReviewPolicy()), "旧待审申请未冻结多岗位策略，须撤回或驳回后重新申请");
        require(!Objects.equals(row.getApplicantId(), governance.actor()), "申请人不能会签或代表会签岗位否决自己的申请");
        if ("SIGN".equals(action)) {
            freshReview(row);
            List<MdmBomEcnOperationEntity> signed = signatures(row);
            require(signed.stream().noneMatch(o -> Objects.equals(o.getRoleCode(), c.getRoleCode())), "该岗位已签署，不能覆盖或重复签署");
            require(signed.stream().noneMatch(o -> Objects.equals(o.getOperatorId(), governance.actor())), "同一个人不能兼签多个工程岗位");
            require(Objects.equals(snapshot(row.getTargetBomId()).getFingerprint(), row.getFingerprint()),
                    "工程版本或业务引用事实已变化，请撤回/驳回后重提，不能继续沿用原会签");
            freshReview(row);
        } else {
            // A signed reviewer may still veto; stale/expired evidence must remain closable.
            row.setStatus("REJECTED").setReviewerId(governance.actor()).setReviewedTime(LocalDateTime.now())
                    .setReviewReason(c.getReason());
            require(mapper.updateById(row) == 1, "工程变更否决保存失败");
        }
        audit(row, c, action);
        return row;
    }

    private void freshReview(MdmBomEcnEntity row) {
        require(REVIEW_POLICY.equals(row.getReviewPolicy()) && row.getExpiresTime() != null,
                "旧待审工程申请须撤回/驳回后按多岗位策略重新申请，历史已发布证据不补造");
        require(LocalDateTime.now().isBefore(row.getExpiresTime()), "工程会签已过期，请撤回/驳回后重新分析申请");
    }

    private List<MdmBomEcnOperationEntity> signatures(MdmBomEcnEntity row) {
        return operationMapper.selectList(new LambdaQueryWrapper<MdmBomEcnOperationEntity>()
                .eq(MdmBomEcnOperationEntity::getEcnId, row.getId()).eq(MdmBomEcnOperationEntity::getFactoryCode, row.getFactoryCode())
                .eq(MdmBomEcnOperationEntity::getAction, "SIGN").orderByAsc(MdmBomEcnOperationEntity::getId));
    }

    private boolean allSigned(MdmBomEcnEntity row) {
        List<MdmBomEcnOperationEntity> signed = signatures(row);
        return REVIEW_POLICY.equals(row.getReviewPolicy()) && signed.size() == REVIEW_ROLES.size()
                && signed.stream().map(MdmBomEcnOperationEntity::getRoleCode).collect(java.util.stream.Collectors.toSet()).equals(new HashSet<>(REVIEW_ROLES))
                && signed.stream().map(MdmBomEcnOperationEntity::getOperatorId).distinct().count() == REVIEW_ROLES.size()
                && signed.stream().allMatch(o -> !Objects.equals(o.getOperatorId(), row.getApplicantId())
                && REVIEW_POLICY.equals(o.getReviewPolicy()) && Objects.equals(o.getSourceFingerprint(), row.getFingerprint())
                && o.getOperationTime() != null && row.getExpiresTime() != null && o.getOperationTime().isBefore(row.getExpiresTime()));
    }

    private void access(String factory, String permission, boolean lock) {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode())
                && Objects.equals(factory, FactoryContextHolder.getFactoryCode()), "工程变更须选择实际工厂，不得跳过租户/工厂隔离");
        governance.access(factory, permission, lock);
        if (lock) sqlSession.clearCache();
    }

    private Snapshot snapshot(Long targetId) {
        MdmBomEntity target = bomMapper.selectById(targetId);
        require(target != null && !Boolean.TRUE.equals(target.getEnabled()) && !Boolean.TRUE.equals(target.getIsDefault())
                && !"RELEASED".equals(target.getReleaseState()), "目标必须为尚未启用且未发布的新版本草案");
        String factory = target.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && StrUtil.isNotBlank(target.getProductCode()) && StrUtil.isNotBlank(target.getVersion()), "BOM产品、版本和工厂不能为空");
        List<MdmBomEntity> catalog = bomMapper.selectList(new LambdaQueryWrapper<MdmBomEntity>().eq(MdmBomEntity::getFactoryCode, factory));
        require(catalog.stream().filter(b -> Objects.equals(b.getProductCode(), target.getProductCode())
                && Objects.equals(b.getVersion(), target.getVersion())).count() == 1, "产品BOM版本重复，先治理主数据");
        List<MdmBomEntity> defaults = catalog.stream().filter(b -> Objects.equals(b.getProductCode(), target.getProductCode())
                && Boolean.TRUE.equals(b.getIsDefault())).toList();
        require(defaults.size() <= 1, "旧默认BOM不唯一，不能猜测变更基线");
        MdmBomEntity source = defaults.isEmpty() ? null : defaults.get(0);
        if (source != null)
            require(Boolean.TRUE.equals(source.getEnabled()) && !"DRAFT".equals(source.getReleaseState()), "旧默认BOM不是有效发布基线");
        List<MdmBomChildEntity> children = childMapper.selectList(new LambdaQueryWrapper<MdmBomChildEntity>()
                .in(MdmBomChildEntity::getBomId, catalog.stream().map(MdmBomEntity::getId).toList()).orderByAsc(MdmBomChildEntity::getId));
        Map<Long, MdmBomEntity> byId = new HashMap<>();
        catalog.forEach(b -> byId.put(b.getId(), b));
        Map<Long, List<MdmBomChildEntity>> byBom = new HashMap<>();
        children.forEach(c -> byBom.computeIfAbsent(c.getBomId(), ignored -> new ArrayList<>()).add(c));
        SortedSet<Long> frozen = new TreeSet<>();
        graph(targetId, targetId, byId, byBom, new HashSet<>(), frozen, true);
        Set<Long> newGraph = new HashSet<>(frozen);
        if (source != null) graph(source.getId(), targetId, byId, byBom, new HashSet<>(), frozen, false);
        Set<String> materialCodes = new HashSet<>();
        // 被新版移除的旧物料允许已停用；其历史事实仍冻结，不阻止纠正旧版的工程变更。
        for (Long id : newGraph) {
            bomService.validateEngineeringSubstitutes(byId.get(id), byBom.getOrDefault(id, List.of()));
            materialCodes.add(byId.get(id).getProductCode());
            byBom.getOrDefault(id, List.of()).forEach(c -> materialCodes.add(c.getMaterialCode()));
        }
        var materials = materialMapper.selectList(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getFactoryCode, factory).in(MdmMaterialEntity::getMaterialCode, materialCodes));
        for (String code : materialCodes)
            require(materials.stream().filter(m -> Objects.equals(code, m.getMaterialCode())
                    && Boolean.TRUE.equals(m.getStatus())).count() == 1, "BOM物料" + code + "不是同工厂唯一启用主数据");
        SortedSet<Long> affected = new TreeSet<>(frozen);
        // 同产品非默认历史版也必须出现在引用盘点中，不能因无默认基线而遗漏旧业务。
        catalog.stream().filter(b -> Objects.equals(b.getProductCode(), target.getProductCode()))
                .map(MdmBomEntity::getId).forEach(affected::add);
        boolean changed;
        do {
            changed = false;
            for (MdmBomChildEntity child : children)
                if (child.getSubitemBomId() != null && affected.contains(child.getSubitemBomId()))
                    changed |= affected.add(child.getBomId());
            require(affected.size() <= 200, "工程变更影响版本超过200个，请先拆分工程范围");
        } while (changed);
        MdmBomImpact.Query query = new MdmBomImpact.Query();
        query.setFactoryCode(factory);
        query.setBomIds(new ArrayList<>(affected));
        List<MdmBomImpact.Row> references = new ArrayList<>();
        List<Map<String, Object>> scans = new ArrayList<>();
        long[] evidenceBytes = {0};
        long started = System.nanoTime();
        for (String domain : List.of("PLAN_MRP", "MES_TASK", "MES_ORDER", "MES_MATERIAL_LIST"))
            references.addAll(scan(query, domain, scans, evidenceBytes, started));
        for (Long id : affected)
            if (!frozen.contains(id)) {
                MdmBomEntity parent = byId.get(id);
                references.add(new MdmBomImpact.Row().setDomain("MDM_PARENT_BOM").setId(id).setNumber(parent.getProductCode() + "/" + parent.getVersion())
                        .setFactoryCode(factory).setStatus(parent.getReleaseState()).setEvidenceJson(JSONUtil.toJsonStr(Arrays.asList(facts(parent),
                                byBom.getOrDefault(id, List.of()).stream().map(this::facts).toList()))));
            }
        references.sort(Comparator.comparing(MdmBomImpact.Row::getDomain).thenComparing(MdmBomImpact.Row::getId));
        List<Object> nodes = new ArrayList<>();
        for (Long id : frozen)
            nodes.add(Arrays.asList(facts(byId.get(id)), byBom.getOrDefault(id, List.of()).stream().map(this::facts).toList()));
        Snapshot s = new Snapshot();
        require(reviewHours >= 1 && reviewHours <= 168, "工程会签有效期配置须在1至168小时之间");
        s.setReviewPolicy(REVIEW_POLICY);
        s.setRequiredRoles(REVIEW_ROLES);
        s.setReviewHours(reviewHours);
        s.setTargetBomId(targetId);
        s.setSourceBomId(source == null ? null : source.getId());
        s.setFactoryCode(factory);
        s.setProductCode(target.getProductCode());
        s.setVersionGraph(nodes);
        s.setReferences(references);
        s.setFrozenBomIds(new ArrayList<>(frozen));
        s.setEvidenceVersion(2);
        s.setReferenceScans(scans);
        s.setDifferences(diff(source, target, byBom));
        s.setFingerprint(hash(JSONUtil.toJsonStr(Arrays.asList(2, s.getSourceBomId(), targetId, factory, nodes, references, s.getDifferences(),
                REVIEW_POLICY, REVIEW_ROLES, reviewHours))));
        require(snapshotJson(s).getBytes(StandardCharsets.UTF_8).length <= maxEvidenceBytes,
                "完整工程快照超过配置容量，禁止截断版本图或引用后申请");
        require(!s.getDifferences().isEmpty(), "新旧BOM没有业务内容差异，请勿仅更换版本号发布");
        return s;
    }

    private void graph(Long id, Long target, Map<Long, MdmBomEntity> catalog, Map<Long, List<MdmBomChildEntity>> children,
                       Set<Long> path, Set<Long> seen, boolean validateNew) {
        require(path.size() < 64 && path.add(id), "BOM包含循环引用或深度超过64层");
        if (seen.contains(id)) {
            path.remove(id);
            return;
        }
        MdmBomEntity bom = catalog.get(id);
        require(bom != null, "BOM子版本不存在或跨工厂：" + id);
        require(!validateNew || Objects.equals(id, target) || (Boolean.TRUE.equals(bom.getEnabled()) && !"DRAFT".equals(bom.getReleaseState())),
                "新版引用的下层BOM须先审批发布：" + id);
        if (validateNew && !Objects.equals(id, target)) require(
                (bom.getStartDate() == null || !bom.getStartDate().isAfter(LocalDate.now()))
                        && (bom.getEndDate() == null || !bom.getEndDate().isBefore(LocalDate.now())), "新版引用的下层BOM当前不在有效期内：" + id);
        require(bom.getStartDate() == null || bom.getEndDate() == null || !bom.getStartDate().isAfter(bom.getEndDate()), "BOM日期范围倒置");
        require(seen.size() < 200 || seen.contains(id), "单次冻结版本图超过200个节点");
        seen.add(id);
        List<MdmBomChildEntity> lines = children.getOrDefault(id, List.of());
        require(!validateNew || !lines.isEmpty(), "新BOM版本无明细：" + id);
        for (MdmBomChildEntity c : lines) {
            require(Objects.equals(c.getFactoryCode(), bom.getFactoryCode()), "BOM明细工厂来源不一致");
            if (validateNew) {
                require(StrUtil.isNotBlank(c.getMaterialCode()) && c.getQuantity() != null && c.getQuantity().signum() > 0,
                        "新BOM明细缺少物料或正用量");
                require(c.getFixedLossRate() == null || c.getFixedLossRate().signum() >= 0, "固定损耗不可为负");
                require(c.getVariableLossRate() == null || c.getVariableLossRate().signum() >= 0, "变动损耗不可为负");
            }
            if (c.getSubitemBomId() != null && c.getSubitemBomId() != 0) {
                MdmBomEntity sub = catalog.get(c.getSubitemBomId());
                require(sub != null && Objects.equals(sub.getProductCode(), c.getMaterialCode()), "子项BOM产品不匹配：" + c.getMaterialCode());
                if (validateNew) {
                    LocalDate effectiveFrom = bom.getStartDate() == null || bom.getStartDate().isBefore(LocalDate.now())
                            ? LocalDate.now() : bom.getStartDate();
                    require(sub.getStartDate() == null || !sub.getStartDate().isAfter(effectiveFrom), "子BOM开始日期未覆盖上层使用期：" + sub.getId());
                    require(sub.getEndDate() == null || (bom.getEndDate() != null && !bom.getEndDate().isAfter(sub.getEndDate())),
                            "上层BOM有效期不能超过子BOM截止日期，请明确版本切换范围：" + sub.getId());
                }
                graph(c.getSubitemBomId(), target, catalog, children, path, seen, validateNew);
            }
        }
        path.remove(id);
    }

    private List<Map<String, Object>> diff(MdmBomEntity source, MdmBomEntity target, Map<Long, List<MdmBomChildEntity>> children) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> oldHead = source == null ? new TreeMap<>() : facts(source);
        Map<String, Object> newHead = facts(target);
        for (String key : List.of("id", "version", "isDefault", "enabled", "releaseState")) {
            oldHead.remove(key);
            newHead.remove(key);
        }
        if (!Objects.equals(JSONUtil.toJsonStr(oldHead), JSONUtil.toJsonStr(newHead)))
            result.add(change("HEADER", oldHead, newHead));
        Map<String, List<Object>> oldLines = grouped(source == null ? List.of() : children.getOrDefault(source.getId(), List.of()));
        Map<String, List<Object>> newLines = grouped(children.getOrDefault(target.getId(), List.of()));
        Set<String> codes = new TreeSet<>(oldLines.keySet());
        codes.addAll(newLines.keySet());
        for (String code : codes)
            if (!Objects.equals(JSONUtil.toJsonStr(oldLines.get(code)), JSONUtil.toJsonStr(newLines.get(code))))
                result.add(change(code, oldLines.get(code), newLines.get(code)));
        return result;
    }

    private Map<String, List<Object>> grouped(List<MdmBomChildEntity> lines) {
        Map<String, List<Object>> result = new TreeMap<>();
        for (MdmBomChildEntity c : lines) {
            Map<String, Object> f = facts(c);
            f.remove("id");
            f.remove("bomId");
            result.computeIfAbsent(c.getMaterialCode(), ignored -> new ArrayList<>()).add(f);
        }
        result.values().forEach(l -> l.sort(Comparator.comparing(JSONUtil::toJsonStr)));
        return result;
    }

    private Map<String, Object> change(String item, Object oldValue, Object newValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("item", item);
        row.put("before", oldValue);
        row.put("after", newValue);
        return row;
    }

    private Map<String, Object> facts(Object entity) {
        Map<String, Object> fields = new TreeMap<>(JSONUtil.parseObj(entity));
        for (String key : List.of("createBy", "createTime", "updateBy", "updateTime", "tenantId", "delFlag"))
            fields.remove(key);
        fields.replaceAll((k, v) -> v instanceof BigDecimal n ? n.stripTrailingZeros().toPlainString()
                : v instanceof Long ? v.toString() : v);
        return fields;
    }

    private List<MdmBomImpact.Row> scan(MdmBomImpact.Query scope, String domain, List<Map<String, Object>> summaries,
                                        long[] totalBytes, long started) {
        require(maxEvidenceBytes >= 1_048_576 && maxEvidenceBytes <= 268_435_456
                && scanTimeoutSeconds >= 10 && scanTimeoutSeconds <= 600, "工程影响容量配置超出支持范围");
        MdmBomImpact.PageQuery query = new MdmBomImpact.PageQuery();
        query.setFactoryCode(scope.getFactoryCode());
        query.setBomIds(scope.getBomIds());
        query.setDomain(domain);
        query.setAfterId(0L);
        List<MdmBomImpact.Row> result = new ArrayList<>();
        long scanned = 0, pages = 0;
        while (true) {
            require((System.nanoTime() - started) / 1_000_000_000L <= scanTimeoutSeconds,
                    "工程影响扫描超时，未生成可审批的部分证据；请核实服务性能或调整支持范围内的扫描超时");
            R<MdmBomImpact.Batch> response = "PLAN_MRP".equals(domain) ? planImpact.page(query) : mesImpact.page(query);
            require((System.nanoTime() - started) / 1_000_000_000L <= scanTimeoutSeconds, "工程影响分页响应已超过扫描时限，本次证据作废，请重新分析");
            require(response != null && response.isOk() && response.getData() != null, domain + "影响分页不可核验，禁止截断审批");
            MdmBomImpact.Batch b = response.getData();
            require(Objects.equals(b.getDomain(), domain) && Objects.equals(b.getFactoryCode(), scope.getFactoryCode())
                    && b.getUpperId() != null && b.getUpperId() >= 0 && b.getNextId() != null
                    && b.getNextId() >= query.getAfterId() && b.getNextId() <= b.getUpperId()
                    && (query.getUpperId() == null || Objects.equals(query.getUpperId(), b.getUpperId()))
                    && b.getHasMore() != null && b.getScannedCount() != null && b.getScannedCount() >= 0
                    && b.getScannedCount() <= MdmBomImpactPages.PAGE_SIZE && b.getRows() != null
                    && b.getRows().size() <= b.getScannedCount(), "远端影响分页范围或游标响应异常");
            require(!b.getHasMore() || (b.getScannedCount() > 0 && b.getNextId() > query.getAfterId()), "远端影响分页未推进，禁止循环或漏页");
            require(b.getScannedCount() == 0 ? Objects.equals(b.getNextId(), query.getAfterId()) : b.getNextId() > query.getAfterId(),
                    "远端影响扫描数量与终止游标不一致");
            long previous = query.getAfterId();
            Set<String> expected = new HashSet<>();
            scope.getBomIds().forEach(id -> expected.add(id.toString()));
            for (MdmBomImpact.Row row : b.getRows()) {
                require(row != null && row.getId() != null && row.getId() > previous && row.getId() <= b.getNextId()
                                && Objects.equals(row.getDomain(), domain) && Objects.equals(row.getFactoryCode(), scope.getFactoryCode())
                                && Set.of("HEADER", "SNAPSHOT").contains(StrUtil.blankToDefault(row.getMatchKind(), ""))
                                && row.getMatchedBomIds() != null && !row.getMatchedBomIds().isEmpty()
                                && expected.containsAll(row.getMatchedBomIds()) && StrUtil.isNotBlank(row.getEvidenceJson()),
                        "远端引用证据重复、错序、跨范围或未给出精确BOM匹配依据");
                totalBytes[0] += row.getEvidenceJson().getBytes(StandardCharsets.UTF_8).length + 1024L;
                require(totalBytes[0] <= maxEvidenceBytes, "完整工程影响证据超过配置容量，禁止以已读取部分批准；请扩展证据存储或核实工程范围");
                previous = row.getId();
                result.add(row);
            }
            scanned += b.getScannedCount();
            pages++;
            query.setUpperId(b.getUpperId());
            query.setAfterId(b.getNextId());
            if (!b.getHasMore()) break;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("domain", domain);
        summary.put("upperId", query.getUpperId().toString());
        summary.put("lastScannedId", query.getAfterId().toString());
        summary.put("pages", pages);
        summary.put("candidateCount", scanned);
        summary.put("referenceCount", result.size());
        summary.put("excludedTextMatches", scanned - result.size());
        summary.put("complete", true);
        summaries.add(summary);
        return result;
    }

    private MdmBomEcnEntity replay(MdmBomEcnCommand c, String action) {
        mapper.lockRequest("MOM-ECN:" + TenantContextHolder.getTenantId() + ":" + c.getRequestId());
        sqlSession.clearCache();
        MdmBomEcnOperationEntity old = operationMapper.selectOne(new LambdaQueryWrapper<MdmBomEcnOperationEntity>()
                .eq(MdmBomEcnOperationEntity::getRequestId, c.getRequestId()));
        if (old == null) return null;
        require(Objects.equals(old.getRequestFingerprint(), requestFingerprint(c, action)) && Objects.equals(old.getOperatorId(), governance.actor()),
                "同一请求号不能更换操作、目标、依据、快照或操作者");
        return detail(old.getEcnId());
    }

    private void audit(MdmBomEcnEntity row, MdmBomEcnCommand c, String action) {
        require(operationMapper.insert(new MdmBomEcnOperationEntity().setEcnId(row.getId()).setRequestId(c.getRequestId())
                .setRequestFingerprint(requestFingerprint(c, action)).setAction(action).setReason(c.getReason())
                .setOperatorId(governance.actor()).setResultStatus(row.getStatus()).setOperationTime(LocalDateTime.now())
                .setRoleCode(c.getRoleCode()).setSourceFingerprint(row.getFingerprint()).setReviewPolicy(row.getReviewPolicy())
                .setFactoryCode(row.getFactoryCode())) == 1, "工程变更永久操作账保存失败");
    }

    private String requestFingerprint(MdmBomEcnCommand c, String action) {
        List<Object> parts = new ArrayList<>(Arrays.asList(action, c.getEcnId(), c.getTargetBomId(), c.getReason(), c.getDisposition(), c.getExpectedFingerprint()));
        if (c.getRoleCode() != null)
            parts.add(c.getRoleCode()); // Preserve permanent request hashes for old non-role actions.
        return hash(JSONUtil.toJsonStr(parts));
    }

    private void validate(MdmBomEcnCommand c) {
        require(c != null && StrUtil.isNotBlank(c.getRequestId()) && c.getRequestId().length() <= 64
                && StrUtil.isNotBlank(c.getReason()) && c.getReason().length() <= 1000
                && c.getExpectedFingerprint() != null && c.getExpectedFingerprint().matches("[a-f0-9]{64}"), "须提供请求号、1000字内依据及已核实的快照指纹");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String snapshotJson(Snapshot snapshot) {
        // 持久JSON同样保留雪花ID精度；不能依赖HTTP层的Long序列化器。
        return JSONUtil.toJsonStr(stringLongs(JSONUtil.parse(JSONUtil.toJsonStr(snapshot))));
    }

    private Object stringLongs(Object value) {
        if (value instanceof Long) return value.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(key.toString(), stringLongs(item)));
            return converted;
        }
        if (value instanceof Collection<?> items) return items.stream().map(this::stringLongs).toList();
        return value;
    }

    @Data
    public static class Snapshot {
        private Long sourceBomId;
        private Long targetBomId;
        private String factoryCode;
        private String productCode;
        private List<Object> versionGraph;
        private List<Map<String, Object>> differences;
        private List<MdmBomImpact.Row> references;
        private List<Long> frozenBomIds;
        private String fingerprint;
        private Integer evidenceVersion;
        private List<Map<String, Object>> referenceScans;
        private String reviewPolicy;
        private List<String> requiredRoles;
        private Integer reviewHours;
    }
}
