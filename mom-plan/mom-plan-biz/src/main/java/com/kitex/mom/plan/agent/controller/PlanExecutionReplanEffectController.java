package com.kitex.mom.plan.agent.controller;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.agent.service.PlanExecutionReplanComparisonService;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnSchedulingEffectReviewDTO;
import com.kitex.mom.plan.service.PlnResourceLocalReplanService;
import com.kitex.mom.plan.service.PlnSchedulingAgentService;
import com.kitex.mom.plan.service.PlnSchedulingEffectReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 执行偏差重排发布后的内部效果采集契约。
 *
 * <p>仅供服务间调用，必须保留租户过滤和真实工厂上下文；只追加PLAN/MES效果观察，
 * 不允许修改正式排程、选案或发布。请求永久幂等，来源情景必须与全部候选证据一致。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/planAgentEffect/internal")
public class PlanExecutionReplanEffectController {

    private final PlnResourceLocalReplanService replanService;
    private final PlnSchedulingEffectReviewService reviewService;
    private final PlnSchedulingAgentService schedulingService;
    private final PlanExecutionReplanComparisonService comparisonService;

    /**
     * 核验发布链和来源后重采MES事实，并返回对应的情景与效果证据。
     */
    @Inner
    @PostMapping("/execution-replan/capture")
    public R<Map<String, Object>> capture(@Valid @RequestBody Capture command) {
        String factory = FactoryContextHolder.getFactoryCode();
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(factory) && Objects.equals(factory, command.factoryCode()),
                "效果采集必须保留真实租户与工厂过滤");
        var scenario = replanService.scenario(command.scenarioId());
        var header = scenario.getScenario();
        require(Objects.equals(header.getFactoryCode(), factory)
                        && "EXECUTION_DEVIATION_LOCAL_REPLAN".equals(header.getTriggerType())
                        && Set.of("PUBLISHED", "PARTIAL").contains(header.getScenarioStatus()),
                "仅允许采集当前工厂已发布的执行偏差重排情景");
        require(scenario.getCandidates() != null && scenario.getCandidates().size() == 3
                        && scenario.getCandidates().stream().allMatch(candidate ->
                        Objects.equals(command.sourceScenarioNo(), candidate.getEvidenceSourceNo())),
                "重排来源与Agent冻结的原排程不一致");
        var capture = new PlnSchedulingEffectReviewDTO.CaptureCommand();
        capture.setScenarioId(command.scenarioId());
        capture.setRequestId(command.requestId());
        capture.setToleranceMinutes(30);
        capture.setReason("MOM Agent发布后实施效果采集；来源=" + command.sourceScenarioNo());
        var detail = reviewService.capture(capture);
        var query = new PlanSchedulingAgentDTO.ScenarioQuery();
        query.setFactoryCode(factory);
        query.setScenarioId(command.scenarioId());
        var comparison = comparisonService.compare(scenario, detail, command.requestId());
        var effect = schedulingService.effect(query);
        if (effect.getDetail() != null && effect.getDetail().getObservations() != null) {
            // 内部采集只带当前观察及幂等请求原观察，避免每次把全部历史再次嵌套进AI产物。
            // 原PLAN观察账不删除；完整历史仍由既有效果回评接口提供。
            var currentId = effect.getDetail().getReview().getCurrentObservationId();
            effect.getDetail().setObservations(effect.getDetail().getObservations().stream()
                    .filter(item -> Objects.equals(item.getId(), currentId)
                            || Objects.equals(item.getId(), comparison.observationId())).toList());
        }
        return R.ok(Map.of("scenario", scenario, "effect", effect, "comparison", comparison));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 服务端固定对象与请求号，不接收URL、SQL或任意模型参数。
     */
    public record Capture(@NotNull @Positive Long scenarioId,
                          @NotBlank @Size(max = 100) String factoryCode,
                          @NotBlank @Size(max = 100) String sourceScenarioNo,
                          @NotBlank @Size(max = 64) String requestId) {
    }
}
