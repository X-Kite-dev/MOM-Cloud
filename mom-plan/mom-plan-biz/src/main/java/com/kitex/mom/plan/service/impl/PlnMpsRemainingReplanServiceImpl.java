package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.plan.dto.PlanResourceLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.dto.PlnMpsRemainingReplanDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnMpsExecutionObservationEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingReplanLinkEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnMpsExecutionObservationMapper;
import com.kitex.mom.plan.mapper.PlnMpsRemainingReplanLinkMapper;
import com.kitex.mom.plan.mapper.PlnResourceReplanPolicyMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.entity.PlnResourceReplanPolicyEntity;
import com.kitex.mom.plan.service.PlnMpsRemainingReplanService;
import com.kitex.mom.plan.service.PlnResourceLocalReplanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 已执行MPS来源的剩余未开工工序评估。
 *
 * <p>原数量、原桶、已执行工序不变。新来源账与局部候选同一PLAN事务提交，响应丢失原号
 * 返回同一情景；正式选择、独立发布、MES应用和安全回退继续由原资源局部案件承担。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnMpsRemainingReplanServiceImpl extends ServiceImpl<PlnMpsRemainingReplanLinkMapper, PlnMpsRemainingReplanLinkEntity>
        implements PlnMpsRemainingReplanService {
    private final PlnMpsExecutionObservationMapper observationMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnResourceReplanPolicyMapper policyMapper;
    private final PlnMpsExecutionEvidenceReader executionReader;
    private final PlnMpsRemainingEvidenceReader remainingReader;
    private final PlnResourceLocalReplanService localReplan;
    private final PlnMpsService mpsService;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlnMpsRemainingReplanDTO.Preview preview(Long observationId, Long lineId, int toleranceMinutes) {
        authorize("read");
        return capture(observationId, lineId, toleranceMinutes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsRemainingReplanDTO.Detail assess(PlnMpsRemainingReplanDTO.Assess command) {
        authorize("write");
        require(command != null && command.getObservationId() != null && command.getLineId() != null
                && command.getToleranceMinutes() != null, "MPS观察、桶及工序容差必填");
        text(command.getRequestId(), 100, "发起永久号");
        text(command.getReason(), 500, "评估原因");
        require(command.getExpectedFingerprint() != null && command.getExpectedFingerprint().matches("[a-f0-9]{64}"), "预览指纹格式无效");
        List<String> selected = command.getSourceRouteNodeCodes() == null ? command.getProcessCodes() : command.getSourceRouteNodeCodes();
        require(selected != null && !selected.isEmpty() && selected.size() <= 1000
                && selected.stream().allMatch(code -> StrUtil.isNotBlank(code) && code.length() <= 100
                && code.equals(code.trim())) && new HashSet<>(selected).size() == selected.size(),
                "须明确选择1至1000个不重复直接偏差工序/节点，不允许空范围自动扩围");
        String factory = executionReader.actualFactory();
        Long tenant = TenantContextHolder.getTenantId();
        String requestFingerprint = PlnMpsExecutionEvidenceReader.hash(JSONUtil.toJsonStr(Arrays.asList(command.getObservationId(),
                command.getLineId(), command.getToleranceMinutes(), command.getExpectedFingerprint(),
                (command.getProcessCodes() == null ? List.of() : command.getProcessCodes().stream().sorted().toList()), command.getReason().trim())));
        if (command.getSourceRouteNodeCodes() != null) {
            requestFingerprint = PlnMpsExecutionEvidenceReader.hash(JSONUtil.toJsonStr(Arrays.asList("MPS_REMAINING_REQUEST_NODE_V2",
                    command.getObservationId(), command.getLineId(), command.getToleranceMinutes(), command.getExpectedFingerprint(),
                    command.getSourceRouteNodeCodes().stream().sorted().toList(),
                    command.getProcessCodes() == null ? null : command.getProcessCodes().stream().sorted().toList(), command.getReason().trim())));
        }
        baseMapper.lockRequest("MPS_REMAINING:" + tenant + ":" + factory + ":" + command.getRequestId());
        var replay = getOne(scope().eq(PlnMpsRemainingReplanLinkEntity::getRequestId, command.getRequestId()));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint), "发起永久号已使用但内容不同");
            return linked(replay);
        }
        var evidence = capture(command.getObservationId(), command.getLineId(), command.getToleranceMinutes());
        require(Boolean.TRUE.equals(evidence.getEligible()) && Objects.equals(evidence.getFingerprint(), command.getExpectedFingerprint()),
                "当前MPS/PLAN/MES或策略证据不可评估或已变化，请重新预览");
        require(command.getSourceRouteNodeCodes() == null ? evidence.getEligibleProcessCodes().containsAll(selected)
                        : evidence.getEligibleSourceRouteNodeCodes() != null && evidence.getEligibleSourceRouteNodeCodes().containsAll(selected),
                "指定范围包含已接收/执行、现场阻断、缺数据或非当前根计划工序");
        // 固定派生号跨响应丢失/本地回滚保持一致；不依赖尚未提交的雪花主键。
        String coreRequest = "MPSR-" + PlnMpsExecutionEvidenceReader.hash(tenant + ":" + factory + ":" + command.getRequestId()).substring(0, 40);
        var proof = remainingReader.read(Long.valueOf(evidence.getIssueId()), Long.valueOf(evidence.getSourceScenarioId()), command.getToleranceMinutes());
        var core = new PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand();
        core.setSourceScenarioId(Long.valueOf(evidence.getSourceScenarioId()));
        core.setIssueId(Long.valueOf(evidence.getIssueId()));
        core.setRequestId(coreRequest);
        core.setExpectedEffectFingerprint(proof.effect().getEffectFingerprint());
        core.setProcessCodes(command.getProcessCodes() == null ? null : command.getProcessCodes().stream().sorted().toList());
        core.setSourceRouteNodeCodes(command.getSourceRouteNodeCodes() == null ? null : command.getSourceRouteNodeCodes().stream().sorted().toList());
        core.setReason(command.getReason().trim());
        require(Objects.equals(evidence.getExecutionSnapshot(), proof.snapshot()), "发起过程中MES事实变化，不能混用两次采集");
        var scenario = localReplan.assessMpsRemaining(core, command.getToleranceMinutes());
        require(scenario != null && scenario.getScenario() != null && scenario.getCandidates() != null
                && scenario.getCandidates().size() == 3, "剩余工序评估没有返回完整三候选");
        var link = new PlnMpsRemainingReplanLinkEntity().setRequestId(command.getRequestId()).setRequestFingerprint(requestFingerprint)
                .setObservationId(command.getObservationId()).setMpsLineId(command.getLineId())
                .setIssueId(core.getIssueId()).setIssueNo(evidence.getIssueNo()).setSourceScenarioId(core.getSourceScenarioId())
                .setReplanScenarioId(scenario.getScenario().getId()).setEvidenceFingerprint(evidence.getFingerprint())
                .setEvidenceSnapshot(JSONUtil.toJsonStr(evidence)).setReason(command.getReason().trim()).setFactoryCode(factory);
        link.setTenantId(tenant);
        link.setCreateBy(SecurityUtils.getUser().getUsername());
        link.setCreateTime(LocalDateTime.now());
        require(save(link), "MPS剩余重排来源绑定保存失败");
        return new PlnMpsRemainingReplanDTO.Detail().setLink(link).setScenario(scenario);
    }

    @Override
    public PlnMpsRemainingReplanDTO.Detail findRequest(String requestId) {
        authorize("read");
        text(requestId, 100, "发起永久号");
        var link = getOne(scope().eq(PlnMpsRemainingReplanLinkEntity::getRequestId, requestId));
        return link == null ? null : linked(link);
    }

    @Override
    public List<PlnMpsRemainingReplanLinkEntity> history(Long observationId, Long lineId) {
        authorize("read");
        require(observationId != null && lineId != null, "观察及桶主键必填");
        var rows = list(scope().eq(PlnMpsRemainingReplanLinkEntity::getObservationId, observationId)
                .eq(PlnMpsRemainingReplanLinkEntity::getMpsLineId, lineId)
                .select(PlnMpsRemainingReplanLinkEntity.class, field -> !"evidenceSnapshot".equals(field.getProperty()))
                .orderByDesc(PlnMpsRemainingReplanLinkEntity::getId).last("LIMIT 201"));
        require(rows.size() <= 200, "该观察/桶来源绑定超过200条，禁止截断后当完整历史");
        return rows;
    }

    @Override
    public PlnMpsRemainingReplanDTO.Detail detail(Long id) {
        authorize("read");
        require(id != null, "来源绑定主键必填");
        var link = getOne(scope().eq(PlnMpsRemainingReplanLinkEntity::getId, id));
        require(link != null, "当前实际租户/工厂未找到MPS剩余重排来源");
        return linked(link);
    }

    private PlnMpsRemainingReplanDTO.Preview capture(Long observationId, Long lineId, int tolerance) {
        require(observationId != null && lineId != null && tolerance >= 0 && tolerance <= 1440, "观察、桶及0至1440分钟工序容差必填");
        String factory = executionReader.actualFactory();
        Long tenant = TenantContextHolder.getTenantId();
        var observation = observationMapper.selectOne(new LambdaQueryWrapper<PlnMpsExecutionObservationEntity>()
                .eq(PlnMpsExecutionObservationEntity::getId, observationId).eq(PlnMpsExecutionObservationEntity::getTenantId, tenant)
                .eq(PlnMpsExecutionObservationEntity::getFactoryCode, factory));
        require(observation != null && "CONFIRMED".equals(observation.getStatus()), "先完成该MPS原始观察的独立核验");
        List<String> gaps = new ArrayList<>();
        Duration age = Duration.between(observation.getObservedTime(), LocalDateTime.now());
        if (age.isNegative() || age.compareTo(Duration.ofMinutes(30)) > 0) gaps.add("原观察已超过30分钟有效期，重新采集并独立核验");
        var current = executionReader.read(observation.getVersionId(), observation.getToleranceMinutes());
        var bucket = current.getBuckets().stream().filter(row -> Objects.equals(row.getLineId(), lineId.toString())).findFirst()
                .orElseThrow(() -> new CheckedException("指定桶不属于该观察来源版本"));
        if (!Objects.equals(current.getFingerprint(), observation.getEvidenceFingerprint())) gaps.add("原观察事实已漂移，重新采集并核验，不覆盖旧观察");
        if (!Boolean.TRUE.equals(bucket.getActive()) || !Boolean.TRUE.equals(bucket.getStarted()) || Boolean.TRUE.equals(bucket.getCompleted()))
            gaps.add("仅处理当前活动且已进入执行、尚未全量完工的来源；未下达仍走原编制入口");
        gaps.addAll(bucket.getGaps());
        gaps.addAll(bucket.getQuantityGaps());
        var preview = new PlnMpsRemainingReplanDTO.Preview().setObservationId(observationId.toString()).setLineId(lineId.toString())
                .setFactoryCode(factory).setBucket(bucket).setIssueId(bucket.getRootIssueId()).setIssueNo(bucket.getRootIssueNo())
                .setOperations(List.of()).setEligibleProcessCodes(List.of()).setGaps(gaps).setToleranceMinutes(tolerance);
        if (StrUtil.isNotBlank(bucket.getRootIssueId())) {
            var issue = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>().eq(PlnIssueEntity::getId, bucket.getRootIssueId())
                    .eq(PlnIssueEntity::getTenantId, tenant).eq(PlnIssueEntity::getFactoryCode, factory));
            PlnSchedulingTaskEntity task = issue == null || issue.getLockTaskId() == null ? null : taskMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingTaskEntity>().eq(PlnSchedulingTaskEntity::getId, issue.getLockTaskId())
                            .eq(PlnSchedulingTaskEntity::getTenantId, tenant).eq(PlnSchedulingTaskEntity::getFactoryCode, factory));
            if (task == null || task.getScenarioId() == null) gaps.add("缺已发布正式多候选来源情景，旧单方案不能猜来源");
            else {
                preview.setSourceScenarioId(task.getScenarioId().toString());
                try {
                    var proof = remainingReader.read(issue.getId(), task.getScenarioId(), tolerance);
                    preview.setExecutionSnapshot(proof.snapshot()).setOperations(proof.operations());
                    gaps.addAll(proof.gaps());
                    preview.setEligibleProcessCodes(proof.operations().stream().filter(row -> Boolean.TRUE.equals(row.getEligible()))
                            .filter(row -> proof.operations().stream().filter(other -> Objects.equals(row.getProcessCode(), other.getProcessCode())).count() == 1)
                            .map(PlnMpsRemainingReplanDTO.Operation::getProcessCode).sorted().toList());
                    if (proof.operations().stream().anyMatch(row -> StrUtil.isNotBlank(row.getSourceRouteNodeCode()))) {
                        preview.setEligibleSourceRouteNodeCodes(proof.operations().stream().filter(row -> Boolean.TRUE.equals(row.getEligible()))
                                .map(PlnMpsRemainingReplanDTO.Operation::getSourceRouteNodeCode).sorted().toList());
                    }
                } catch (CheckedException exception) { gaps.add(StrUtil.subPre(exception.getMessage(), 500)); }
            }
        }
        if (preview.getEligibleProcessCodes().isEmpty() && (preview.getEligibleSourceRouteNodeCodes() == null || preview.getEligibleSourceRouteNodeCodes().isEmpty())) gaps.add("当前无具备完整来源及前序证明的未开工偏差工序");
        LocalDateTime now = LocalDateTime.now();
        var policies = policyMapper.selectList(new LambdaQueryWrapper<PlnResourceReplanPolicyEntity>()
                .eq(PlnResourceReplanPolicyEntity::getTenantId, tenant).eq(PlnResourceReplanPolicyEntity::getFactoryCode, factory)
                .eq(PlnResourceReplanPolicyEntity::getStatus, "ACTIVE").in(PlnResourceReplanPolicyEntity::getResourceType, "EXECUTION", "ALL")
                .and(q -> q.isNull(PlnResourceReplanPolicyEntity::getEffectiveFrom).or().le(PlnResourceReplanPolicyEntity::getEffectiveFrom, now))
                .and(q -> q.isNull(PlnResourceReplanPolicyEntity::getEffectiveTo).or().gt(PlnResourceReplanPolicyEntity::getEffectiveTo, now))
                .orderByAsc(PlnResourceReplanPolicyEntity::getId).last("LIMIT 201"));
        require(policies.size() <= 200, "匹配策略超过200条，请先治理策略范围，禁止截断");
        if (policies.stream().noneMatch(row -> StrUtil.isBlank(row.getResourceCode()))) gaps.add("当前工厂缺生效EXECUTION/ALL类型通用局部重排策略");
        preview.setEligible(gaps.isEmpty());
        String fingerprint = PlnMpsExecutionEvidenceReader.hash(JSONUtil.toJsonStr(Arrays.asList("MPS_REMAINING_PREVIEW_V1",
                tenant.toString(), factory, observationId.toString(), observation.getEvidenceFingerprint(), lineId.toString(), tolerance,
                current.getFingerprint(), preview.getExecutionSnapshot(), policies, gaps)));
        return preview.setFingerprint(fingerprint).setObservedAt(now.toString());
    }

    private PlnMpsRemainingReplanDTO.Detail linked(PlnMpsRemainingReplanLinkEntity link) {
        return new PlnMpsRemainingReplanDTO.Detail().setLink(link).setScenario(localReplan.scenario(link.getReplanScenarioId()));
    }

    private LambdaQueryWrapper<PlnMpsRemainingReplanLinkEntity> scope() {
        return new LambdaQueryWrapper<PlnMpsRemainingReplanLinkEntity>().eq(PlnMpsRemainingReplanLinkEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnMpsRemainingReplanLinkEntity::getFactoryCode, executionReader.actualFactory());
    }

    /** 当前认证用户必须具备实际工厂的直接业务授权，不能由请求参数另选组织。 */
    private void authorize(String level) {
        mpsService.requireFactoryAccess(executionReader.actualFactory(), level);
    }

    private void text(String value, int length, String label) {
        require(StrUtil.isNotBlank(value) && value.length() <= length, label + "必填且不得超长");
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
