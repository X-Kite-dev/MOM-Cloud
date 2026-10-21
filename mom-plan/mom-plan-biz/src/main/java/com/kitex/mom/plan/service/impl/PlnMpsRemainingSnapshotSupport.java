package com.kitex.mom.plan.service.impl;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONConfig;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentLineEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** 不可变账正文核对，共用纯工具；不取用户、不访问数据库，损坏正文不退化成空数组。 */
final class PlnMpsRemainingSnapshotSupport {
    private PlnMpsRemainingSnapshotSupport() {
    }

    static String snapshot(Object value) {
        String text = JSONUtil.toJsonStr(value);
        require(text.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024, "完整取证正文超过8MiB，禁止截断");
        return text;
    }

    /** 新领域命令固定保留null数组槽，首版previous=null也须与浏览器原六项载荷一致。 */
    static String commandSnapshot(Object value) {
        String text = JSONUtil.toJsonStr(value, JSONConfig.create().setIgnoreNullValue(false));
        require(text.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024, "完整命令超过8MiB，禁止截断");
        return text;
    }

    static String hash(String value) {
        return PlnMpsExecutionEvidenceReader.hash(value);
    }

    static PlnMpsRemainingCommitmentDTO.Baseline baseline(PlnMpsRemainingCommitmentEntity record) {
        require(record.getBaselineSnapshot() != null && record.getBaselineSnapshot().getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024
                        && Objects.equals(hash(record.getBaselineSnapshot()), record.getBaselineFingerprint()),
                "剩余案件基线正文或指纹损坏，不能用当前数补造");
        PlnMpsRemainingCommitmentDTO.Baseline baseline;
        try {
            baseline = JSONUtil.toBean(record.getBaselineSnapshot(), PlnMpsRemainingCommitmentDTO.Baseline.class);
            require(Objects.equals(baseline.getLineId(), record.getMpsLineId().toString())
                            && Objects.equals(baseline.getRootIssueId(), record.getRootIssueId().toString())
                            && Objects.equals(baseline.getRootIssueNo(), record.getRootIssueNo())
                            && Objects.equals(baseline.getProductCode(), record.getProductCode())
                            && Objects.equals(baseline.getProductUnit(), record.getProductUnit())
                            && Objects.equals(baseline.getPlannedDate(), record.getOriginalPlannedDate().toString())
                            && Objects.equals(baseline.getSourceDueDate(), record.getCustomerDueDate().toString())
                            && Objects.equals(baseline.getCapacityResourceCode(), record.getCapacityResourceCode())
                            && quantity(baseline.getOriginalQuantity()).compareTo(record.getOriginalQuantity()) == 0
                            && quantity(baseline.getBaselineQuantity()).compareTo(record.getBaselineQuantity()) == 0
                            && quantity(baseline.getRemainingQuantity()).compareTo(record.getRemainingQuantity()) == 0
                            && record.getOriginalQuantity().compareTo(record.getBaselineQuantity().add(record.getRemainingQuantity())) == 0,
                    "剩余案件结构字段与原基线不一致");
        } catch (CheckedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CheckedException("剩余案件基线正文不可解析，不采用旧成功数");
        }
        return baseline;
    }

    static List<PlnMpsRemainingCommitmentDTO.Schedule> schedules(List<PlnMpsRemainingCommitmentLineEntity> lines) {
        require(lines != null && !lines.isEmpty() && lines.size() <= 100, "剩余案件须1至100个完整日桶");
        return lines.stream().map(line -> new PlnMpsRemainingCommitmentDTO.Schedule().setBucketNo(line.getBucketNo())
                .setPromisedDate(line.getPromisedDate().toString()).setQuantity(decimal(line.getQuantity()))).toList();
    }

    static BigDecimal quantity(String text) {
        require(text != null && text.matches("[0-9]{1,22}(\\.[0-9]{1,8})?"), "数量须精确十进制文本，最多8位小数，禁止指数/浮点");
        return new BigDecimal(text);
    }

    static String decimal(BigDecimal quantity) {
        return quantity == null ? null : quantity.stripTrailingZeros().toPlainString();
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
