package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesIdentifierMappingEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesIdentifierMappingMapper;
import com.kitex.mom.mes.mapper.MesIdentifierBindingMapper;
import com.kitex.mom.mes.entity.MesIdentifierBindingEntity;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesIdentifierMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 生产标识映射的版本治理和运行期解析实现。
 */
@Service
@RequiredArgsConstructor
public class MesIdentifierMappingServiceImpl
        extends ServiceImpl<MesIdentifierMappingMapper, MesIdentifierMappingEntity>
        implements MesIdentifierMappingService {

    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String RETIRED = "RETIRED";
    private static final Set<String> IDENTIFIER_TYPES = Set.of(
            "TEMPORARY", "ALTERNATE", "EXTERNAL", "CUSTOMER", "SUPPLIER", "LEGACY");
    private static final Set<String> OBJECT_TYPES = Set.of("UNIT", "BATCH", "TRAVELER", "PACKAGE", "ANY");
    private static final Set<String> RESOLVE_MODES = Set.of(
            "CANONICAL_ONLY", "ALLOW_MAPPING", "MAPPING_REQUIRED");

    private final MesActualFactoryAccessService factoryAccess;
    private final MesExecutionTemplateService executionTemplateService;
    private final MesIdentifierBindingMapper bindingMapper;

    @Override
    public IPage<MesIdentifierMappingEntity> queryPage(Page<MesIdentifierMappingEntity> page,
                                                       MesIdentifierMappingEntity query) {
        String factory = factoryAccess.require("read");
        LambdaQueryWrapper<MesIdentifierMappingEntity> wrapper = new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getFactoryCode, factory);
        if (query != null) {
            wrapper.like(StrUtil.isNotBlank(query.getMappingCode()), MesIdentifierMappingEntity::getMappingCode,
                            query.getMappingCode())
                    .like(StrUtil.isNotBlank(query.getAliasCode()), MesIdentifierMappingEntity::getAliasCode,
                            query.getAliasCode())
                    .like(StrUtil.isNotBlank(query.getCanonicalCode()), MesIdentifierMappingEntity::getCanonicalCode,
                            query.getCanonicalCode())
                    .eq(StrUtil.isNotBlank(query.getIdentifierType()), MesIdentifierMappingEntity::getIdentifierType,
                            query.getIdentifierType())
                    .eq(StrUtil.isNotBlank(query.getStatus()), MesIdentifierMappingEntity::getStatus,
                            query.getStatus());
        }
        wrapper.orderByAsc(MesIdentifierMappingEntity::getMappingCode)
                .orderByDesc(MesIdentifierMappingEntity::getVersionNo);
        return page(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesIdentifierMappingEntity saveDraft(MesIdentifierMappingEntity input) {
        String factory = factoryAccess.require("write");
        normalizeAndValidate(input);
        baseMapper.lockMapping(factory, input.getMappingCode());
        MesIdentifierMappingEntity existing = input.getId() == null ? null : getOne(
                new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                        .eq(MesIdentifierMappingEntity::getId, input.getId())
                        .eq(MesIdentifierMappingEntity::getFactoryCode, factory).last("FOR UPDATE"));
        if (input.getId() != null && existing == null) {
            throw new CheckedException("生产标识映射不存在");
        }
        if (existing != null && !DRAFT.equals(existing.getStatus())) {
            throw new CheckedException("已发布或已退役映射不可原地修改，请复制新版本");
        }
        if (existing != null && !Objects.equals(existing.getMappingCode(), input.getMappingCode())) {
            throw new CheckedException("映射编码不可修改，请另建映射");
        }
        if (existing == null) {
            int version = list(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                    .eq(MesIdentifierMappingEntity::getFactoryCode, factory)
                    .eq(MesIdentifierMappingEntity::getMappingCode, input.getMappingCode()))
                    .stream().map(MesIdentifierMappingEntity::getVersionNo).filter(Objects::nonNull)
                    .max(Integer::compareTo).orElse(0) + 1;
            input.setVersionNo(version).setStatus(DRAFT).setUsedCount(0);
        } else {
            input.setVersionNo(existing.getVersionNo()).setStatus(DRAFT)
                    .setUsedCount(existing.getUsedCount());
            input.setCreateBy(existing.getCreateBy());
            input.setCreateTime(existing.getCreateTime());
            input.setDelFlag(existing.getDelFlag());
        }
        input.setPublishedBy(null).setPublishedTime(null).setFactoryCode(factory)
                .setTenantId(TenantContextHolder.getTenantId());
        saveOrUpdate(input);
        return getById(input.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesIdentifierMappingEntity copyVersion(Long sourceId) {
        String factory = factoryAccess.require("write");
        MesIdentifierMappingEntity source = getOne(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getId, sourceId)
                .eq(MesIdentifierMappingEntity::getFactoryCode, factory));
        if (source == null) {
            throw new CheckedException("生产标识映射不存在");
        }
        MesIdentifierMappingEntity copy = new MesIdentifierMappingEntity();
        BeanUtil.copyProperties(source, copy);
        copy.setId(null);
        copy.setVersionNo(null).setStatus(DRAFT).setUsedCount(0)
                .setPublishedBy(null).setPublishedTime(null);
        copy.setCreateBy(null);
        copy.setCreateTime(null);
        copy.setUpdateBy(null);
        copy.setUpdateTime(null);
        return saveDraft(copy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesIdentifierMappingEntity publish(Long id) {
        String factory = factoryAccess.require("write");
        MesIdentifierMappingEntity observed = baseMapper.selectOne(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesIdentifierMappingEntity::getFactoryCode, factory).eq(MesIdentifierMappingEntity::getId, id));
        if (observed == null) throw new CheckedException("生产标识映射不存在");
        // 共同别名字面锁先于映射行锁，与动态绑定、普通报工使用相同键域。
        if (!Integer.valueOf(1).equals(bindingMapper.lockIdentity(MesIdentifierBindingLock.key(
                TenantContextHolder.getTenantId(), factory, observed.getAliasCode())))) {
            throw new CheckedException("未取得别名发布业务锁");
        }
        MesIdentifierMappingEntity mapping = locked(id, factory);
        if (mapping == null) {
            throw new CheckedException("生产标识映射不存在");
        }
        if (!DRAFT.equals(mapping.getStatus())) {
            throw new CheckedException("只有草稿映射可以发布");
        }
        if (!Objects.equals(observed.getAliasCode(), mapping.getAliasCode())) {
            throw new CheckedException("草稿别名已变化，请刷新后重试，禁止持错业务锁发布");
        }
        normalizeAndValidate(mapping);
        if (mapping.getEffectiveTo() != null && !mapping.getEffectiveTo().isAfter(LocalDateTime.now())) {
            throw new CheckedException("标识映射已经失效，不能发布");
        }
        assertNoAmbiguousMapping(mapping, factory);
        if (bindingMapper.selectCount(new LambdaQueryWrapper<MesIdentifierBindingEntity>()
                .eq(MesIdentifierBindingEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesIdentifierBindingEntity::getFactoryCode, factory)
                .eq(MesIdentifierBindingEntity::getExternalCode, mapping.getAliasCode())
                .ne(MesIdentifierBindingEntity::getCanonicalIdentifier, mapping.getCanonicalCode())) > 0) {
            throw new CheckedException("别名与已登记动态外码的原产品含义冲突，不能通过发布新映射改写占用历史");
        }
        MomUser user = SecurityUtils.getUser();
        mapping.setStatus(PUBLISHED).setPublishedBy(user == null ? "SYSTEM" : user.getUsername())
                .setPublishedTime(LocalDateTime.now());
        updateById(mapping);
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesIdentifierMappingEntity retire(Long id) {
        String factory = factoryAccess.require("write");
        MesIdentifierMappingEntity mapping = locked(id, factory);
        if (mapping == null) {
            throw new CheckedException("生产标识映射不存在");
        }
        if (!PUBLISHED.equals(mapping.getStatus())) {
            throw new CheckedException("只有已发布映射可以退役");
        }
        mapping.setStatus(RETIRED);
        updateById(mapping);
        return getById(id);
    }

    @Override
    public List<String> findCanonicalCandidates(String submittedIdentifier) {
        String submitted = required(submittedIdentifier, 200, "生产标识");
        String factory = factoryAccess.require("read");
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(submitted);
        list(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesIdentifierMappingEntity::getFactoryCode, factory)
                .eq(MesIdentifierMappingEntity::getAliasCode, submitted)
                .in(MesIdentifierMappingEntity::getStatus, List.of(PUBLISHED, RETIRED))
                .orderByDesc(MesIdentifierMappingEntity::getPriority)
                .orderByDesc(MesIdentifierMappingEntity::getVersionNo)
                .orderByDesc(MesIdentifierMappingEntity::getId))
                .stream()
                .map(MesIdentifierMappingEntity::getCanonicalCode)
                .filter(StrUtil::isNotBlank)
                .forEach(candidates::add);
        return List.copyOf(candidates);
    }

    @Override
    public void resolve(MesTaskArrangeEntity arrange, MesTaskEntity task, ReportModeEnum mode,
                        MesTaskReportVO command) {
        String factory = factoryAccess.require("read");
        if (arrange == null || task == null || command == null
                || !Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(task.getTenantId(), TenantContextHolder.getTenantId())
                || !Objects.equals(arrange.getFactoryCode(), factory) || !Objects.equals(task.getFactoryCode(), factory)
                || !Objects.equals(arrange.getTaskCode(), task.getCode())) {
            throw new CheckedException("标识解析必须基于当前实际租户工厂的真实派工和工单");
        }
        // 数量模式及无映射内部调用也不能沿用客户端/上一轮映射事实。
        command.setIdentifierMappingId(null);
        command.setIdentifierType(null);
        command.setIdentifierResolutionSnapshot(null);
        command.setSubmittedIdentifier(null);
        if (StrUtil.isBlank(command.getTransferCode())) {
            return;
        }
        String submitted = command.getTransferCode().trim();
        if (submitted.length() > 200) {
            throw new CheckedException("生产标识长度不能超过200位");
        }
        command.setSubmittedIdentifier(submitted);
        MesExecutionContractDTO contract = executionTemplateService.contract(arrange);
        String resolveMode = StrUtil.blankToDefault(contract.getIdentifierResolveMode(), "ALLOW_MAPPING");
        if (!RESOLVE_MODES.contains(resolveMode)) {
            throw new CheckedException("派工冻结的标识解析方式无效，禁止继续报工");
        }
        String objectType = objectType(mode);
        LocalDateTime now = LocalDateTime.now();
        List<MesIdentifierMappingEntity> governed = list(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesIdentifierMappingEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesIdentifierMappingEntity::getAliasCode, submitted)
                .in(MesIdentifierMappingEntity::getStatus, List.of(PUBLISHED, RETIRED)));
        List<MesIdentifierMappingEntity> published = governed.stream()
                .filter(mapping -> PUBLISHED.equals(mapping.getStatus())).toList();
        List<MesIdentifierMappingEntity> active = published.stream()
                .filter(mapping -> isEffective(mapping, now)).toList();
        List<MesIdentifierMappingEntity> scoped = active.stream()
                .filter(mapping -> matches(mapping, arrange, task, objectType)).toList();

        if ("CANONICAL_ONLY".equals(resolveMode)) {
            if (!active.isEmpty()) {
                throw new CheckedException("当前执行模板只接受规范码，不能使用已登记的别名或外部码");
            }
            if (!published.isEmpty()) {
                throw new CheckedException("生产标识映射尚未生效或已经过期");
            }
            if (!governed.isEmpty()) {
                throw new CheckedException("生产标识映射已经退役，禁止继续使用原别名");
            }
            applyCanonical(command, submitted, objectType, resolveMode);
            return;
        }

        Set<String> allowedTypes = contract.getAllowedIdentifierTypes() == null
                ? IDENTIFIER_TYPES : Set.copyOf(contract.getAllowedIdentifierTypes());
        List<MesIdentifierMappingEntity> allowed = scoped.stream()
                .filter(mapping -> allowedTypes.contains(mapping.getIdentifierType()))
                .sorted(Comparator.comparing(MesIdentifierMappingEntity::getPriority,
                                Comparator.nullsFirst(Integer::compareTo)).reversed()
                        .thenComparing(this::specificity, Comparator.reverseOrder())
                        .thenComparing(MesIdentifierMappingEntity::getVersionNo, Comparator.reverseOrder())
                        .thenComparing(MesIdentifierMappingEntity::getId, Comparator.reverseOrder()))
                .toList();
        if (allowed.isEmpty()) {
            if (!scoped.isEmpty()) {
                throw new CheckedException("标识映射已登记，但其类型未被当前执行模板允许");
            }
            if (!active.isEmpty()) {
                throw new CheckedException("生产标识映射与当前工单、物料、工序、工位或对象类型不匹配");
            }
            if (!published.isEmpty()) {
                throw new CheckedException("生产标识映射尚未生效或已经过期");
            }
            if (!governed.isEmpty()) {
                throw new CheckedException("生产标识映射已经退役，禁止继续使用原别名");
            }
            if ("MAPPING_REQUIRED".equals(resolveMode)) {
                throw new CheckedException("当前执行模板要求先建立并发布标识映射");
            }
            applyCanonical(command, submitted, objectType, resolveMode);
            return;
        }
        MesIdentifierMappingEntity selected = allowed.get(0);
        if (selected.getMaxUseCount() != null
                && Objects.requireNonNullElse(selected.getUsedCount(), 0) >= selected.getMaxUseCount()) {
            throw new CheckedException("生产标识映射已达到最大使用次数");
        }
        command.setTransferCode(selected.getCanonicalCode());
        command.setIdentifierMappingId(selected.getId());
        command.setIdentifierType(selected.getIdentifierType());
        command.setIdentifierResolutionSnapshot(snapshot(submitted, selected.getCanonicalCode(), objectType,
                resolveMode, selected));
    }

    @Override
    public void recordSuccessfulUse(MesTaskReportEntity report, MesTaskReportVO command) {
        if (command.getIdentifierMappingId() == null) {
            return;
        }
        MesIdentifierMappingEntity mapping = getOne(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesIdentifierMappingEntity::getId, command.getIdentifierMappingId())
                .eq(MesIdentifierMappingEntity::getFactoryCode, report.getFactoryCode()).last("FOR UPDATE"));
        if (mapping == null || !PUBLISHED.equals(mapping.getStatus())
                || !isEffective(mapping, LocalDateTime.now())) {
            throw new CheckedException("报工使用的标识映射已不存在或不再有效");
        }
        int used = Objects.requireNonNullElse(mapping.getUsedCount(), 0);
        if (mapping.getMaxUseCount() != null && used >= mapping.getMaxUseCount()) {
            throw new CheckedException("生产标识映射已达到最大使用次数");
        }
        mapping.setUsedCount(used + 1);
        if (!updateById(mapping)) {
            throw new CheckedException("生产标识映射使用次数写入失败");
        }
    }

    private void applyCanonical(MesTaskReportVO command, String submitted, String objectType,
                                String resolveMode) {
        command.setTransferCode(submitted);
        command.setIdentifierType("CANONICAL");
        command.setIdentifierResolutionSnapshot(snapshot(submitted, submitted, objectType, resolveMode, null));
    }

    private String snapshot(String submitted, String canonical, String objectType, String resolveMode,
                            MesIdentifierMappingEntity mapping) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", "MES_IDENTIFIER_RESOLUTION_V1");
        value.put("submittedIdentifier", submitted);
        value.put("canonicalIdentifier", canonical);
        value.put("objectType", objectType);
        value.put("resolveMode", resolveMode);
        value.put("identifierType", mapping == null ? "CANONICAL" : mapping.getIdentifierType());
        if (mapping != null) {
            value.put("mappingId", mapping.getId());
            value.put("mappingCode", mapping.getMappingCode());
            value.put("mappingVersion", mapping.getVersionNo());
            value.put("scope", Map.of(
                    "materialCode", StrUtil.nullToEmpty(mapping.getMaterialCode()),
                    "taskCode", StrUtil.nullToEmpty(mapping.getTaskCode()),
                    "routeCode", StrUtil.nullToEmpty(mapping.getRouteCode()),
                    "worksiteCode", StrUtil.nullToEmpty(mapping.getWorksiteCode())));
        }
        return JSONUtil.toJsonStr(value);
    }

    private boolean matches(MesIdentifierMappingEntity mapping, MesTaskArrangeEntity arrange,
                            MesTaskEntity task, String objectType) {
        return ("ANY".equals(mapping.getObjectType()) || objectType.equals(mapping.getObjectType()))
                && match(mapping.getMaterialCode(), arrange.getMatCode())
                && match(mapping.getTaskCode(), task.getCode())
                && match(mapping.getRouteCode(), arrange.getRouteCode())
                && (StrUtil.isBlank(mapping.getWorksiteCode())
                || split(arrange.getWorksiteCodes()).contains(mapping.getWorksiteCode()));
    }

    private boolean match(String expected, String actual) {
        return StrUtil.isBlank(expected) || Objects.equals(expected, actual);
    }

    private boolean isEffective(MesIdentifierMappingEntity mapping, LocalDateTime now) {
        return (mapping.getEffectiveFrom() == null || !mapping.getEffectiveFrom().isAfter(now))
                && (mapping.getEffectiveTo() == null || mapping.getEffectiveTo().isAfter(now));
    }

    private int specificity(MesIdentifierMappingEntity mapping) {
        return (StrUtil.isNotBlank(mapping.getMaterialCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(mapping.getTaskCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(mapping.getRouteCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(mapping.getWorksiteCode()) ? 1 : 0);
    }

    private String objectType(ReportModeEnum mode) {
        return switch (mode) {
            case ONE_CODE -> "UNIT";
            case QUANTITY -> "BATCH";
            case TRANSFER_CARD -> "TRAVELER";
            case PACKAGE_ONE_CODE, PACKAGE_QUANTITY -> "PACKAGE";
        };
    }

    private void normalizeAndValidate(MesIdentifierMappingEntity mapping) {
        if (mapping == null || StrUtil.isBlank(mapping.getMappingCode())
                || StrUtil.isBlank(mapping.getMappingName())) {
            throw new CheckedException("映射编码和名称不能为空");
        }
        mapping.setMappingCode(mapping.getMappingCode().trim())
                .setMappingName(mapping.getMappingName().trim())
                .setAliasCode(required(mapping.getAliasCode(), 200, "输入标识"))
                .setCanonicalCode(required(mapping.getCanonicalCode(), 200, "规范标识"))
                .setIdentifierType(required(mapping.getIdentifierType(), 30, "标识类型").toUpperCase())
                .setObjectType(required(mapping.getObjectType(), 30, "对象类型").toUpperCase())
                .setMaterialCode(trimToNull(mapping.getMaterialCode()))
                .setTaskCode(trimToNull(mapping.getTaskCode()))
                .setRouteCode(trimToNull(mapping.getRouteCode()))
                .setWorksiteCode(trimToNull(mapping.getWorksiteCode()))
                .setPriority(Objects.requireNonNullElse(mapping.getPriority(), 0))
                .setUsedCount(Objects.requireNonNullElse(mapping.getUsedCount(), 0))
                .setRemark(trimToNull(mapping.getRemark()));
        if (!IDENTIFIER_TYPES.contains(mapping.getIdentifierType())) {
            throw new CheckedException("不支持的标识类型");
        }
        if (!OBJECT_TYPES.contains(mapping.getObjectType())) {
            throw new CheckedException("不支持的标识对象类型");
        }
        if (Objects.equals(mapping.getAliasCode(), mapping.getCanonicalCode())) {
            throw new CheckedException("输入标识与规范标识相同，无需建立映射");
        }
        if (mapping.getPriority() < 0 || mapping.getPriority() > 9999) {
            throw new CheckedException("映射优先级必须在0至9999之间");
        }
        if (mapping.getMaxUseCount() != null && mapping.getMaxUseCount() <= 0) {
            throw new CheckedException("最大使用次数必须大于0");
        }
        if (mapping.getEffectiveFrom() != null && mapping.getEffectiveTo() != null
                && !mapping.getEffectiveTo().isAfter(mapping.getEffectiveFrom())) {
            throw new CheckedException("映射失效时间必须晚于生效时间");
        }
        if ("TEMPORARY".equals(mapping.getIdentifierType())
                && (mapping.getEffectiveTo() == null || mapping.getMaxUseCount() == null)) {
            throw new CheckedException("临时码必须同时配置失效时间和最大使用次数");
        }
    }

    private void assertNoAmbiguousMapping(MesIdentifierMappingEntity mapping, String factory) {
        boolean conflict = list(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesIdentifierMappingEntity::getFactoryCode, factory)
                .eq(MesIdentifierMappingEntity::getAliasCode, mapping.getAliasCode())
                .eq(MesIdentifierMappingEntity::getStatus, PUBLISHED)
                .ne(MesIdentifierMappingEntity::getId, mapping.getId()))
                .stream().anyMatch(existing -> Objects.equals(existing.getPriority(), mapping.getPriority())
                        && Objects.equals(existing.getObjectType(), mapping.getObjectType())
                        && Objects.equals(existing.getMaterialCode(), mapping.getMaterialCode())
                        && Objects.equals(existing.getTaskCode(), mapping.getTaskCode())
                        && Objects.equals(existing.getRouteCode(), mapping.getRouteCode())
                        && Objects.equals(existing.getWorksiteCode(), mapping.getWorksiteCode())
                        && overlaps(existing, mapping));
        if (conflict) {
            throw new CheckedException("存在相同输入标识、作用域和优先级且有效期重叠的已发布映射");
        }
    }

    private boolean overlaps(MesIdentifierMappingEntity first, MesIdentifierMappingEntity second) {
        LocalDateTime firstStart = Objects.requireNonNullElse(first.getEffectiveFrom(), LocalDateTime.MIN);
        LocalDateTime firstEnd = Objects.requireNonNullElse(first.getEffectiveTo(), LocalDateTime.MAX);
        LocalDateTime secondStart = Objects.requireNonNullElse(second.getEffectiveFrom(), LocalDateTime.MIN);
        LocalDateTime secondEnd = Objects.requireNonNullElse(second.getEffectiveTo(), LocalDateTime.MAX);
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
    }

    private MesIdentifierMappingEntity locked(Long id, String factory) {
        return getOne(new LambdaQueryWrapper<MesIdentifierMappingEntity>()
                .eq(MesIdentifierMappingEntity::getId, id)
                .eq(MesIdentifierMappingEntity::getFactoryCode, factory).last("FOR UPDATE"));
    }

    private String required(String value, int maxLength, String name) {
        if (StrUtil.isBlank(value)) {
            throw new CheckedException(name + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new CheckedException(name + "长度不能超过" + maxLength + "位");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private List<String> split(String value) {
        return Arrays.stream(StrUtil.blankToDefault(value, "").split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }
}
