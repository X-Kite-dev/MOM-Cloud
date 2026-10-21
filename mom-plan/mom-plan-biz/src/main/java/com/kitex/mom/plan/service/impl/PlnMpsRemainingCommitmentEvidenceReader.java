package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.kitex.mom.plan.entity.PlnMpsExecutionObservationEntity;
import com.kitex.mom.plan.service.PlnMpsExecutionObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

/**
 * 已开工原桶的剩余承诺编制依据：只读原确认观察及当前执行，不改原数量或承诺日期。
 *
 * <p>原观察被冻结为B，当前正常新增形成C；不比较包含其他桶推进的整版当前指纹，
 * 而是逐原来源/原报工验证基线。正式发布仍须政策、RCCP与异岗复核。</p>
 */
@Component
@RequiredArgsConstructor
public class PlnMpsRemainingCommitmentEvidenceReader {
    private final PlnMpsExecutionObservationService observationService;
    private final PlnMpsExecutionEvidenceReader executionReader;
    private final PlnMpsRemainingFulfillmentCalculator calculator;

    /** 新编制引用30分钟内的独立确认观察；过期不能用新采集时间覆盖原观察时间。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Reference read(Long observationId, Long lineId) {
        require(observationId != null && lineId != null, "原确认观察及精确MPS桶必填");
        var record = observationService.detail(observationId);
        require(record != null && Objects.equals(record.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(record.getFactoryCode(), executionReader.actualFactory())
                        && "CONFIRMED".equals(record.getStatus()) && StrUtil.isNotBlank(record.getReviewedBy())
                        && StrUtil.isNotBlank(record.getCreateBy()) && !Objects.equals(record.getReviewedBy(), record.getCreateBy())
                        && record.getObservedTime() != null && record.getReviewedTime() != null
                        && record.getVersionId() != null && record.getToleranceMinutes() != null
                        && record.getToleranceMinutes() >= 0 && record.getToleranceMinutes() <= 1440,
                "须选择当前实际组织已独立核验的来源观察，不接受待审/旧失效或自审观察");
        Duration age = Duration.between(record.getObservedTime(), LocalDateTime.now());
        require(!age.isNegative() && age.compareTo(Duration.ofMinutes(30)) <= 0,
                "新编制依据超过30分钟，须重新采集并独立确认，不改旧观察");
        require(record.getEvidenceSnapshot() != null
                        && record.getEvidenceSnapshot().getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
                "原观察缺完整正文或超过8MiB，不能截断为可执行依据");
        PlnMpsExecutionDTO.Evidence frozen;
        try {
            frozen = JSONUtil.toBean(record.getEvidenceSnapshot(), PlnMpsExecutionDTO.Evidence.class);
        } catch (RuntimeException exception) {
            throw new CheckedException("原观察正文不可解析，禁止按当前数补造基线");
        }
        require(frozen != null && frozen.getBuckets() != null
                        && frozen.getBuckets().size() <= 500 && Objects.equals(frozen.getFactoryCode(), record.getFactoryCode())
                        && Objects.equals(frozen.getVersionId(), record.getVersionId().toString())
                        && Objects.equals(frozen.getToleranceMinutes(), record.getToleranceMinutes())
                        && Objects.equals(frozen.getResult(), record.getResult())
                        && Objects.equals(frozen.getFingerprint(), record.getEvidenceFingerprint())
                        && Objects.equals(executionReader.fingerprintOf(frozen), record.getEvidenceFingerprint()),
                "原确认观察正文、组织、版本或稳定指纹不一致；旧观察须重采，不伪造新版事件");
        LocalDateTime observedAt;
        try {
            observedAt = LocalDateTime.parse(frozen.getObservedAt());
        } catch (RuntimeException exception) {
            throw new CheckedException("原观察缺精确ISO采集时刻");
        }
        // PG时间列保留微秒，正文保留Java原精度；仅接受至多一个微秒的存储舍入差，不猜时钟偏差。
        require(Duration.between(record.getObservedTime(), observedAt).abs().compareTo(Duration.ofNanos(1000)) <= 0,
                "原观察正文时间与持久化时间不一致");
        var matched = frozen.getBuckets().stream().filter(bucket -> bucket != null
                && Objects.equals(bucket.getLineId(), lineId.toString())).toList();
        require(matched.size() == 1, "原观察中未找到唯一精确MPS桶，禁止取首条或按产品拼凑");
        var baseline = calculator.baseline(matched.get(0), observedAt);
        require(JSONUtil.toJsonStr(baseline).getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
                "完整基线超过8MiB，须专项对账，不截断");
        var latest = executionReader.read(record.getVersionId(), record.getToleranceMinutes());
        var current = latest.getBuckets().stream().filter(bucket -> Objects.equals(bucket.getLineId(), lineId.toString())).toList();
        require(current.size() == 1, "当前原MPS桶身份不完整，不能使用旧成功释放责任");
        var progress = calculator.verifyProgress(baseline, current.get(0), LocalDateTime.now());
        return new Reference(record, baseline, current.get(0), progress);
    }

    /** 展示数量准备情况，不虚构新分期、不提供正式发布写入口。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlnMpsRemainingCommitmentDTO.BaselinePreview preview(Long observationId, Long lineId) {
        // read()没有写入；在此调用仍共享本方法开启的只读一致性事务。
        var reference = read(observationId, lineId);
        boolean ready = "ACTIVE".equals(reference.progress().getResult());
        return new PlnMpsRemainingCommitmentDTO.BaselinePreview().setObservationId(observationId.toString())
                .setVersionId(reference.observation().getVersionId().toString())
                .setToleranceMinutes(reference.observation().getToleranceMinutes()).setBaseline(reference.baseline())
                .setCurrent(reference.progress()).setQuantityReady(ready)
                .setEligibilityReason(ready ? null : "FULFILLED".equals(reference.progress().getResult())
                        ? "当前来源已报满原量，不能再创建正量剩余承诺" : String.join("；", reference.progress().getGaps()))
                .setFingerprint(PlnMpsExecutionEvidenceReader.hash(JSONUtil.toJsonStr(Arrays.asList(
                        "MPS_REMAINING_BASELINE_V1", observationId.toString(), reference.observation().getEvidenceFingerprint(),
                        reference.baseline(), reference.progress(), executionReader.bucketFingerprint(reference.current())))));
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /** 仅领域内部使用，不把数据库观察行当可由客户端修改的提交载荷。 */
    public record Reference(PlnMpsExecutionObservationEntity observation,
                            PlnMpsRemainingCommitmentDTO.Baseline baseline,
                            PlnMpsExecutionDTO.Bucket current,
                            PlnMpsRemainingCommitmentDTO.Evaluation progress) {
    }
}
