package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.agent.dto.MdmProcessKnowledgeDTO;
import com.kitex.mom.mdm.agent.service.MdmAgentActualScopeAccess;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmProcessParameterStandardService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO.*;

/**
 * 工艺参数标准版本服务实现。
 */
@Service
@RequiredArgsConstructor
public class MdmProcessParameterStandardServiceImpl
        extends ServiceImpl<MdmProcessParameterStandardMapper, MdmProcessParameterStandardEntity>
        implements MdmProcessParameterStandardService {

    private static final Set<String> DATA_TYPES = Set.of("DECIMAL", "INTEGER");
    private static final Set<String> AGGREGATION_MODES = Set.of("RANGE", "AVERAGE");

    private final MdmProcessParameterItemMapper itemMapper;
    private final MdmProcessParameterStandardLogMapper logMapper;
    private final MdmProcessRouteMapper routeMapper;
    private final MdmProcessRouteWorkingMapper workingMapper;
    private final MdmProcedureMapper procedureMapper;
    private final MdmAgentActualScopeAccess knowledgeScope;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long createDraft(MdmProcessParameterStandardDTO.SaveCommand command) {
        require(command != null, "工艺参数标准不能为空");
        String factory = requireFactory();
        MdmProcessRouteEntity route = requireRoute(command.getProcessRouteId(), factory, true);
        MdmProcessParameterStandardEntity standard = buildDraft(command, route, factory);
        assertVersionUnique(standard);
        require(baseMapper.insert(standard) == 1, "参数标准草稿创建失败");
        List<MdmProcessParameterItemEntity> items = buildItems(command.getItems(), standard, route);
        items.forEach(item -> require(itemMapper.insert(item) == 1, "参数明细保存失败"));
        standard.setItemCount(items.size());
        require(baseMapper.updateById(standard) == 1, "参数标准数量保存失败");
        audit(standard, "CREATE", null, "DRAFT", null, null, snapshot(standard, items));
        return standard.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Boolean updateDraft(MdmProcessParameterStandardDTO.SaveCommand command) {
        if (command == null || command.getId() == null) {
            throw new CheckedException("工艺参数标准ID不能为空");
        }
        MdmProcessParameterStandardEntity current = lockedStandard(command.getId());
        require("DRAFT".equals(current.getStatus()), "只有草稿工艺参数标准可以修改");
        String before = snapshot(current, items(current.getId()));
        require(Objects.equals(current.getProcessRouteId(), command.getProcessRouteId()),
                "已有参数标准不能改绑路线，请在目标路线新建标准");
        MdmProcessRouteEntity route = requireRoute(command.getProcessRouteId(), current.getFactoryCode(), true);
        MdmProcessParameterStandardEntity standard = buildDraft(command, route, current.getFactoryCode());
        standard.setId(current.getId());
        standard.setCreateBy(current.getCreateBy());
        standard.setCreateTime(current.getCreateTime());
        assertVersionUnique(standard);
        List<MdmProcessParameterItemEntity> replacement = buildItems(command.getItems(), standard, route);
        itemMapper.delete(new LambdaQueryWrapper<MdmProcessParameterItemEntity>()
                .eq(MdmProcessParameterItemEntity::getStandardId, standard.getId()));
        standard.setItemCount(replacement.size());
        standard.setContentFingerprint(null);
        // 先移除旧V1草稿明细，再升级表头协议，最后写入新节点明细；整体提交才检查完整性。
        require(baseMapper.updateById(standard) == 1, "参数标准草稿保存失败");
        replacement.forEach(item -> require(itemMapper.insert(item) == 1, "参数明细保存失败"));
        audit(standard, "UPDATE", "DRAFT", "DRAFT", null, before,
                snapshot(standard, replacement));
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Boolean deleteDraft(Long id) {
        MdmProcessParameterStandardEntity standard = lockedStandard(id);
        require("DRAFT".equals(standard.getStatus()), "只有草稿工艺参数标准可以删除");
        List<MdmProcessParameterItemEntity> items = items(id);
        String before = snapshot(standard, items);
        itemMapper.delete(new LambdaQueryWrapper<MdmProcessParameterItemEntity>()
                .eq(MdmProcessParameterItemEntity::getStandardId, id));
        require(baseMapper.deleteById(id) == 1, "参数标准草稿删除失败");
        audit(standard, "DELETE", "DRAFT", null, null, before, null);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long copyVersion(Long id) {
        MdmProcessParameterStandardEntity source = lockedStandard(id);
        List<MdmProcessParameterItemEntity> sourceItems = items(id);
        validateStored(source, sourceItems);
        if (!"DRAFT".equals(source.getStatus())) {
            require(Objects.equals(source.getContentFingerprint(), contentFingerprint(source, sourceItems)),
                    "来源标准指纹不一致，不能复制");
        }
        int nextVersion = baseMapper.selectList(new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                        .eq(MdmProcessParameterStandardEntity::getFactoryCode, source.getFactoryCode())
                        .eq(MdmProcessParameterStandardEntity::getStandardNo, source.getStandardNo()))
                .stream().map(MdmProcessParameterStandardEntity::getVersionNo).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
        require(nextVersion > 0, "标准版本号超出范围，不能继续复制");
        MdmProcessRouteEntity route = requireRoute(source.getProcessRouteId(), source.getFactoryCode(), true);
        MdmProcessParameterStandardDTO.SaveCommand copy = new MdmProcessParameterStandardDTO.SaveCommand()
                .setStandardNo(source.getStandardNo()).setStandardName(source.getStandardName())
                .setVersionNo(nextVersion).setProcessRouteId(source.getProcessRouteId())
                .setRemark(source.getRemark()).setItems(sourceItems.stream().map(this::itemInput).toList());
        MdmProcessParameterStandardEntity target = buildDraft(copy, route, source.getFactoryCode());
        assertVersionUnique(target);
        require(baseMapper.insert(target) == 1, "复制参数标准失败");
        List<MdmProcessParameterItemEntity> copiedItems = buildItems(copy.getItems(), target, route);
        copiedItems.forEach(item -> require(itemMapper.insert(item) == 1, "复制参数明细失败"));
        audit(target, "COPY_VERSION", null, "DRAFT", "来源版本ID=" + id, null,
                snapshot(target, items(target.getId())));
        return target.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Boolean changeStatus(MdmProcessParameterStandardDTO.ActionCommand command) {
        if (command == null || command.getId() == null || StrUtil.isBlank(command.getAction())) {
            throw new CheckedException("工艺参数标准状态操作缺少ID或动作");
        }
        MdmProcessParameterStandardEntity standard = lockedStandard(command.getId());
        String action = command.getAction().trim().toUpperCase(Locale.ROOT);
        String beforeStatus = standard.getStatus();
        String before = snapshot(standard, items(standard.getId()));
        MomUser user = requireUser();
        LocalDateTime now = LocalDateTime.now();
        if ("RELEASE".equals(action)) {
            require("DRAFT".equals(beforeStatus), "只有草稿工艺参数标准可以发布");
            require(NODE_V2.equals(contentSchema(standard)), "旧草稿请先进入编辑，确认节点并保存后发布");
            require(!Objects.equals(StrUtil.trim(standard.getCreateBy()), user.getUsername()),
                    "工艺参数标准编制人与发布人必须岗位分离");
            LocalDateTime effective = command.getEffectiveTime() == null ? now : command.getEffectiveTime();
            require(!effective.isAfter(now), "本版本只允许立即或追溯到当前时刻发布，未来生效请在生效时发布");
            List<MdmProcessParameterItemEntity> standardItems = items(standard.getId());
            validateStored(standard, standardItems);
            validateCurrentNodes(standard, standardItems);
            String fingerprint = contentFingerprint(standard, standardItems);
            List<MdmProcessParameterStandardEntity> active = baseMapper.selectList(
                    new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                            .eq(MdmProcessParameterStandardEntity::getFactoryCode, standard.getFactoryCode())
                            .eq(MdmProcessParameterStandardEntity::getProcessRouteId, standard.getProcessRouteId())
                            .eq(MdmProcessParameterStandardEntity::getStatus, "RELEASED")
                            .ne(MdmProcessParameterStandardEntity::getId, standard.getId())
                            .last("FOR UPDATE"));
            for (MdmProcessParameterStandardEntity previous : active) {
                String previousSnapshot = snapshot(previous, items(previous.getId()));
                previous.setStatus("RETIRED");
                previous.setRetiredBy(user.getUsername());
                previous.setRetiredByName(user.getName());
                previous.setRetiredTime(now);
                previous.setRetireReason("由标准" + standard.getStandardNo() + " V" + standard.getVersionNo() + "发布替代");
                require(baseMapper.updateById(previous) == 1, "原有效参数标准退役失败");
                audit(previous, "AUTO_RETIRE", "RELEASED", "RETIRED", previous.getRetireReason(),
                        previousSnapshot, snapshot(previous, items(previous.getId())));
            }
            standard.setStatus("RELEASED");
            standard.setEffectiveTime(effective);
            standard.setContentFingerprint(fingerprint);
            standard.setItemCount(standardItems.size());
            standard.setReleasedBy(user.getUsername());
            standard.setReleasedByName(user.getName());
            standard.setReleasedTime(now);
            standard.setRetiredBy(null);
            standard.setRetiredByName(null);
            standard.setRetiredTime(null);
            standard.setRetireReason(null);
        } else if ("RETIRE".equals(action)) {
            require("RELEASED".equals(beforeStatus), "只有已发布工艺参数标准可以退役");
            require(StrUtil.isNotBlank(command.getReason()), "退役工艺参数标准必须填写原因");
            standard.setStatus("RETIRED");
            standard.setRetiredBy(user.getUsername());
            standard.setRetiredByName(user.getName());
            standard.setRetiredTime(now);
            standard.setRetireReason(trim(command.getReason(), 500));
        } else {
            throw new CheckedException("不支持的工艺参数标准动作：" + action);
        }
        require(baseMapper.updateById(standard) == 1, "参数标准状态保存失败");
        audit(standard, action, beforeStatus, standard.getStatus(), command.getReason(), before,
                snapshot(standard, items(standard.getId())));
        return true;
    }

    @Override
    public MdmProcessParameterStandardDTO.Detail detail(Long id) {
        MdmProcessParameterStandardEntity standard = requireStandard(id, false);
        return new MdmProcessParameterStandardDTO.Detail()
                .setStandard(standard)
                .setItems(items(id))
                .setLogs(logMapper.selectList(new LambdaQueryWrapper<MdmProcessParameterStandardLogEntity>()
                        .eq(MdmProcessParameterStandardLogEntity::getStandardId, id)
                        .orderByDesc(MdmProcessParameterStandardLogEntity::getOperatedTime)
                        .orderByDesc(MdmProcessParameterStandardLogEntity::getId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MdmProcessParameterStandardDTO.FrozenCatalog resolveReleased(
            MdmProcessParameterStandardDTO.ResolveQuery query) {
        return resolveCatalog(query, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MdmProcessParameterStandardDTO.FrozenCatalog resolveNodes(
            MdmProcessParameterStandardDTO.ResolveQuery query) {
        return resolveCatalog(query, true);
    }

    /**
     * 父路线锁使节点和本次选中的发布版本来自同一可用状态。
     * 历史V1只投影本次返回值，不给原表、原内容指纹或旧工单补造身份。
     */
    private MdmProcessParameterStandardDTO.FrozenCatalog resolveCatalog(
            MdmProcessParameterStandardDTO.ResolveQuery query, boolean nodeProtocol) {
        String factory = requireFactory();
        require(query != null && Objects.equals(factory, StrUtil.trim(query.getFactoryCode())),
                "工艺参数标准解析工厂与当前实际工厂不一致");
        MdmProcessRouteEntity route = requireRoute(query.getProcessRouteId(), factory, true);
        require(Objects.equals(route.getRouteCode(), StrUtil.trim(query.getRouteCode())),
                "工艺参数标准解析的路线ID与编码不一致");
        List<String> requested = nodeProtocol ? query.getRouteNodeCodes() : query.getProcessCodes();
        require(requested != null && !requested.isEmpty() && requested.size() <= 500,
                "工艺参数标准解析必须提供1至500个" + (nodeProtocol ? "路线节点" : "工序编码"));
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String value : requested) {
            String key = requiredText(value, nodeProtocol ? 64 : 100, "解析对象");
            require(keys.add(key), "工艺参数解析对象重复：" + key);
        }
        List<MdmProcessRouteWorkingEntity> workings = routeNodes(route);
        Map<String, String> routeNodeProcesses = new LinkedHashMap<>();
        for (String key : keys) {
            MdmProcessRouteWorkingEntity node = resolveNode(workings, nodeProtocol ? key : null,
                    nodeProtocol ? null : key);
            if (nodeProtocol) {
                routeNodeProcesses.put(node.getRouteNodeCode(), node.getProcessCode());
            }
        }
        LocalDateTime effective = query.getEffectiveTime() == null ? LocalDateTime.now() : query.getEffectiveTime();
        List<MdmProcessParameterStandardEntity> standards = baseMapper.selectList(
                new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                        .eq(MdmProcessParameterStandardEntity::getFactoryCode, factory)
                        .eq(MdmProcessParameterStandardEntity::getProcessRouteId, route.getId())
                        .eq(MdmProcessParameterStandardEntity::getRouteCode, route.getRouteCode())
                        .eq(MdmProcessParameterStandardEntity::getStatus, "RELEASED")
                        .le(MdmProcessParameterStandardEntity::getEffectiveTime, effective)
                        .orderByDesc(MdmProcessParameterStandardEntity::getEffectiveTime)
                        .orderByDesc(MdmProcessParameterStandardEntity::getVersionNo)
                        .last("LIMIT 2"));
        if (standards.isEmpty()) {
            return new MdmProcessParameterStandardDTO.FrozenCatalog()
                    .setSnapshotSchemaVersion(nodeProtocol ? ROUTE_NODE_V2 : null)
                    .setRouteNodeProcesses(routeNodeProcesses)
                    .setAvailable(false)
                    .setProcessRouteId(route.getId())
                    .setRouteCode(route.getRouteCode())
                    .setRouteName(route.getRouteName())
                    .setResolvedAt(LocalDateTime.now())
                    .setDataGaps(List.of("该工艺路线尚未发布生效的工艺参数标准"));
        }
        require(standards.size() == 1, "同一工艺路线存在多个已发布生效参数标准，禁止下达工单");
        MdmProcessParameterStandardEntity standard = standards.get(0);
        String schema = contentSchema(standard);
        require(nodeProtocol || LEGACY_PROCESS_V1.equals(schema),
                "当前标准使用NODE_V2，请升级MES并使用节点冻结接口，禁止降级为主工序标准");
        List<MdmProcessParameterItemEntity> allItems = items(standard.getId());
        validateStored(standard, allItems);
        require(Objects.equals(standard.getContentFingerprint(), contentFingerprint(standard, allItems)),
                "已发布工艺参数标准内容指纹不一致，禁止下达工单");
        List<MdmProcessParameterStandardDTO.FrozenParameter> parameters = new ArrayList<>();
        // 核验整版当前归属，不只核本次命中的子集，避免孤立明细被静默隐藏。
        for (MdmProcessParameterItemEntity item : allItems) {
            MdmProcessRouteWorkingEntity node = resolveNode(workings,
                    NODE_V2.equals(schema) ? item.getRouteNodeCode() : null, item.getProcessCode());
            String key = nodeProtocol ? node.getRouteNodeCode() : node.getProcessCode();
            if (keys.contains(key)) {
                parameters.add(frozen(item).setRouteNodeCode(nodeProtocol ? node.getRouteNodeCode() : null));
            }
        }
        List<String> gaps = keys.stream()
                .filter(key -> parameters.stream().noneMatch(item -> key.equals(nodeProtocol
                        ? item.getRouteNodeCode() : item.getProcessCode())))
                .map(key -> (nodeProtocol ? "节点" : "工序") + key + "未定义受控工艺参数").toList();
        return new MdmProcessParameterStandardDTO.FrozenCatalog()
                .setSnapshotSchemaVersion(nodeProtocol ? ROUTE_NODE_V2 : null)
                .setRouteNodeProcesses(routeNodeProcesses)
                .setContentSchemaVersion(nodeProtocol ? schema : null)
                .setAvailable(true)
                .setStandardId(standard.getId())
                .setStandardNo(standard.getStandardNo())
                .setStandardName(standard.getStandardName())
                .setVersionNo(standard.getVersionNo())
                .setProcessRouteId(standard.getProcessRouteId())
                .setRouteCode(standard.getRouteCode())
                .setRouteName(standard.getRouteName())
                .setEffectiveTime(standard.getEffectiveTime())
                .setReleasedTime(standard.getReleasedTime())
                .setContentFingerprint(standard.getContentFingerprint())
                .setResolvedAt(LocalDateTime.now())
                .setParameters(parameters)
                .setDataGaps(gaps);
    }

    /**
     * 来源发现只返回有界元数据。分页count与记录在同一只读快照中，不读审批日志。
     * 搜索词全部作为SQL参数，固定子查询同时限定租户、工厂及删除标记。
     */
    @Override
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_view')")
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ)
    public MdmProcessKnowledgeDTO.SourcePage searchKnowledgeSources(MdmProcessKnowledgeDTO.SearchQuery query) {
        require(query != null, "知识来源查询不能为空");
        knowledgeScope.require(query.factoryCode(), "read");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = requireFactory();
        int current = query.current() == null ? 1 : query.current();
        int size = query.size() == null ? 20 : query.size();
        require(current >= 1 && current <= 1_000_000 && size >= 1 && size <= 50,
                "知识来源分页须为1至1000000页，每页1至50条");
        String keyword = sourceFilter(query.keyword());
        String routeCode = sourceFilter(query.routeCode());
        String processCode = sourceFilter(query.processCode());
        require(query.terms() == null || query.terms().size() <= 12, "知识来源检索最多12个词");
        List<String> terms = query.terms() == null ? List.of() : query.terms().stream()
                .map(term -> requiredText(term, 100, "知识检索词")).distinct().toList();
        LocalDateTime observedAt = LocalDateTime.now();
        LambdaQueryWrapper<MdmProcessParameterStandardEntity> filter = activeKnowledgeSources(
                tenant, factory, observedAt)
                .eq(routeCode != null, MdmProcessParameterStandardEntity::getRouteCode, routeCode)
                .gt(MdmProcessParameterStandardEntity::getId, 0)
                .gt(MdmProcessParameterStandardEntity::getProcessRouteId, 0)
                .gt(MdmProcessParameterStandardEntity::getVersionNo, 0)
                .isNotNull(MdmProcessParameterStandardEntity::getReleasedTime)
                .gt(MdmProcessParameterStandardEntity::getItemCount, 0)
                .le(MdmProcessParameterStandardEntity::getItemCount, 500)
                .apply("content_fingerprint ~ '^[0-9a-f]{64}$'");
        // 路线身份、当前启用状态及同编码唯一性必须明确，不能沿用停用路线的旧正文。
        filter.apply("EXISTS (SELECT 1 FROM mdm_process_route r WHERE r.tenant_id={0} "
                        + "AND r.factory_code={1} AND r.del_flag='0' AND r.status=true "
                        + "AND r.id=mdm_process_parameter_standard.process_route_id "
                        + "AND r.route_code=mdm_process_parameter_standard.route_code "
                        + "AND NOT EXISTS (SELECT 1 FROM mdm_process_route r2 WHERE r2.tenant_id={0} "
                        + "AND r2.factory_code={1} AND r2.del_flag='0' AND r2.route_code=r.route_code AND r2.id<>r.id))",
                tenant, factory);
        filter.apply("NOT EXISTS (SELECT 1 FROM mdm_process_parameter_standard s2 "
                + "WHERE s2.tenant_id={0} AND s2.factory_code={1} AND s2.del_flag='0' "
                + "AND s2.status='RELEASED' AND s2.effective_time<={2} "
                + "AND s2.id<>mdm_process_parameter_standard.id "
                + "AND (s2.process_route_id=mdm_process_parameter_standard.process_route_id "
                + "OR s2.route_code=mdm_process_parameter_standard.route_code))", tenant, factory, observedAt);
        if (processCode != null) {
            filter.apply("EXISTS (SELECT 1 FROM mdm_process_parameter_item p WHERE p.tenant_id={0} "
                            + "AND p.factory_code={1} AND p.del_flag='0' "
                            + "AND p.standard_id=mdm_process_parameter_standard.id AND p.process_code={2})",
                    tenant, factory, processCode);
        }
        if (keyword != null) {
            filter.and(match -> sourceKeyword(match, keyword, tenant, factory));
        }
        if (!terms.isEmpty()) {
            filter.and(match -> {
                for (String term : terms) {
                    match.or().and(part -> sourceKeyword(part, term, tenant, factory));
                }
            });
        }
        filter.select(MdmProcessParameterStandardEntity::getId, MdmProcessParameterStandardEntity::getTenantId,
                        MdmProcessParameterStandardEntity::getFactoryCode, MdmProcessParameterStandardEntity::getStandardNo,
                        MdmProcessParameterStandardEntity::getStandardName, MdmProcessParameterStandardEntity::getVersionNo,
                        MdmProcessParameterStandardEntity::getProcessRouteId, MdmProcessParameterStandardEntity::getRouteCode,
                        MdmProcessParameterStandardEntity::getRouteName, MdmProcessParameterStandardEntity::getStatus,
                        MdmProcessParameterStandardEntity::getEffectiveTime, MdmProcessParameterStandardEntity::getReleasedTime,
                        MdmProcessParameterStandardEntity::getContentFingerprint, MdmProcessParameterStandardEntity::getItemCount)
                .orderByDesc(MdmProcessParameterStandardEntity::getEffectiveTime)
                .orderByDesc(MdmProcessParameterStandardEntity::getVersionNo)
                .orderByDesc(MdmProcessParameterStandardEntity::getId);
        Page<MdmProcessParameterStandardEntity> page = baseMapper.selectPage(new Page<>(current, size), filter);
        return new MdmProcessKnowledgeDTO.SourcePage(current, size, page.getTotal(), page.getRecords().stream()
                .map(standard -> sourceSummary(standard, observedAt)).toList(), observedAt,
                List.of("目录仅为当前正式来源摘要，使用正文前须核验精确版本及当前路线工序归属"));
    }

    /**
     * 在独立REPEATABLE_READ只读事务中核验指定版本；不锁写行、不修改发布状态。
     * 任何失效或归属不完整均返回空正文，数据库/权限异常则保持失败，不伪装可用来源。
     */
    @Override
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_view')")
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ)
    public MdmProcessKnowledgeDTO.SourceDetail verifyKnowledgeSource(MdmProcessKnowledgeDTO.VerifyQuery query) {
        require(query != null, "知识来源核验不能为空");
        knowledgeScope.require(query.factoryCode(), "read");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = requireFactory();
        Long id = sourceId(query.standardId());
        require(query.versionNo() != null && query.versionNo() > 0
                        && query.expectedFingerprint() != null && query.expectedFingerprint().matches("[0-9a-f]{64}"),
                "知识来源核验须提供精确版本号和64位小写指纹");
        LocalDateTime observedAt = LocalDateTime.now();
        MdmProcessParameterStandardEntity standard = baseMapper.selectOne(
                new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                        .eq(MdmProcessParameterStandardEntity::getTenantId, tenant)
                        .eq(MdmProcessParameterStandardEntity::getFactoryCode, factory)
                        .eq(MdmProcessParameterStandardEntity::getDelFlag, "0")
                        .eq(MdmProcessParameterStandardEntity::getId, id));
        if (standard == null || !"RELEASED".equals(standard.getStatus())
                || standard.getEffectiveTime() == null || standard.getEffectiveTime().isAfter(observedAt)
                || standard.getReleasedTime() == null || standard.getProcessRouteId() == null
                || standard.getProcessRouteId() <= 0 || StrUtil.isBlank(standard.getRouteCode())
                || StrUtil.isBlank(standard.getStandardNo()) || StrUtil.isBlank(standard.getStandardName())
                || !Objects.equals(standard.getVersionNo(), query.versionNo())
                || !Objects.equals(standard.getContentFingerprint(), query.expectedFingerprint())) {
            return unavailableSource(observedAt, "指定正式版本缺失、已失效或版本指纹不匹配，请重新查阅来源目录");
        }
        List<MdmProcessParameterStandardEntity> active = baseMapper.selectList(activeKnowledgeSources(
                tenant, factory, observedAt).and(match -> match
                .eq(MdmProcessParameterStandardEntity::getProcessRouteId, standard.getProcessRouteId())
                .or().eq(MdmProcessParameterStandardEntity::getRouteCode, standard.getRouteCode())).last("LIMIT 2"));
        if (active.size() != 1 || !Objects.equals(active.get(0).getId(), id)) {
            return unavailableSource(observedAt, "路线当前正式标准不唯一，禁止作为知识依据");
        }
        List<MdmProcessRouteEntity> routes = routeMapper.selectList(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                .eq(MdmProcessRouteEntity::getTenantId, tenant).eq(MdmProcessRouteEntity::getFactoryCode, factory)
                .eq(MdmProcessRouteEntity::getDelFlag, "0").eq(MdmProcessRouteEntity::getRouteCode, standard.getRouteCode())
                .last("LIMIT 2"));
        if (routes.size() != 1 || !Objects.equals(routes.get(0).getId(), standard.getProcessRouteId())
                || !Boolean.TRUE.equals(routes.get(0).getStatus())) {
            return unavailableSource(observedAt, "当前路线缺失、停用或身份不唯一，禁止沿用历史结构");
        }
        List<MdmProcessParameterItemEntity> sourceItems = itemMapper.selectList(
                new LambdaQueryWrapper<MdmProcessParameterItemEntity>()
                        .eq(MdmProcessParameterItemEntity::getTenantId, tenant)
                        .eq(MdmProcessParameterItemEntity::getFactoryCode, factory)
                        .eq(MdmProcessParameterItemEntity::getDelFlag, "0")
                        .eq(MdmProcessParameterItemEntity::getStandardId, id)
                        .orderByAsc(MdmProcessParameterItemEntity::getProcessCode)
                        .orderByAsc(MdmProcessParameterItemEntity::getIdentifier)
                        .orderByAsc(MdmProcessParameterItemEntity::getId).last("LIMIT 501"));
        List<MdmProcessRouteWorkingEntity> workings = workingMapper.selectList(
                new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                        .eq(MdmProcessRouteWorkingEntity::getTenantId, tenant)
                        .eq(MdmProcessRouteWorkingEntity::getFactoryCode, factory)
                        .eq(MdmProcessRouteWorkingEntity::getDelFlag, "0")
                        .eq(MdmProcessRouteWorkingEntity::getRouteCode, standard.getRouteCode())
                        .orderByAsc(MdmProcessRouteWorkingEntity::getOrderNum)
                        .orderByAsc(MdmProcessRouteWorkingEntity::getId).last("LIMIT 501"));
        // 只捕获已有业务校验异常，不吞数据库故障；不向调用者泄漏不可信明细中的原文。
        try {
            validateKnowledgeStructure(standard, sourceItems, workings);
            require(Objects.equals(standard.getContentFingerprint(), contentFingerprint(standard, sourceItems)),
                    "正式标准内容指纹不一致");
        } catch (CheckedException exception) {
            return unavailableSource(observedAt, "正式标准明细或当前工序结构不完整、失配或超过500项核验上限");
        }
        Set<String> codes = sourceItems.stream().map(MdmProcessParameterItemEntity::getProcessCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<MdmProcedureEntity> procedures = procedureMapper.selectList(new LambdaQueryWrapper<MdmProcedureEntity>()
                .eq(MdmProcedureEntity::getTenantId, tenant).eq(MdmProcedureEntity::getFactoryCode, factory)
                .eq(MdmProcedureEntity::getDelFlag, "0").in(MdmProcedureEntity::getProcessCode, codes).last("LIMIT 501"));
        Map<String, Long> counts = procedures.stream().collect(Collectors.groupingBy(
                MdmProcedureEntity::getProcessCode, Collectors.counting()));
        if (procedures.size() > 500 || codes.stream().anyMatch(code -> counts.getOrDefault(code, 0L) != 1L)
                || procedures.stream().anyMatch(procedure -> !Boolean.TRUE.equals(procedure.getStatus()))) {
            return unavailableSource(observedAt, "引用工序在当前工厂缺失、停用或编码不唯一");
        }
        return verifiedSource(standard, sourceItems, observedAt);
    }

    /**
     * 显式隔离条件仅用于新增知识读取，不改变原业务查询和发布流程。
     */
    private LambdaQueryWrapper<MdmProcessParameterStandardEntity> activeKnowledgeSources(
            Long tenant, String factory, LocalDateTime observedAt) {
        return new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                .eq(MdmProcessParameterStandardEntity::getTenantId, tenant)
                .eq(MdmProcessParameterStandardEntity::getFactoryCode, factory)
                .eq(MdmProcessParameterStandardEntity::getDelFlag, "0")
                .eq(MdmProcessParameterStandardEntity::getStatus, "RELEASED")
                .le(MdmProcessParameterStandardEntity::getEffectiveTime, observedAt);
    }

    /**
     * 固定字段的字面包含检索；参数名/工序名可发现所属标准，但分页不返回参数正文。
     */
    private void sourceKeyword(LambdaQueryWrapper<MdmProcessParameterStandardEntity> filter,
                               String term, Long tenant, String factory) {
        filter.apply("(POSITION(LOWER({0}) IN LOWER(standard_no))>0 "
                + "OR POSITION(LOWER({0}) IN LOWER(standard_name))>0 "
                + "OR POSITION(LOWER({0}) IN LOWER(route_code))>0 "
                + "OR POSITION(LOWER({0}) IN LOWER(route_name))>0 "
                + "OR EXISTS (SELECT 1 FROM mdm_process_parameter_item p "
                + "WHERE p.tenant_id={1} AND p.factory_code={2} AND p.del_flag='0' "
                + "AND p.standard_id=mdm_process_parameter_standard.id "
                + "AND (POSITION(LOWER({0}) IN LOWER(p.process_code))>0 "
                + "OR POSITION(LOWER({0}) IN LOWER(p.process_name))>0 "
                + "OR POSITION(LOWER({0}) IN LOWER(p.identifier))>0 "
                + "OR POSITION(LOWER({0}) IN LOWER(p.parameter_name))>0)))", term, tenant, factory);
    }

    private String sourceFilter(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        return requiredText(value, 100, "知识来源筛选条件");
    }

    private Long sourceId(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "知识来源ID须为十进制字符串");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new CheckedException("知识来源ID超出合法范围");
        }
    }

    /**
     * 复用正式版本明细归属/指纹校验，并验证当前路线内工序的唯一身份。
     */
    private void validateKnowledgeStructure(MdmProcessParameterStandardEntity standard,
                                            List<MdmProcessParameterItemEntity> sourceItems,
                                            List<MdmProcessRouteWorkingEntity> workings) {
        require(sourceItems.size() <= 500 && !workings.isEmpty() && workings.size() <= 500,
                "来源规模或工序结构不可核验");
        validateStored(standard, sourceItems);
        Map<String, MdmProcessRouteWorkingEntity> processIndex = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingEntity working : workings) {
            require(StrUtil.isNotBlank(working.getProcessCode())
                            && processIndex.putIfAbsent(working.getProcessCode(), working) == null,
                    "路线内工序编码缺失或重复");
        }
        Set<Long> itemIds = new HashSet<>();
        for (MdmProcessParameterItemEntity item : sourceItems) {
            require(item.getId() != null && item.getId() > 0 && itemIds.add(item.getId())
                            && Objects.equals(item.getTenantId(), standard.getTenantId())
                            && processIndex.containsKey(item.getProcessCode())
                            && (!NODE_V2.equals(contentSchema(standard)) || Objects.equals(item.getRouteNodeCode(),
                            processIndex.get(item.getProcessCode()).getRouteNodeCode()))
                            && StrUtil.isNotBlank(item.getProcessName())
                            && Objects.equals(item.getProcessName(), processIndex.get(item.getProcessCode()).getProcessName())
                            && StrUtil.isNotBlank(item.getParameterName())
                            && item.getIdentifier() != null && item.getIdentifier().matches("[A-Za-z][A-Za-z0-9_.:-]{0,99}")
                            && item.getDataType() != null && DATA_TYPES.contains(item.getDataType())
                            && item.getAggregationMode() != null && AGGREGATION_MODES.contains(item.getAggregationMode())
                            && item.getRequired() != null && item.getMinimumSampleCount() != null
                            && item.getMaximumNonGoodRatePct() != null && item.getContinuousDeviationSeconds() != null,
                    "参数身份、规则或当前工序归属不完整");
            MdmProcessParameterStandardDTO.ItemInput input = new MdmProcessParameterStandardDTO.ItemInput();
            BeanUtils.copyProperties(item, input);
            validateLimits(input);
        }
    }

    private MdmProcessKnowledgeDTO.SourceSummary sourceSummary(
            MdmProcessParameterStandardEntity standard, LocalDateTime observedAt) {
        return new MdmProcessKnowledgeDTO.SourceSummary("MDM", "PROCESS_PARAMETER_STANDARD",
                String.valueOf(standard.getTenantId()), standard.getFactoryCode(), String.valueOf(standard.getId()),
                standard.getStandardNo(), standard.getStandardName(), standard.getVersionNo(),
                String.valueOf(standard.getProcessRouteId()), standard.getRouteCode(), standard.getRouteName(),
                "RELEASED", standard.getEffectiveTime(), standard.getReleasedTime(), standard.getContentFingerprint(),
                standard.getItemCount(), observedAt);
    }

    private MdmProcessKnowledgeDTO.SourceDetail unavailableSource(LocalDateTime observedAt, String gap) {
        return new MdmProcessKnowledgeDTO.SourceDetail(false, null, List.of(), List.of(), List.of(), List.of(),
                List.of(gap), observedAt);
    }

    /**
     * 只投影核验成功的原数值，toPlainString不经过浮点数。
     */
    private MdmProcessKnowledgeDTO.SourceParameter sourceParameter(MdmProcessParameterItemEntity item) {
        return new MdmProcessKnowledgeDTO.SourceParameter(String.valueOf(item.getId()), item.getProcessCode(),
                item.getProcessName(), item.getIdentifier(), item.getParameterName(), item.getDataType(), item.getUnit(),
                sourceDecimal(item.getTargetValue()), sourceDecimal(item.getLowerLimit()), sourceDecimal(item.getUpperLimit()),
                sourceDecimal(item.getWarningLowerLimit()), sourceDecimal(item.getWarningUpperLimit()), item.getRequired(),
                item.getAggregationMode(), item.getMinimumSampleCount(), sourceDecimal(item.getMaximumNonGoodRatePct()),
                item.getContinuousDeviationSeconds());
    }

    private String sourceDecimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * 关系来自已核验的结构，不由模型编造；引用不可跨租户、工厂或标准版本复用。
     */
    private MdmProcessKnowledgeDTO.SourceDetail verifiedSource(MdmProcessParameterStandardEntity standard,
                                                               List<MdmProcessParameterItemEntity> sourceItems,
                                                               LocalDateTime observedAt) {
        String reference = "MDM:PROCESS_PARAMETER_STANDARD:tenant/" + standard.getTenantId()
                + "/factory/" + sourceCode(standard.getFactoryCode()) + "/standard/" + standard.getId()
                + "/version/" + standard.getVersionNo() + "/fingerprint/" + standard.getContentFingerprint();
        String routeNode = reference + "/route/" + standard.getProcessRouteId();
        List<MdmProcessKnowledgeDTO.SourceNode> nodes = new ArrayList<>();
        List<MdmProcessKnowledgeDTO.SourceRelation> relations = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        refs.add(reference);
        nodes.add(new MdmProcessKnowledgeDTO.SourceNode(routeNode, "PROCESS_ROUTE", standard.getRouteName(),
                String.valueOf(standard.getProcessRouteId()), standard.getRouteCode()));
        nodes.add(new MdmProcessKnowledgeDTO.SourceNode(reference, "PROCESS_STANDARD", standard.getStandardName(),
                String.valueOf(standard.getId()), standard.getStandardNo()));
        relations.add(new MdmProcessKnowledgeDTO.SourceRelation(reference, routeNode, "STANDARD_APPLIES_TO_ROUTE", reference));
        Set<String> processCodes = new LinkedHashSet<>();
        for (MdmProcessParameterItemEntity item : sourceItems) {
            String processNode = reference + "/process/" + sourceCode(item.getProcessCode());
            String itemReference = reference + "/parameter/" + item.getId();
            if (processCodes.add(item.getProcessCode())) {
                nodes.add(new MdmProcessKnowledgeDTO.SourceNode(processNode, "PROCESS", item.getProcessName(), null,
                        item.getProcessCode()));
                relations.add(new MdmProcessKnowledgeDTO.SourceRelation(routeNode, processNode, "HAS_PROCESS", reference));
            }
            nodes.add(new MdmProcessKnowledgeDTO.SourceNode(itemReference, "PROCESS_PARAMETER", item.getParameterName(),
                    String.valueOf(item.getId()), item.getIdentifier()));
            relations.add(new MdmProcessKnowledgeDTO.SourceRelation(reference, itemReference,
                    "STANDARD_CONTROLS_PARAMETER", itemReference));
            relations.add(new MdmProcessKnowledgeDTO.SourceRelation(processNode, itemReference,
                    "PROCESS_HAS_PARAMETER", itemReference));
            refs.add(itemReference);
        }
        return new MdmProcessKnowledgeDTO.SourceDetail(true, sourceSummary(standard, observedAt),
                sourceItems.stream().map(this::sourceParameter).toList(), nodes, relations, refs, List.of(), observedAt);
    }

    private String sourceCode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private MdmProcessParameterStandardEntity buildDraft(
            MdmProcessParameterStandardDTO.SaveCommand command,
            MdmProcessRouteEntity route, String factory) {
        require(command != null, "工艺参数标准不能为空");
        require(command.getContentSchemaVersion() == null || NODE_V2.equals(command.getContentSchemaVersion()),
                "不支持的参数标准内容协议，请升级页面");
        if (NODE_V2.equals(command.getContentSchemaVersion())) {
            require(command.getItems() != null && command.getItems().stream()
                            .allMatch(item -> item != null && StrUtil.isNotBlank(item.getRouteNodeCode())),
                    "NODE_V2参数明细必须指定真实路线节点，不能降级为工序匹配");
        }
        require(command.getVersionNo() != null && command.getVersionNo() > 0,
                "工艺参数标准版本号必须大于0");
        MdmProcessParameterStandardEntity standard = new MdmProcessParameterStandardEntity();
        standard.setStandardNo(requiredText(command.getStandardNo(), 100, "标准编码"));
        standard.setStandardName(requiredText(command.getStandardName(), 200, "标准名称"));
        standard.setVersionNo(command.getVersionNo());
        standard.setContentSchemaVersion(NODE_V2);
        standard.setTenantId(TenantContextHolder.getTenantId());
        standard.setProcessRouteId(route.getId());
        standard.setRouteCode(route.getRouteCode());
        standard.setRouteName(route.getRouteName());
        standard.setStatus("DRAFT");
        standard.setItemCount(command.getItems() == null ? 0 : command.getItems().size());
        standard.setRemark(trim(command.getRemark(), 500));
        standard.setFactoryCode(factory);
        return standard;
    }

    private List<MdmProcessParameterItemEntity> buildItems(
            List<MdmProcessParameterStandardDTO.ItemInput> inputs,
            MdmProcessParameterStandardEntity standard, MdmProcessRouteEntity route) {
        require(inputs != null && !inputs.isEmpty(), "工艺参数标准至少需要一项参数");
        require(inputs.size() <= 500, "单个工艺参数标准最多维护500项参数");
        List<MdmProcessRouteWorkingEntity> workings = routeNodes(route);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        List<MdmProcessParameterItemEntity> result = new ArrayList<>();
        for (MdmProcessParameterStandardDTO.ItemInput input : inputs) {
            require(input != null, "工艺参数明细不能为空");
            String processCode = requiredText(input.getProcessCode(), 100, "工序编码");
            MdmProcessRouteWorkingEntity process = resolveNode(workings, input.getRouteNodeCode(), processCode);
            String identifier = requiredText(input.getIdentifier(), 100, "IoT属性标识符");
            require(identifier.matches("[A-Za-z][A-Za-z0-9_.:-]{0,99}"),
                    "IoT属性标识符格式不合法：" + identifier);
            require(unique.add(process.getRouteNodeCode() + "|" + identifier),
                    "同一路线节点存在重复参数标识符：" + process.getRouteNodeCode() + "/" + identifier);
            String dataType = upper(input.getDataType());
            require(DATA_TYPES.contains(dataType), "工艺参数数据类型只支持DECIMAL或INTEGER");
            String aggregation = StrUtil.isBlank(input.getAggregationMode())
                    ? "RANGE" : upper(input.getAggregationMode());
            require(AGGREGATION_MODES.contains(aggregation), "参数聚合判定只支持RANGE或AVERAGE");
            validateLimits(input);
            MdmProcessParameterItemEntity item = new MdmProcessParameterItemEntity();
            item.setStandardId(standard.getId());
            item.setStandardNo(standard.getStandardNo());
            item.setStandardVersionNo(standard.getVersionNo());
            item.setRouteCode(standard.getRouteCode());
            item.setRouteNodeCode(process.getRouteNodeCode());
            item.setTenantId(standard.getTenantId());
            item.setProcessCode(processCode);
            item.setProcessName(process.getProcessName());
            item.setIdentifier(identifier);
            item.setParameterName(requiredText(input.getParameterName(), 200, "参数名称"));
            item.setDataType(dataType);
            item.setUnit(trim(input.getUnit(), 50));
            item.setTargetValue(input.getTargetValue());
            item.setLowerLimit(input.getLowerLimit());
            item.setUpperLimit(input.getUpperLimit());
            item.setWarningLowerLimit(input.getWarningLowerLimit());
            item.setWarningUpperLimit(input.getWarningUpperLimit());
            item.setRequired(!Boolean.FALSE.equals(input.getRequired()));
            item.setAggregationMode(aggregation);
            item.setMinimumSampleCount(input.getMinimumSampleCount() == null ? 1 : input.getMinimumSampleCount());
            item.setMaximumNonGoodRatePct(input.getMaximumNonGoodRatePct() == null
                    ? BigDecimal.ZERO : input.getMaximumNonGoodRatePct());
            item.setContinuousDeviationSeconds(input.getContinuousDeviationSeconds() == null
                    ? 0 : input.getContinuousDeviationSeconds());
            item.setRemark(trim(input.getRemark(), 500));
            item.setFactoryCode(standard.getFactoryCode());
            result.add(item);
        }
        return result;
    }

    private void validateLimits(MdmProcessParameterStandardDTO.ItemInput input) {
        require(input.getLowerLimit() != null || input.getUpperLimit() != null,
                "每个工艺参数至少需要维护一个控制下限或上限");
        require(input.getLowerLimit() == null || input.getUpperLimit() == null
                        || input.getLowerLimit().compareTo(input.getUpperLimit()) <= 0,
                "工艺参数控制下限不能大于上限");
        require(input.getTargetValue() == null || input.getLowerLimit() == null
                        || input.getTargetValue().compareTo(input.getLowerLimit()) >= 0,
                "工艺参数目标值不能低于控制下限");
        require(input.getTargetValue() == null || input.getUpperLimit() == null
                        || input.getTargetValue().compareTo(input.getUpperLimit()) <= 0,
                "工艺参数目标值不能高于控制上限");
        require(input.getWarningLowerLimit() == null || input.getLowerLimit() == null
                        || input.getWarningLowerLimit().compareTo(input.getLowerLimit()) >= 0,
                "预警下限不能低于控制下限");
        require(input.getWarningUpperLimit() == null || input.getUpperLimit() == null
                        || input.getWarningUpperLimit().compareTo(input.getUpperLimit()) <= 0,
                "预警上限不能高于控制上限");
        require(input.getWarningLowerLimit() == null || input.getWarningUpperLimit() == null
                        || input.getWarningLowerLimit().compareTo(input.getWarningUpperLimit()) <= 0,
                "预警下限不能大于预警上限");
        int samples = input.getMinimumSampleCount() == null ? 1 : input.getMinimumSampleCount();
        require(samples >= 1 && samples <= 1_000_000, "最少GOOD数值采样数必须在1到1000000之间");
        BigDecimal nonGood = input.getMaximumNonGoodRatePct() == null
                ? BigDecimal.ZERO : input.getMaximumNonGoodRatePct();
        require(nonGood.compareTo(BigDecimal.ZERO) >= 0
                        && nonGood.compareTo(BigDecimal.valueOf(100)) <= 0,
                "允许非GOOD采样占比必须在0到100之间");
        int seconds = input.getContinuousDeviationSeconds() == null
                ? 0 : input.getContinuousDeviationSeconds();
        require(seconds >= 0 && seconds <= 86400, "连续越界秒数必须在0到86400之间");
    }

    private void validateStored(MdmProcessParameterStandardEntity standard,
                                List<MdmProcessParameterItemEntity> standardItems) {
        String schema = contentSchema(standard);
        require(standardItems != null && !standardItems.isEmpty() && standardItems.size() <= 500,
                "工艺参数标准明细必须为1至500项");
        require(Objects.equals(standard.getItemCount(), standardItems.size()),
                "工艺参数标准表头数量与明细不一致");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (MdmProcessParameterItemEntity item : standardItems) {
            require(Objects.equals(item.getStandardId(), standard.getId())
                            && Objects.equals(item.getStandardNo(), standard.getStandardNo())
                            && Objects.equals(item.getStandardVersionNo(), standard.getVersionNo())
                            && Objects.equals(item.getRouteCode(), standard.getRouteCode())
                            && Objects.equals(item.getTenantId(), standard.getTenantId())
                            && Objects.equals(item.getFactoryCode(), standard.getFactoryCode()),
                    "工艺参数标准明细归属与表头不一致");
            require(StrUtil.isNotBlank(item.getProcessCode()) && StrUtil.isNotBlank(item.getIdentifier()),
                    "工艺参数标准缺少主工序或标识符");
            require(NODE_V2.equals(schema) ? item.getRouteNodeCode() != null
                            && item.getRouteNodeCode().matches("RN-[1-9][0-9]*") : item.getRouteNodeCode() == null,
                    "参数明细节点与内容协议不一致，禁止猜测归属");
            String key = NODE_V2.equals(schema) ? item.getRouteNodeCode() : item.getProcessCode();
            require(unique.add(key + "|" + item.getIdentifier()), "工艺参数标准存在重复节点/标识符");
        }
    }

    /**
     * DTO只含业务字段，复制不会沿用历史明细ID、删除标记或审计字段。
     */
    private MdmProcessParameterStandardDTO.ItemInput itemInput(MdmProcessParameterItemEntity source) {
        MdmProcessParameterStandardDTO.ItemInput result = new MdmProcessParameterStandardDTO.ItemInput();
        BeanUtils.copyProperties(source, result);
        return result;
    }

    private String contentSchema(MdmProcessParameterStandardEntity standard) {
        String schema = standard.getContentSchemaVersion();
        require(schema == null || LEGACY_PROCESS_V1.equals(schema) || NODE_V2.equals(schema),
                "不支持的工艺参数内容协议，请升级服务");
        return schema == null ? LEGACY_PROCESS_V1 : schema;
    }

    /**
     * 发布/新冻结前核实当前节点；已退役原文查看不执行当前路线重绑定。
     */
    private void validateCurrentNodes(MdmProcessParameterStandardEntity standard,
                                      List<MdmProcessParameterItemEntity> standardItems) {
        MdmProcessRouteEntity route = requireRoute(standard.getProcessRouteId(), standard.getFactoryCode(), true);
        require(Objects.equals(route.getRouteCode(), standard.getRouteCode()), "参数标准路线身份不一致");
        List<MdmProcessRouteWorkingEntity> workings = routeNodes(route);
        for (MdmProcessParameterItemEntity item : standardItems) {
            resolveNode(workings, item.getRouteNodeCode(), item.getProcessCode());
            validateLimits(itemInput(item));
        }
    }

    /**
     * 调用方已锁真实父路线，一次加载所有节点，避免每项参数独立查询导致窗口不一致。
     */
    private List<MdmProcessRouteWorkingEntity> routeNodes(MdmProcessRouteEntity route) {
        List<MdmProcessRouteWorkingEntity> workings = workingMapper.selectList(
                new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                        .eq(MdmProcessRouteWorkingEntity::getTenantId, route.getTenantId())
                        .eq(MdmProcessRouteWorkingEntity::getFactoryCode, route.getFactoryCode())
                        .eq(MdmProcessRouteWorkingEntity::getRouteCode, route.getRouteCode())
                        .orderByAsc(MdmProcessRouteWorkingEntity::getId));
        require(!workings.isEmpty() && workings.size() <= 500, "工艺路线节点必须为1至500项");
        Set<String> identities = new HashSet<>();
        for (MdmProcessRouteWorkingEntity node : workings) {
            require(node.getId() != null && Objects.equals(node.getRouteNodeCode(), "RN-" + node.getId())
                            && identities.add(node.getRouteNodeCode()) && StrUtil.isNotBlank(node.getProcessCode()),
                    "路线节点身份缺失或重复，请先完成节点升级");
        }
        return workings;
    }

    /**
     * 精确节点优先；旧process-only必须恰好匹配一项，禁止first-win。
     */
    private MdmProcessRouteWorkingEntity resolveNode(List<MdmProcessRouteWorkingEntity> workings,
                                                     String nodeCode, String processCode) {
        require(StrUtil.isNotBlank(nodeCode) || StrUtil.isNotBlank(processCode), "缺少路线节点或主工序编码");
        List<MdmProcessRouteWorkingEntity> matches = workings.stream().filter(node -> StrUtil.isNotBlank(nodeCode)
                ? Objects.equals(node.getRouteNodeCode(), nodeCode)
                : Objects.equals(node.getProcessCode(), processCode)).toList();
        require(matches.size() == 1, "路线节点未唯一归属当前标准："
                + StrUtil.blankToDefault(nodeCode, processCode) + "，请明确选择真实节点");
        MdmProcessRouteWorkingEntity node = matches.get(0);
        require(StrUtil.isBlank(processCode) || Objects.equals(node.getProcessCode(), processCode),
                "路线节点与主工序不一致：" + nodeCode);
        return node;
    }

    private MdmProcessParameterStandardDTO.FrozenParameter frozen(MdmProcessParameterItemEntity item) {
        return new MdmProcessParameterStandardDTO.FrozenParameter()
                .setSourceItemId(item.getId())
                .setProcessCode(item.getProcessCode())
                .setProcessName(item.getProcessName())
                .setIdentifier(item.getIdentifier())
                .setParameterName(item.getParameterName())
                .setDataType(item.getDataType())
                .setUnit(item.getUnit())
                .setTargetValue(item.getTargetValue())
                .setLowerLimit(item.getLowerLimit())
                .setUpperLimit(item.getUpperLimit())
                .setWarningLowerLimit(item.getWarningLowerLimit())
                .setWarningUpperLimit(item.getWarningUpperLimit())
                .setRequired(item.getRequired())
                .setAggregationMode(item.getAggregationMode())
                .setMinimumSampleCount(item.getMinimumSampleCount())
                .setMaximumNonGoodRatePct(item.getMaximumNonGoodRatePct())
                .setContinuousDeviationSeconds(item.getContinuousDeviationSeconds());
    }

    private MdmProcessRouteEntity requireRoute(Long id, String factory, boolean lock) {
        require(id != null, "工艺路线ID不能为空");
        List<MdmProcessRouteEntity> routes = routeMapper.selectList(
                new LambdaQueryWrapper<MdmProcessRouteEntity>()
                        .eq(MdmProcessRouteEntity::getId, id)
                        .eq(MdmProcessRouteEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MdmProcessRouteEntity::getFactoryCode, factory)
                        .last(lock ? "LIMIT 2 FOR UPDATE" : "LIMIT 2"));
        require(routes.size() == 1, "当前工厂工艺路线不存在或不唯一");
        require(routeMapper.selectCount(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                        .eq(MdmProcessRouteEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MdmProcessRouteEntity::getFactoryCode, factory)
                        .eq(MdmProcessRouteEntity::getRouteCode, routes.get(0).getRouteCode())) == 1,
                "当前工厂路线编码不唯一，不能解析参数节点");
        return routes.get(0);
    }

    /**
     * 所有写操作统一父路线→标准锁顺序，与节点配置/图保存保持一致。
     */
    private MdmProcessParameterStandardEntity lockedStandard(Long id) {
        MdmProcessParameterStandardEntity before = requireStandard(id, false);
        MdmProcessRouteEntity route = requireRoute(before.getProcessRouteId(), before.getFactoryCode(), true);
        MdmProcessParameterStandardEntity locked = requireStandard(id, true);
        require(Objects.equals(locked.getProcessRouteId(), route.getId())
                        && Objects.equals(locked.getRouteCode(), route.getRouteCode()),
                "参数标准路线已变化，请刷新后重试");
        return locked;
    }

    private MdmProcessParameterStandardEntity requireStandard(Long id, boolean lock) {
        String factory = requireFactory();
        require(id != null && id > 0, "工艺参数标准ID不能为空");
        LambdaQueryWrapper<MdmProcessParameterStandardEntity> query =
                new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                        .eq(MdmProcessParameterStandardEntity::getId, id)
                        .eq(MdmProcessParameterStandardEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MdmProcessParameterStandardEntity::getFactoryCode, factory);
        if (lock) query.last("FOR UPDATE");
        MdmProcessParameterStandardEntity standard = baseMapper.selectOne(query, false);
        require(standard != null, "当前工厂未找到工艺参数标准");
        return standard;
    }

    private List<MdmProcessParameterItemEntity> items(Long standardId) {
        return itemMapper.selectList(new LambdaQueryWrapper<MdmProcessParameterItemEntity>()
                .eq(MdmProcessParameterItemEntity::getStandardId, standardId)
                .orderByAsc(MdmProcessParameterItemEntity::getProcessCode)
                .orderByAsc(MdmProcessParameterItemEntity::getIdentifier)
                .orderByAsc(MdmProcessParameterItemEntity::getId));
    }

    private void assertVersionUnique(MdmProcessParameterStandardEntity standard) {
        long count = baseMapper.selectCount(new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                .eq(MdmProcessParameterStandardEntity::getFactoryCode, standard.getFactoryCode())
                .eq(MdmProcessParameterStandardEntity::getStandardNo, standard.getStandardNo())
                .eq(MdmProcessParameterStandardEntity::getVersionNo, standard.getVersionNo())
                .ne(standard.getId() != null, MdmProcessParameterStandardEntity::getId, standard.getId()));
        require(count == 0, "同一标准编码和版本号已存在");
        List<MdmProcessParameterStandardEntity> family = baseMapper.selectList(
                new LambdaQueryWrapper<MdmProcessParameterStandardEntity>()
                        .eq(MdmProcessParameterStandardEntity::getFactoryCode, standard.getFactoryCode())
                        .eq(MdmProcessParameterStandardEntity::getStandardNo, standard.getStandardNo())
                        .ne(standard.getId() != null, MdmProcessParameterStandardEntity::getId, standard.getId()));
        require(family.stream().allMatch(item -> Objects.equals(item.getProcessRouteId(), standard.getProcessRouteId())),
                "同一标准编码不能跨工艺路线复用");
    }

    private String contentFingerprint(MdmProcessParameterStandardEntity standard,
                                      List<MdmProcessParameterItemEntity> standardItems) {
        if (LEGACY_PROCESS_V1.equals(contentSchema(standard))) {
            return legacyContentFingerprint(standard, standardItems);
        }
        List<MdmProcessParameterItemEntity> ordered = standardItems.stream()
                .sorted(Comparator.comparing(MdmProcessParameterItemEntity::getRouteNodeCode)
                        .thenComparing(MdmProcessParameterItemEntity::getIdentifier)).toList();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("contentSchemaVersion", NODE_V2);
        envelope.put("legacyBodyFingerprint", legacyContentFingerprint(standard, ordered));
        envelope.put("nodes", ordered.stream().map(item -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("routeNodeCode", item.getRouteNodeCode());
            node.put("processCode", item.getProcessCode());
            node.put("identifier", item.getIdentifier());
            return node;
        }).toList());
        return sha256(JSONUtil.toJsonStr(envelope));
    }

    /**
     * V1原算法：字段顺序、明细原排序及JSON数值表达不得改变。
     */
    private String legacyContentFingerprint(MdmProcessParameterStandardEntity standard,
                                            List<MdmProcessParameterItemEntity> standardItems) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("standardNo", standard.getStandardNo());
        header.put("versionNo", standard.getVersionNo());
        header.put("processRouteId", standard.getProcessRouteId());
        header.put("routeCode", standard.getRouteCode());
        List<Map<String, Object>> rows = standardItems.stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("processCode", item.getProcessCode());
            row.put("identifier", item.getIdentifier());
            row.put("parameterName", item.getParameterName());
            row.put("dataType", item.getDataType());
            row.put("unit", item.getUnit());
            row.put("targetValue", item.getTargetValue());
            row.put("lowerLimit", item.getLowerLimit());
            row.put("upperLimit", item.getUpperLimit());
            row.put("warningLowerLimit", item.getWarningLowerLimit());
            row.put("warningUpperLimit", item.getWarningUpperLimit());
            row.put("required", item.getRequired());
            row.put("aggregationMode", item.getAggregationMode());
            row.put("minimumSampleCount", item.getMinimumSampleCount());
            row.put("maximumNonGoodRatePct", item.getMaximumNonGoodRatePct());
            row.put("continuousDeviationSeconds", item.getContinuousDeviationSeconds());
            return row;
        }).toList();
        header.put("items", rows);
        return sha256(JSONUtil.toJsonStr(header));
    }

    private String snapshot(MdmProcessParameterStandardEntity standard,
                            List<MdmProcessParameterItemEntity> standardItems) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("standard", standard);
        snapshot.put("items", standardItems == null ? List.of() : standardItems);
        return JSONUtil.toJsonStr(snapshot);
    }

    private void audit(MdmProcessParameterStandardEntity standard, String action,
                       String from, String to, String reason, String before, String after) {
        MomUser user = requireUser();
        MdmProcessParameterStandardLogEntity log = new MdmProcessParameterStandardLogEntity();
        log.setStandardId(standard.getId());
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setReason(trim(reason, 500));
        log.setOperatedBy(user.getUsername());
        log.setOperatedByName(user.getName());
        log.setOperatedTime(LocalDateTime.now());
        log.setBeforeSnapshot(before);
        log.setAfterSnapshot(after);
        log.setFactoryCode(standard.getFactoryCode());
        require(logMapper.insert(log) == 1, "参数标准审计保存失败");
    }

    private String requireFactory() {
        Long tenantId = TenantContextHolder.getTenantId();
        String factory = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        require(tenantId != null && tenantId > 0 && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(factory),
                "工艺参数标准操作须选择实际租户和工厂，不得跳过隔离");
        return factory;
    }

    private MomUser requireUser() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()),
                "工艺参数标准操作须实名登录");
        return user;
    }

    private String requiredText(String value, int maximum, String label) {
        require(StrUtil.isNotBlank(value), label + "不能为空");
        String result = value.trim();
        require(result.length() <= maximum, label + "不能超过" + maximum + "位");
        return result;
    }

    private String trim(String value, int maximum) {
        return StrUtil.isBlank(value) ? null : StrUtil.sub(value.trim(), 0, maximum);
    }

    private String upper(String value) {
        return StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成工艺参数标准指纹", exception);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
