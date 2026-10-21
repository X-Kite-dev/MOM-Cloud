package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyActionDTO;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogDTO;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogQueryDTO;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmProductionLotPolicyEntity;
import com.kitex.mom.mdm.entity.MdmProductionLotPolicyLogEntity;
import com.kitex.mom.mdm.mapper.MdmProductionLotPolicyLogMapper;
import com.kitex.mom.mdm.mapper.MdmProductionLotPolicyMapper;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.service.MdmProductionLotPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产批量策略服务实现。
 *
 * <p>草稿可修改，ACTIVE版本不可原地覆盖；启用人与编制人必须分离，且同产品有效期
 * 不允许存在两个正式版本。目录查询遇到重叠版本时故障关闭，不任意挑选。</p>
 */
@Service
@RequiredArgsConstructor
public class MdmProductionLotPolicyServiceImpl
        extends ServiceImpl<MdmProductionLotPolicyMapper, MdmProductionLotPolicyEntity>
        implements MdmProductionLotPolicyService {

    private static final Set<String> CLOSE_ACTIONS = Set.of("SUSPEND", "RETIRE");
    private final MdmMaterialService materialService;
    private final MdmProductionLotPolicyLogMapper logMapper;

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MdmProductionLotPolicyEntity> pagePolicies(SearchPage page,
                                                           MdmProductionLotPolicyEntity query) {
        requireUser();
        require(page != null && page.getCurrent() > 0 && page.getSize() > 0 && page.getSize() <= 200,
                "生产批量策略页码须大于0，每页数量须为1至200");
        LambdaQueryWrapper<MdmProductionLotPolicyEntity> scope = currentScope()
                .like(StrUtil.isNotBlank(query.getPolicyNo()), MdmProductionLotPolicyEntity::getPolicyNo,
                        StrUtil.trim(query.getPolicyNo()))
                .like(StrUtil.isNotBlank(query.getProductCode()), MdmProductionLotPolicyEntity::getProductCode,
                        StrUtil.trim(query.getProductCode()))
                .like(StrUtil.isNotBlank(query.getProductName()), MdmProductionLotPolicyEntity::getProductName,
                        StrUtil.trim(query.getProductName()))
                .eq(StrUtil.isNotBlank(query.getStatus()), MdmProductionLotPolicyEntity::getStatus, query.getStatus())
                .orderByDesc(MdmProductionLotPolicyEntity::getValidStartTime)
                .orderByDesc(MdmProductionLotPolicyEntity::getCreateTime)
                .orderByDesc(MdmProductionLotPolicyEntity::getId);
        // 使用产品定义的稳定排序，不接受通用SearchPage携带任意数据库字段排序。
        return page(new Page<>(page.getCurrent(), page.getSize()), scope);
    }

    @Override
    @Transactional(readOnly = true)
    public MdmProductionLotPolicyEntity detail(Long id) {
        requireUser();
        MdmProductionLotPolicyEntity policy = requirePolicy(id, false);
        policy.setRevisionToken(revisionToken(policy));
        return policy;
    }

    @Override
    @Transactional(readOnly = true)
    public Integer nextVersion(Long id) {
        requireUser();
        MdmProductionLotPolicyEntity source = requirePolicy(id, false);
        MdmProductionLotPolicyEntity latest = getOne(currentScope()
                .eq(MdmProductionLotPolicyEntity::getPolicyNo, source.getPolicyNo())
                .orderByDesc(MdmProductionLotPolicyEntity::getVersionNo).last("LIMIT 1"));
        require(latest != null && latest.getVersionNo() != null, "未找到可核验的策略版本序列");
        require(latest.getVersionNo() < Integer.MAX_VALUE, "版本号已到上限，请使用新的策略编号");
        return latest.getVersionNo() + 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MdmProductionLotPolicyLogEntity> logs(Long id) {
        MdmProductionLotPolicyEntity policy = detail(id);
        return logMapper.selectList(new LambdaQueryWrapper<MdmProductionLotPolicyLogEntity>()
                .eq(MdmProductionLotPolicyLogEntity::getTenantId, policy.getTenantId())
                .eq(MdmProductionLotPolicyLogEntity::getFactoryCode, policy.getFactoryCode())
                .eq(MdmProductionLotPolicyLogEntity::getPolicyId, policy.getId())
                .orderByDesc(MdmProductionLotPolicyLogEntity::getOperatedTime)
                .orderByDesc(MdmProductionLotPolicyLogEntity::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraft(MdmProductionLotPolicyEntity entity) {
        require(entity != null, "生产批量策略不能为空");
        MomUser user = requireUser();
        MdmProductionLotPolicyEntity draft = new MdmProductionLotPolicyEntity();
        draft.setTenantId(requireTenant());
        draft.setFactoryCode(requireCurrentFactory());
        draft.setCreateBy(user.getUsername());
        draft.setCreateTime(LocalDateTime.now());
        draft.setDelFlag("0");
        copyDraftFields(entity, draft);
        normalizeAndValidate(draft);
        assertVersionUnique(draft);
        draft.setStatus("DRAFT");
        require(save(draft), "生产批量策略草稿保存失败");
        log(draft, "CREATE", null, "DRAFT", null, null, JSONUtil.toJsonStr(draft));
        return draft.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDraft(MdmProductionLotPolicyEntity entity) {
        require(entity != null && entity.getId() != null, "生产批量策略ID不能为空");
        requireUser();
        // 草稿编辑与启用共用行锁；先锁后校验，避免旧草稿覆盖已启用版本及审计快照失真。
        MdmProductionLotPolicyEntity current = requirePolicy(entity.getId(), true);
        require("DRAFT".equals(current.getStatus()), "只有草稿状态的生产批量策略可以修改");
        assertRevision(current, entity.getRevisionToken());
        String before = JSONUtil.toJsonStr(current);
        copyDraftFields(entity, current);
        normalizeAndValidate(current);
        assertVersionUnique(current);
        require(updateById(current), "生产批量策略草稿修改失败");
        log(current, "UPDATE", "DRAFT", "DRAFT", null, before, JSONUtil.toJsonStr(current));
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean changeStatus(MdmProductionLotPolicyActionDTO action) {
        if (action == null || action.getId() == null || StrUtil.isBlank(action.getAction())) {
            throw new CheckedException("生产批量策略状态操作缺少ID或动作");
        }
        MomUser user = requireUser();
        MdmProductionLotPolicyEntity current = requirePolicy(action.getId(), true);
        require(Objects.equals(action.getExpectedStatus(), current.getStatus()),
                "生产批量策略状态已变化或未提供复核状态，请刷新后重新核对");
        assertRevision(current, action.getExpectedRevisionToken());
        String operator = user.getUsername();
        String target = action.getAction().trim().toUpperCase(Locale.ROOT);
        String from = current.getStatus();
        String before = JSONUtil.toJsonStr(current);
        LocalDateTime now = LocalDateTime.now();
        if ("ACTIVATE".equals(target)) {
            if (!Set.of("DRAFT", "SUSPENDED").contains(from)) {
                throw new CheckedException("只有草稿或暂停策略可以启用");
            }
            require(StrUtil.isNotBlank(current.getCreateBy()), "策略缺少原编制人，不能启用，请核对历史来源");
            require(!Objects.equals(current.getCreateBy(), operator), "生产批量策略编制人与启用复核人必须岗位分离");
            normalizeAndValidate(current);
            if (current.getValidEndTime() != null && !current.getValidEndTime().isAfter(now)) {
                throw new CheckedException("生产批量策略已超过有效期，不能启用");
            }
            assertNoActiveOverlap(current);
            current.setStatus("ACTIVE").setApprovedBy(operator).setApprovedByName(operatorName(user))
                    .setApprovedTime(now).setCloseReason(null).setClosedBy(null)
                    .setClosedByName(null).setClosedTime(null);
        } else if (CLOSE_ACTIONS.contains(target)) {
            if (!"ACTIVE".equals(from)) {
                throw new CheckedException("只有启用策略可以暂停或退役");
            }
            String reason = requiredText(action.getReason(), 500, "暂停或退役原因");
            current.setStatus("SUSPEND".equals(target) ? "SUSPENDED" : "RETIRED")
                    .setCloseReason(reason)
                    .setClosedBy(operator).setClosedByName(operatorName(user)).setClosedTime(now);
        } else {
            throw new CheckedException("不支持的生产批量策略动作：" + target);
        }
        require(updateById(current), "生产批量策略状态更新失败");
        log(current, target, from, current.getStatus(), StrUtil.trim(action.getReason()), before, JSONUtil.toJsonStr(current));
        return true;
    }

    @Override
    public MdmProductionLotPolicyCatalogDTO catalog(MdmProductionLotPolicyCatalogQueryDTO query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode()) || CollUtil.isEmpty(query.getProductCodes())) {
            throw new CheckedException("生产批量策略目录查询缺少工厂或产品范围");
        }
        require(query.getProductCodes().size() <= 500, "生产批量策略查询最多提交500个产品编码");
        String factory = query.getFactoryCode().trim();
        require(Objects.equals(factory, requireCurrentFactory()), "生产批量策略目录不属于当前工厂");
        LocalDateTime effective = Objects.requireNonNullElse(query.getEffectiveTime(), LocalDateTime.now());
        List<String> products = query.getProductCodes().stream().map(StrUtil::trim)
                .filter(StrUtil::isNotBlank).distinct().sorted().toList();
        require(!products.isEmpty() && products.size() <= 500, "生产批量策略查询须提供1至500个有效产品编码");
        require(products.stream().allMatch(code -> code.length() <= 100), "产品编码不能超过100字");
        List<MdmProductionLotPolicyEntity> active = list(new LambdaQueryWrapper<MdmProductionLotPolicyEntity>()
                .eq(MdmProductionLotPolicyEntity::getTenantId, requireTenant())
                .eq(MdmProductionLotPolicyEntity::getFactoryCode, factory)
                .in(MdmProductionLotPolicyEntity::getProductCode, products)
                .eq(MdmProductionLotPolicyEntity::getStatus, "ACTIVE")
                .le(MdmProductionLotPolicyEntity::getValidStartTime, effective)
                .and(w -> w.isNull(MdmProductionLotPolicyEntity::getValidEndTime)
                        .or().gt(MdmProductionLotPolicyEntity::getValidEndTime, effective))
                .orderByAsc(MdmProductionLotPolicyEntity::getProductCode)
                .orderByDesc(MdmProductionLotPolicyEntity::getVersionNo));
        Map<String, List<MdmProductionLotPolicyEntity>> grouped = active.stream().collect(Collectors.groupingBy(
                MdmProductionLotPolicyEntity::getProductCode, LinkedHashMap::new, Collectors.toList()));
        List<String> conflicts = grouped.entrySet().stream().filter(entry -> entry.getValue().size() != 1)
                .map(Map.Entry::getKey).toList();
        if (!conflicts.isEmpty()) {
            throw new CheckedException("产品存在重叠的正式生产批量策略：" + String.join(",", conflicts));
        }
        Map<String, MdmProductionLotPolicyEntity> byProduct = grouped.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().get(0), (left, right) -> left, LinkedHashMap::new));
        List<String> missing = products.stream().filter(code -> !byProduct.containsKey(code)).toList();
        List<Object> evidence = byProduct.values().stream().map(item -> (Object) Arrays.asList(item.getId(),
                item.getPolicyNo(), item.getVersionNo(), item.getProductCode(), item.getMinimumBatchQuantity(),
                item.getEconomicBatchQuantity(), item.getMaximumBatchQuantity(), item.getBatchMultiple(),
                item.getValidStartTime(), item.getValidEndTime(), item.getUpdateTime())).toList();
        return new MdmProductionLotPolicyCatalogDTO().setPolicyByProduct(byProduct)
                .setProductsWithoutPolicy(missing).setEvidenceFingerprint(hash(JSONUtil.toJsonStr(
                        Arrays.asList(factory, products, missing, evidence))));
    }

    private void normalizeAndValidate(MdmProductionLotPolicyEntity entity) {
        entity.setPolicyNo(requiredText(entity.getPolicyNo(), 64, "策略编号"));
        entity.setProductCode(requiredText(entity.getProductCode(), 100, "产品编码"));
        entity.setVersionNo(Objects.requireNonNullElse(entity.getVersionNo(), 1));
        if (StrUtil.hasBlank(entity.getPolicyNo(), entity.getProductCode()) || entity.getValidStartTime() == null) {
            throw new CheckedException("策略编号、产品和生效时间不能为空");
        }
        require(entity.getVersionNo() > 0, "策略版本号必须大于0");
        BigDecimal minimum = positive(entity.getMinimumBatchQuantity(), "最小生产批量");
        BigDecimal economic = positive(entity.getEconomicBatchQuantity(), "经济生产批量");
        BigDecimal maximum = positive(entity.getMaximumBatchQuantity(), "最大生产批量");
        BigDecimal multiple = positive(entity.getBatchMultiple(), "整批倍数");
        if (minimum.compareTo(economic) > 0 || economic.compareTo(maximum) > 0) {
            throw new CheckedException("生产批量必须满足：最小批量 ≤ 经济批量 ≤ 最大批量");
        }
        if (!isMultiple(minimum, multiple) || !isMultiple(economic, multiple)
                || !isMultiple(maximum, multiple)) {
            throw new CheckedException("最小、经济和最大生产批量都必须是整批倍数的整数倍");
        }
        if (entity.getValidEndTime() != null && !entity.getValidEndTime().isAfter(entity.getValidStartTime())) {
            throw new CheckedException("失效时间必须晚于生效时间");
        }
        List<MdmMaterialEntity> materials = materialService.list(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getTenantId, requireTenant())
                .eq(MdmMaterialEntity::getFactoryCode, entity.getFactoryCode())
                .eq(MdmMaterialEntity::getMaterialCode, entity.getProductCode())
                .eq(MdmMaterialEntity::getStatus, true).last("LIMIT 2"));
        require(!materials.isEmpty(), "产品[" + entity.getProductCode() + "]不存在或未启用");
        require(materials.size() == 1, "产品[" + entity.getProductCode() + "]存在重复的启用主档，请先核对主数据");
        MdmMaterialEntity material = materials.get(0);
        entity.setProductName(material.getMaterialName());
        entity.setMinimumBatchQuantity(minimum).setEconomicBatchQuantity(economic)
                .setMaximumBatchQuantity(maximum).setBatchMultiple(multiple);
    }

    private BigDecimal positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 6) {
            throw new CheckedException(label + "必须大于0且最多保留6位小数");
        }
        BigDecimal normalized = value.stripTrailingZeros();
        require(normalized.precision() - normalized.scale() <= 14, label + "整数部分最多14位");
        return normalized.setScale(6);
    }

    private boolean isMultiple(BigDecimal value, BigDecimal multiple) {
        return value.remainder(multiple).compareTo(BigDecimal.ZERO) == 0;
    }

    private void assertVersionUnique(MdmProductionLotPolicyEntity entity) {
        long count = count(new LambdaQueryWrapper<MdmProductionLotPolicyEntity>()
                .eq(MdmProductionLotPolicyEntity::getTenantId, requireTenant())
                .eq(MdmProductionLotPolicyEntity::getFactoryCode, entity.getFactoryCode())
                .eq(MdmProductionLotPolicyEntity::getPolicyNo, entity.getPolicyNo())
                .eq(MdmProductionLotPolicyEntity::getVersionNo, entity.getVersionNo())
                .ne(entity.getId() != null, MdmProductionLotPolicyEntity::getId, entity.getId()));
        require(count == 0, "生产批量策略[" + entity.getPolicyNo() + "]V"
                + entity.getVersionNo() + "已存在");
    }

    private void assertNoActiveOverlap(MdmProductionLotPolicyEntity entity) {
        List<MdmProductionLotPolicyEntity> active = list(new LambdaQueryWrapper<MdmProductionLotPolicyEntity>()
                .eq(MdmProductionLotPolicyEntity::getTenantId, requireTenant())
                .eq(MdmProductionLotPolicyEntity::getFactoryCode, entity.getFactoryCode())
                .eq(MdmProductionLotPolicyEntity::getProductCode, entity.getProductCode())
                .eq(MdmProductionLotPolicyEntity::getStatus, "ACTIVE")
                .ne(entity.getId() != null, MdmProductionLotPolicyEntity::getId, entity.getId()));
        boolean overlap = active.stream().anyMatch(item -> item.getValidStartTime().isBefore(
                Objects.requireNonNullElse(entity.getValidEndTime(), LocalDateTime.MAX))
                && entity.getValidStartTime().isBefore(
                Objects.requireNonNullElse(item.getValidEndTime(), LocalDateTime.MAX)));
        require(!overlap, "该产品在所选有效期内已有正式生产批量策略");
    }

    /**
     * 仅复制可编辑的业务参数；请求不能覆盖创建人、组织归属或审批/关闭事实。
     */
    private void copyDraftFields(MdmProductionLotPolicyEntity source, MdmProductionLotPolicyEntity target) {
        target.setPolicyNo(source.getPolicyNo()).setProductCode(source.getProductCode())
                .setVersionNo(source.getVersionNo()).setMinimumBatchQuantity(source.getMinimumBatchQuantity())
                .setEconomicBatchQuantity(source.getEconomicBatchQuantity())
                .setMaximumBatchQuantity(source.getMaximumBatchQuantity()).setBatchMultiple(source.getBatchMultiple())
                .setValidStartTime(source.getValidStartTime()).setValidEndTime(source.getValidEndTime());
        require(source.getRemark() == null || source.getRemark().trim().length() <= 500, "适用说明不能超过500字");
        target.setRemark(StrUtil.isBlank(source.getRemark()) ? null : source.getRemark().trim());
    }

    private void log(MdmProductionLotPolicyEntity entity, String action, String from, String to,
                     String reason, String before, String after) {
        MomUser user = requireUser();
        MdmProductionLotPolicyLogEntity row = new MdmProductionLotPolicyLogEntity();
        row.setPolicyId(entity.getId());
        row.setAction(action);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setReason(reason);
        row.setOperatedBy(user.getUsername());
        row.setOperatedByName(operatorName(user));
        row.setOperatedTime(LocalDateTime.now());
        row.setBeforeSnapshot(before);
        row.setAfterSnapshot(after);
        row.setFactoryCode(entity.getFactoryCode());
        row.setTenantId(entity.getTenantId());
        logMapper.insert(row);
    }

    private String operatorName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("生产批量策略证据指纹生成失败", ex);
        }
    }

    private String requireCurrentFactory() {
        requireTenant();
        String factory = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        require(StrUtil.isNotBlank(factory) && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "请先选择当前工厂，生产批量策略操作不能跳过工厂隔离");
        return factory;
    }

    private Long requireTenant() {
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip(),
                "生产批量策略操作须提供实际租户，不能跳过租户隔离");
        return tenant;
    }

    private MomUser requireUser() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()),
                "生产批量策略操作须实名登录");
        return user;
    }

    private LambdaQueryWrapper<MdmProductionLotPolicyEntity> currentScope() {
        return new LambdaQueryWrapper<MdmProductionLotPolicyEntity>()
                .eq(MdmProductionLotPolicyEntity::getTenantId, requireTenant())
                .eq(MdmProductionLotPolicyEntity::getFactoryCode, requireCurrentFactory());
    }

    /**
     * ID查找也显式限定租户/工厂，行锁只能锁定当前组织可见的版本。
     */
    private MdmProductionLotPolicyEntity requirePolicy(Long id, boolean lock) {
        require(id != null, "生产批量策略ID不能为空");
        LambdaQueryWrapper<MdmProductionLotPolicyEntity> query = currentScope()
                .eq(MdmProductionLotPolicyEntity::getId, id);
        if (lock) {
            query.last("FOR UPDATE");
        }
        MdmProductionLotPolicyEntity policy = getOne(query);
        require(policy != null, "生产批量策略不存在或不属于当前工厂");
        return policy;
    }

    /**
     * 对精确持久值计算令牌，不依赖JSON时间格式或浏览器浮点数。
     */
    private String revisionToken(MdmProductionLotPolicyEntity policy) {
        return hash(JSONUtil.toJsonStr(Arrays.asList(policy.getId(), policy.getTenantId(),
                policy.getFactoryCode(), policy.getPolicyNo(), policy.getProductCode(), policy.getProductName(),
                policy.getVersionNo(), policy.getMinimumBatchQuantity(), policy.getEconomicBatchQuantity(),
                policy.getMaximumBatchQuantity(), policy.getBatchMultiple(),
                Objects.toString(policy.getValidStartTime(), null), Objects.toString(policy.getValidEndTime(), null),
                policy.getStatus(), policy.getRemark(), policy.getCreateBy(), policy.getApprovedBy(),
                Objects.toString(policy.getApprovedTime(), null), policy.getCloseReason(), policy.getClosedBy(),
                Objects.toString(policy.getClosedTime(), null), Objects.toString(policy.getUpdateTime(), null))));
    }

    private void assertRevision(MdmProductionLotPolicyEntity current, String expected) {
        require(StrUtil.isNotBlank(expected), "请先读取生产批量策略最新详情并回传版本令牌");
        require(Objects.equals(expected, revisionToken(current)),
                "生产批量策略已被其他人修改，请刷新后重新核对");
    }

    private String requiredText(String value, int maximum, String label) {
        require(StrUtil.isNotBlank(value), label + "不能为空");
        String result = value.trim();
        require(result.length() <= maximum, label + "不能超过" + maximum + "字");
        return result;
    }
}
