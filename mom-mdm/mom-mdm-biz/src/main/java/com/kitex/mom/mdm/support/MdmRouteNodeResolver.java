package com.kitex.mom.mdm.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.mapper.MdmProcessRouteMapper;
import com.kitex.mom.mdm.mapper.MdmProcessRouteWorkingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工程配置的路线节点归属解析。主工序编码用于能力匹配，不能代替本次路线节点身份。
 * 写命令必须在事务中先锁真实父路线，再读取节点，避免与节点删除互相穿透。
 * 只依赖Mapper，避免路线、工装等业务Service之间产生循环依赖。
 */
@Component
@RequiredArgsConstructor
public class MdmRouteNodeResolver {

    private final MdmProcessRouteMapper routeMapper;
    private final MdmProcessRouteWorkingMapper workingMapper;

    /**
     * 按稳定关联ID解析；加锁后再次核实，不能将删除后重加的节点视为原节点。
     */
    public MdmProcessRouteWorkingEntity byId(Long id, boolean lock) {
        require(id != null && id > 0, "请选择已保存的工艺路线节点");
        List<MdmProcessRouteWorkingEntity> rows = workingMapper.selectList(scopedNodes()
                .eq(MdmProcessRouteWorkingEntity::getId, id));
        require(rows.size() == 1, "路线节点不存在或不属于当前租户/工厂");
        MdmProcessRouteWorkingEntity before = rows.get(0);
        MdmProcessRouteWorkingEntity node = resolve(before.getRouteCode(), before.getRouteNodeCode(),
                before.getProcessCode(), lock);
        require(Objects.equals(node.getId(), id), "路线节点身份已变化，请刷新后操作");
        return node;
    }

    /**
     * 新调用指定节点码，旧调用仅在主工序恰好对应一个节点时桥接。
     * 同时传主工序和节点码时必须一致，不能忽略矛盾身份。
     */
    public MdmProcessRouteWorkingEntity resolve(String routeCode, String routeNodeCode,
                                                String processCode, boolean lock) {
        require(StrUtil.isNotBlank(routeCode), "工艺路线编码不能为空");
        require(StrUtil.isNotBlank(routeNodeCode) || StrUtil.isNotBlank(processCode), "路线节点或主工序编码不能为空");
        requireParent(routeCode, lock);
        LambdaQueryWrapper<MdmProcessRouteWorkingEntity> query = scopedNodes()
                .eq(MdmProcessRouteWorkingEntity::getRouteCode, routeCode);
        if (StrUtil.isNotBlank(routeNodeCode)) {
            query.eq(MdmProcessRouteWorkingEntity::getRouteNodeCode, routeNodeCode);
        } else {
            query.eq(MdmProcessRouteWorkingEntity::getProcessCode, processCode);
        }
        List<MdmProcessRouteWorkingEntity> rows = workingMapper.selectList(query.last("LIMIT 2"));
        require(rows.size() == 1, "路线节点未唯一归属当前租户/工厂，请使用明确的节点编号");
        MdmProcessRouteWorkingEntity node = rows.get(0);
        require(StrUtil.isNotBlank(node.getRouteNodeCode()), "路线节点身份尚未升级，请先执行数据库升级");
        require(StrUtil.isBlank(processCode) || Objects.equals(processCode, node.getProcessCode()),
                "路线节点与主工序编码不一致");
        return node;
    }

    /**
     * 批量只读解析。旧请求的路线/工序数组是集合过滤，不代表每个路线都有全部工序。
     * 精确节点请求必须全部存在于所给路线；旧请求按实际路线工序对检测歧义。
     */
    public List<MdmProcessRouteWorkingEntity> resolveBatch(List<String> routeCodes, List<String> nodeCodes,
                                                           List<String> processCodes) {
        List<String> routes = codes(routeCodes, 200, "工艺路线");
        List<String> nodes = codes(nodeCodes, 2000, "路线节点");
        List<String> processes = codes(processCodes, 2000, "主工序");
        require(!routes.isEmpty() && (!nodes.isEmpty() || !processes.isEmpty()), "批量查询必须指定路线及节点或主工序");
        for (String route : routes) {
            requireParent(route, false);
        }
        LambdaQueryWrapper<MdmProcessRouteWorkingEntity> query = scopedNodes()
                .in(MdmProcessRouteWorkingEntity::getRouteCode, routes);
        if (nodes.isEmpty()) {
            query.in(MdmProcessRouteWorkingEntity::getProcessCode, processes);
        } else {
            query.in(MdmProcessRouteWorkingEntity::getRouteNodeCode, nodes);
        }
        List<MdmProcessRouteWorkingEntity> rows = workingMapper.selectList(query
                .orderByAsc(MdmProcessRouteWorkingEntity::getRouteCode)
                .orderByAsc(MdmProcessRouteWorkingEntity::getOrderNum)
                .orderByAsc(MdmProcessRouteWorkingEntity::getId));
        require(rows.stream().allMatch(row -> StrUtil.isNotBlank(row.getRouteNodeCode())), "路线节点身份尚未升级");
        if (!nodes.isEmpty()) {
            Set<String> found = rows.stream().map(MdmProcessRouteWorkingEntity::getRouteNodeCode).collect(Collectors.toSet());
            require(found.size() == nodes.size() && rows.size() == nodes.size(), "部分节点未唯一归属指定路线及当前工厂");
            require(processes.isEmpty() || rows.stream().allMatch(row -> processes.contains(row.getProcessCode())),
                    "指定节点不属于所给主工序集合");
        } else {
            Map<String, Map<String, List<MdmProcessRouteWorkingEntity>>> grouped = rows.stream()
                    .collect(Collectors.groupingBy(MdmProcessRouteWorkingEntity::getRouteCode,
                            Collectors.groupingBy(MdmProcessRouteWorkingEntity::getProcessCode)));
            boolean unique = grouped.values().stream().flatMap(byProcess -> byProcess.values().stream())
                    .allMatch(matches -> matches.size() == 1);
            require(unique, "同一路线存在重复主工序，请改用明确的路线节点查询");
        }
        return rows;
    }

    private void requireParent(String routeCode, boolean lock) {
        require(!lock || TransactionSynchronizationManager.isActualTransactionActive(), "路线配置加锁必须处于业务事务中");
        String factory = currentFactory();
        List<MdmProcessRouteEntity> rows = routeMapper.selectList(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                .eq(MdmProcessRouteEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MdmProcessRouteEntity::getFactoryCode, factory)
                .eq(MdmProcessRouteEntity::getRouteCode, routeCode)
                .last(lock ? "LIMIT 2 FOR UPDATE" : "LIMIT 2"));
        require(rows.size() == 1, "工艺路线未唯一归属当前租户/工厂");
    }

    private LambdaQueryWrapper<MdmProcessRouteWorkingEntity> scopedNodes() {
        String factory = currentFactory();
        return new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                .eq(MdmProcessRouteWorkingEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MdmProcessRouteWorkingEntity::getFactoryCode, factory);
    }

    private String currentFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        require(TenantContextHolder.getTenantId() != null && TenantContextHolder.getTenantId() > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory),
                "路线节点操作需要明确当前租户和工厂");
        return factory;
    }

    private List<String> codes(List<String> values, int limit, String label) {
        if (values == null) {
            return List.of();
        }
        require(values.size() <= limit && values.stream().allMatch(StrUtil::isNotBlank),
                label + "集合不可含空编码且不能超过" + limit + "项");
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
