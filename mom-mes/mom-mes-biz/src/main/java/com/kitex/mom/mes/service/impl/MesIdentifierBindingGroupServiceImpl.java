package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.entity.MesIdentifierBindingGroupEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesIdentifierBindingGroupMapper;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 真实派工事务中的永久区段冻结及查询。
 *
 * <p>业务锁先于派工查重；完整工单图由本服务读取，不信任调用方给出的已选节点。
 * 历史派工只读核对，不锁历史派工行、不更新旧策略，避免引入派工与报工的反向行锁。</p>
 */
@Service
@RequiredArgsConstructor
public class MesIdentifierBindingGroupServiceImpl
        extends ServiceImpl<MesIdentifierBindingGroupMapper, MesIdentifierBindingGroupEntity>
        implements MesIdentifierBindingGroupService {

    private static final ObjectMapper SNAPSHOT_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskMapper taskMapper;
    private final MesProduceTaskMapper produceTaskMapper;
    private final MesIdentifierNodePolicyValidator policyValidator;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockDispatchTask(String taskCode) {
        String factory = factoryAccess.require("write");
        require(StrUtil.isNotBlank(taskCode) && taskCode.length() <= 100
                && taskCode.chars().noneMatch(Character::isISOControl), "派工工单号缺失或格式不正确");
        // JSON数组避免命名空间或编码含分隔符时串锁；Long先转文本再进入Hutool快照。
        String key = JSONUtil.toJsonStr(List.of("MES_NODE_TASK_LOCK_V1", tenant().toString(), factory, taskCode));
        require(Integer.valueOf(1).equals(baseMapper.lockTaskBusinessKey(key)), "未取得派工工单业务锁");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesIdentifierNodePolicyDTO freezeForDispatch(MesTaskEntity task, MesTaskArrangeEntity arrange,
                                                       MesIdentifierNodePolicyDTO policy) {
        String factory = factoryAccess.require("write");
        require(task != null && task.getId() != null && arrange != null && arrange.getId() == null
                        && policy != null, "永久区段只允许冻结新派工，不能重写历史派工");
        require(Objects.equals(task.getTenantId(), tenant()) && Objects.equals(task.getFactoryCode(), factory)
                        && Objects.equals(arrange.getTenantId(), tenant())
                        && Objects.equals(arrange.getFactoryCode(), factory)
                        && Objects.equals(arrange.getTaskCode(), task.getCode()), "派工与工单必须属于当前实际租户工厂");
        lockDispatchTask(task.getCode());
        MesTaskEntity actualTask = taskByCode(task.getCode(), factory);
        require(Objects.equals(actualTask.getId(), task.getId())
                && Objects.equals(actualTask.getProcessRouteSnapshot(), task.getProcessRouteSnapshot()),
                "工单身份或原路线来源已变化，请重新派工");
        List<MesIdentifierBindingGroupEntity> groups = groups(actualTask.getId(), factory);
        if ("NONE".equals(policy.getAction())) {
            require(groups.stream().noneMatch(group -> regionTasks(group).contains(arrange.getProduceTaskCode())),
                    "此工序已属于永久换码区段，不能改用普通策略绕过绑定，请选择同组节点模板");
            return new MesIdentifierNodePolicyDTO();
        }

        List<MesProduceTaskEntity> nodes = produceTaskMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, tenant())
                .eq(MesProduceTaskEntity::getFactoryCode, factory)
                .eq(MesProduceTaskEntity::getTaskCode, actualTask.getCode())
                .orderByAsc(MesProduceTaskEntity::getRouteCode).orderByAsc(MesProduceTaskEntity::getId)
                .last("LIMIT 2001"));
        var facts = policyValidator.freeze(policy, actualTask, arrange, nodes);
        Set<String> region = new HashSet<>(policy.getFrozenNodes().stream()
                .map(MesIdentifierNodePolicyDTO.Node::getProduceTaskCode).toList());
        MesIdentifierBindingGroupEntity existing = null;
        for (MesIdentifierBindingGroupEntity group : groups) {
            if (Objects.equals(group.getBindingGroupCode(), policy.getBindingGroupCode())
                    && Objects.equals(group.getExternalNamespace(), policy.getExternalNamespace())) {
                require(existing == null, "永久区段存在重复身份，请先核查原账");
                existing = group;
            } else {
                require(regionTasks(group).stream().noneMatch(region::contains),
                        "同一工序不能同时属于多个换码区段；请按真实工艺配置不重叠区段");
            }
        }
        if (existing != null) {
            assertSameGroup(existing, actualTask, facts);
            attachIdentity(policy, existing);
        }
        // 包括逻辑删除行，历史NONE/其他组不能被首次新定义反向解释；不会获取历史派工行锁。
        List<MesTaskArrangeEntity> history = baseMapper.selectTaskArrangementHistory(tenant(), factory, actualTask.getCode());
        require(history.size() <= 5000, "历史派工超过完整核对上限，禁止截断后冻结区段");
        for (MesTaskArrangeEntity old : history) {
            if (region.contains(old.getProduceTaskCode())) {
                require(existing != null, "区段已有历史派工，不能将历史NONE或其他策略补造成新换码区段");
                assertHistoricalContract(old, existing, policy);
            }
        }
        if (existing == null) {
            existing = appendGroup(actualTask, facts, factory);
            attachIdentity(policy, existing);
        }
        return policy;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MesIdentifierBindingGroupEntity> taskGroups(Long taskId) {
        String factory = factoryAccess.require("read");
        require(taskId != null, "请选择精确生产工单");
        MesTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, taskId).eq(MesTaskEntity::getTenantId, tenant())
                .eq(MesTaskEntity::getFactoryCode, factory));
        require(task != null, "当前实际租户工厂未找到该工单");
        require(Objects.equals(taskByCode(task.getCode(), factory).getId(), taskId), "工单编码归属不唯一");
        return groups(taskId, factory);
    }

    @Override
    @Transactional(readOnly = true)
    public MesIdentifierBindingGroupEntity evidence(Long groupId) {
        String factory = factoryAccess.require("read");
        require(groupId != null, "请选择精确永久区段");
        MesIdentifierBindingGroupEntity group = baseMapper.selectOne(scope(factory)
                .eq(MesIdentifierBindingGroupEntity::getId, groupId));
        require(group != null, "当前实际租户工厂未找到该永久区段");
        return group;
    }

    /** 原永久账不更新；动作不参与组定义指纹，三个角色仍必须共享全部输入规则。 */
    private void assertSameGroup(MesIdentifierBindingGroupEntity group, MesTaskEntity task,
                                 MesIdentifierNodePolicyValidator.FrozenGroupFacts facts) {
        var policy = facts.policy();
        require(group.getId() != null && Integer.valueOf(1).equals(group.getVersionNo())
                        && "FROZEN".equals(group.getStatus()) && "ONE_CODE".equals(group.getReportMode())
                        && Objects.equals(group.getPolicySchemaVersion(), policy.getPolicySchemaVersion())
                        && Objects.equals(group.getProcessRouteCode(), policy.getProcessRouteCode())
                        && Objects.equals(group.getBindRouteNodeCode(), policy.getBindRouteNodeCode())
                        && Objects.equals(group.getReleaseRouteNodeCode(), policy.getReleaseRouteNodeCode())
                        && Objects.equals(group.getTaskCode(), task.getCode())
                        && Objects.equals(group.getDefinitionFingerprint(), policy.getDefinitionFingerprint())
                        && Objects.equals(group.getGraphFingerprint(), policy.getGraphFingerprint())
                        && Objects.equals(group.getSourceRouteFingerprint(), policy.getSourceRouteFingerprint())
                        && Objects.equals(group.getDefinitionSnapshot(), facts.definitionSnapshot())
                        && Objects.equals(group.getGraphSnapshot(), facts.graphSnapshot())
                        && Objects.equals(group.getRegionSnapshot(), JSONUtil.toJsonStr(policy.getFrozenNodes()))
                        && Objects.equals(group.getSourceRouteSnapshot(), facts.sourceRouteSnapshot()),
                "工单永久区段定义、实际工序图或原路线已变化，禁止覆盖；须核查原工单并另行受控处理");
    }

    /** 历史快照的角色、具体节点、组ID/版本及全部依据都必须仍指向原账。 */
    private void assertHistoricalContract(MesTaskArrangeEntity old, MesIdentifierBindingGroupEntity group,
                                          MesIdentifierNodePolicyDTO current) {
        JsonNode contract = json(old.getExecutionPolicySnapshot(), "区段历史派工缺少完整冻结契约");
        JsonNode node = contract.get("identifierNodePolicy");
        String role = MesIdentifierNodePolicyValidator.role(current, old);
        var actualNode = current.getFrozenNodes().stream().filter(item -> item.getProduceTaskCode().equals(old.getProduceTaskCode())
                        && item.getRouteCode().equals(old.getRouteCode())
                        && (!MesIdentifierNodePolicyValidator.nativePolicy(current)
                        || Objects.equals(item.getSourceRouteNodeCode(), old.getSourceRouteNodeCode())))
                .findFirst().orElseThrow(() -> new CheckedException("历史派工不在冻结区段图内"));
        require(node != null && node.isObject() && role != null && role.equals(node.path("action").asText())
                        && "ONE_CODE".equals(old.getReportMode())
                        && "ONE_CODE".equals(contract.path("reportMode").asText())
                        && Objects.equals(old.getProduceTaskCode(), actualNode.getProduceTaskCode())
                        && group.getId().toString().equals(node.path("bindingGroupId").asText())
                        && node.path("bindingGroupVersion").isIntegralNumber()
                        && group.getVersionNo().equals(node.path("bindingGroupVersion").intValue())
                        && group.getDefinitionFingerprint().equals(node.path("definitionFingerprint").asText())
                        && group.getGraphFingerprint().equals(node.path("graphFingerprint").asText())
                        && group.getSourceRouteFingerprint().equals(node.path("sourceRouteFingerprint").asText())
                        && definitionMatches(group, node)
                        && json(group.getRegionSnapshot(), "永久区段节点依据已损坏").equals(node.path("frozenNodes")),
                "历史派工未冻结同一永久区段或具体节点，不可用新模板补齐/覆盖旧事实");
    }

    /** 指纹不是允许改公开字段的令牌；历史输入规则也须逐字段匹配原定义。 */
    private boolean definitionMatches(MesIdentifierBindingGroupEntity group, JsonNode node) {
        JsonNode definition = json(group.getDefinitionSnapshot(), "永久区段公开定义依据已损坏");
        var fields = definition.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode actual = node.path(field.getKey());
            if (!(field.getValue().isNull() && (actual.isNull() || actual.isMissingNode()))
                    && !field.getValue().equals(actual)) {
                return false;
            }
        }
        return definition.isObject();
    }

    /** 只在派工事务中追加一次；SQL提交约束还要求同事务存在引用本组的正式派工。 */
    private MesIdentifierBindingGroupEntity appendGroup(MesTaskEntity task,
                                                        MesIdentifierNodePolicyValidator.FrozenGroupFacts facts,
                                                        String factory) {
        var policy = facts.policy();
        var user = SecurityUtils.getUser();
        require(StrUtil.isNotBlank(user.getUsername()), "区段冻结需要明确实名账号");
        LocalDateTime now = LocalDateTime.now();
        MesIdentifierBindingGroupEntity group = new MesIdentifierBindingGroupEntity()
                .setFactoryCode(factory).setTaskId(task.getId()).setTaskCode(task.getCode())
                .setBindingGroupCode(policy.getBindingGroupCode()).setExternalNamespace(policy.getExternalNamespace())
                .setVersionNo(1).setStatus("FROZEN").setReportMode("ONE_CODE")
                .setBindRouteCode(policy.getBindRouteCode()).setReleaseRouteCode(policy.getReleaseRouteCode())
                .setDefinitionSnapshot(facts.definitionSnapshot()).setGraphSnapshot(facts.graphSnapshot())
                .setRegionSnapshot(JSONUtil.toJsonStr(policy.getFrozenNodes()))
                .setSourceRouteSnapshot(facts.sourceRouteSnapshot())
                .setDefinitionFingerprint(policy.getDefinitionFingerprint()).setGraphFingerprint(policy.getGraphFingerprint())
                .setSourceRouteFingerprint(policy.getSourceRouteFingerprint()).setFrozenById(user.getId())
                .setFrozenByCode(user.getUsername()).setFrozenByName(user.getName()).setFrozenTime(now);
        if (MesIdentifierNodePolicyValidator.nativePolicy(policy)) {
            group.setPolicySchemaVersion(policy.getPolicySchemaVersion()).setProcessRouteCode(policy.getProcessRouteCode())
                    .setBindRouteNodeCode(policy.getBindRouteNodeCode()).setReleaseRouteNodeCode(policy.getReleaseRouteNodeCode())
                    .setBindRouteCode(masterAt(policy, policy.getBindRouteNodeCode()))
                    .setReleaseRouteCode(masterAt(policy, policy.getReleaseRouteNodeCode()));
        }
        group.setTenantId(tenant());
        group.setCreateBy(user.getUsername());
        group.setCreateTime(now);
        group.setDelFlag("0");
        require(baseMapper.insert(group) == 1 && group.getId() != null, "永久区段冻结保存失败");
        return group;
    }

    private void attachIdentity(MesIdentifierNodePolicyDTO policy, MesIdentifierBindingGroupEntity group) {
        policy.setBindingGroupId(group.getId().toString()).setBindingGroupVersion(group.getVersionNo());
    }

    private Set<String> regionTasks(MesIdentifierBindingGroupEntity group) {
        JsonNode region = json(group.getRegionSnapshot(), "永久区段节点依据已损坏");
        require(region.isArray() && !region.isEmpty() && region.size() <= 2000, "永久区段节点依据缺失或超限");
        Set<String> routes = new HashSet<>();
        for (JsonNode node : region) {
            require(node.isObject() && node.path("produceTaskCode").isTextual()
                    && StrUtil.isNotBlank(node.path("produceTaskCode").textValue())
                    && routes.add(node.path("produceTaskCode").textValue()), "永久区段包含不完整或重复实际任务");
        }
        return routes;
    }

    /** 原生组旧主工序列只留实际描述，不能用其决定角色。 */
    private String masterAt(MesIdentifierNodePolicyDTO policy, String nodeCode) {
        var matches = policy.getFrozenNodes().stream().filter(node -> nodeCode.equals(node.getSourceRouteNodeCode())).toList();
        require(matches.size() == 1, "永久区段绑定/释放节点不唯一");
        return matches.get(0).getRouteCode();
    }

    private List<MesIdentifierBindingGroupEntity> groups(Long taskId, String factory) {
        var groups = baseMapper.selectList(scope(factory).eq(MesIdentifierBindingGroupEntity::getTaskId, taskId)
                .orderByAsc(MesIdentifierBindingGroupEntity::getId).last("LIMIT 2001"));
        require(groups.size() <= 2000, "永久区段超过完整查询上限，禁止截断");
        return groups;
    }

    private LambdaQueryWrapper<MesIdentifierBindingGroupEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesIdentifierBindingGroupEntity>()
                .eq(MesIdentifierBindingGroupEntity::getTenantId, tenant())
                .eq(MesIdentifierBindingGroupEntity::getFactoryCode, factory);
    }

    private MesTaskEntity taskByCode(String taskCode, String factory) {
        var tasks = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, taskCode).last("LIMIT 2"));
        require(tasks.size() == 1, "当前实际组织的生产工单号缺失或不唯一");
        return tasks.get(0);
    }

    private JsonNode json(String raw, String message) {
        require(StrUtil.isNotBlank(raw), message);
        try {
            JsonNode result = SNAPSHOT_JSON.readTree(raw);
            require(result != null && !result.isNull(), message);
            return result;
        } catch (IOException exception) {
            throw new CheckedException(message);
        }
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    /** 禁止从此服务取得Mapper/链式CRUD或不带实际组织的通用查询。内部只使用受保护字段。 */
    @Override
    public MesIdentifierBindingGroupMapper getBaseMapper() {
        throw new CheckedException("永久区段仅允许领域冻结和隔离查询，禁止通用CRUD/Mapper入口");
    }

    /** 批量实现可直接访问SqlSession，须显式封闭而不能只覆盖save/updateById。 */
    @Override
    public boolean saveBatch(Collection<MesIdentifierBindingGroupEntity> entities, int batchSize) {
        throw new CheckedException("永久区段只能在真实派工事务中逐组冻结");
    }

    @Override
    public boolean saveOrUpdateBatch(Collection<MesIdentifierBindingGroupEntity> entities, int batchSize) {
        throw new CheckedException("永久区段禁止批量覆盖或补造");
    }

    @Override
    public boolean updateBatchById(Collection<MesIdentifierBindingGroupEntity> entities, int batchSize) {
        throw new CheckedException("永久区段不可修改");
    }
}
