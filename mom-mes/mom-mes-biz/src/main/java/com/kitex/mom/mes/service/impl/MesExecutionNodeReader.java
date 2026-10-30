package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 读取原工单完整实际执行图。这里只解释冻结身份，不办理生产状态或查询今日工程配置。
 * 使用Mapper避免报工、派工、工序业务服务互相注入形成启动循环。
 */
@Component
@RequiredArgsConstructor
public class MesExecutionNodeReader {
    private final MesProduceTaskMapper tasks;

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    public NodeContext read(MesTaskArrangeEntity arrange) {
        require(arrange != null && arrange.getTaskCode() != null && !arrange.getTaskCode().isBlank(),
                "当前派工或原工单身份缺失，无法核对原执行图");
        var graph = readGraphs(arrange.getTenantId(), arrange.getFactoryCode(), List.of(arrange.getTaskCode()))
                .get(arrange.getTaskCode());
        var current = graph.current(arrange);
        return new NodeContext(current, graph.predecessors(current));
    }

    /**
     * 批量读取完整工单图；不能先按某一主工序/前序子集过滤，否则无法识别歧义和坏图。
     */
    public Map<String, MesFrozenExecutionGraph> readGraphs(Long tenant, String factory, Collection<String> taskCodes) {
        GraphBatch batch = readCandidates(tenant, factory, taskCodes);
        require(batch.failures().isEmpty(), "原工单执行图不可用：" + batch.failures());
        return batch.graphs();
    }

    /**
     * 候选按工单批次查询，每批最多1000个工单，但每个工单读取完整实际任务集。
     * 不裁剪工单、不把缺图作为首站；数据库整体读取失败继续抛出，不伪装成无候选。
     */
    public GraphBatch readCandidates(Long tenant, String factory, Collection<String> taskCodes) {
        require(tenant != null && Objects.equals(tenant, TenantContextHolder.getTenantId())
                && factory != null && !factory.isBlank(), "执行图查询必须属于当前租户和明确工厂");
        require(taskCodes != null, "工单执行图查询范围缺失");
        if (taskCodes.isEmpty()) {
            return new GraphBatch(Map.of(), Map.of());
        }
        var selected = new LinkedHashSet<>(taskCodes);
        require(selected.stream().allMatch(code -> code != null && !code.isBlank()), "执行图不能查询空工单编码");
        Map<String, MesFrozenExecutionGraph> graphs = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> ordered = new ArrayList<>(selected);
        for (int offset = 0; offset < ordered.size(); offset += 1000) {
            List<String> batch = ordered.subList(offset, Math.min(offset + 1000, ordered.size()));
            List<MesProduceTaskEntity> rows = tasks.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                    .eq(MesProduceTaskEntity::getTenantId, tenant)
                    .eq(MesProduceTaskEntity::getFactoryCode, factory)
                    .in(MesProduceTaskEntity::getTaskCode, batch)
                    .orderByAsc(MesProduceTaskEntity::getId));
            Map<String, List<MesProduceTaskEntity>> grouped = rows.stream().collect(Collectors.groupingBy(
                    MesProduceTaskEntity::getTaskCode, LinkedHashMap::new, Collectors.toList()));
            for (String taskCode : batch) {
                try {
                    List<MesProduceTaskEntity> members = grouped.get(taskCode);
                    require(members != null && !members.isEmpty(), "没有完整实际工序，禁止推定为首工序");
                    graphs.put(taskCode, MesFrozenExecutionGraph.of(members));
                } catch (IllegalArgumentException | CheckedException exception) {
                    failures.put(taskCode, exception.getMessage());
                }
            }
        }
        return new GraphBatch(Collections.unmodifiableMap(graphs), Collections.unmodifiableMap(failures));
    }

    /**
     * 当前实际父任务及其全部直接前序；实体只用于本次服务器校验，不接受客户端构造。
     */
    public record NodeContext(MesProduceTaskEntity current, List<MesProduceTaskEntity> predecessors) {
    }

    /**
     * 每个工单单独成功或失败；一个坏图不能让其他工单的正常派工全部不可操作。
     */
    public record GraphBatch(Map<String, MesFrozenExecutionGraph> graphs, Map<String, String> failures) {
    }
}
