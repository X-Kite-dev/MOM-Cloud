package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** 执行兑现授权门面；共用独立来源读取器，RCCP不得依赖此门面形成循环。 */
@Component
@RequiredArgsConstructor
public class PlnMpsExecutionEvidenceReader {
    private final PlnMpsExecutionSourceReader sourceReader;
    private final PlnMpsService mpsService;

    /** 授权后取完整一致性来源快照；正常推进不改写任何历史观察。 */
    public PlnMpsExecutionDTO.Evidence read(Long versionId, int tolerance) {
        mpsService.requireFactoryAccess(actualFactory(), "read");
        var evidence = sourceReader.read(versionId, tolerance);
        return evidence.setFingerprint(fingerprintOf(evidence));
    }

    /** 采集边界须冻结到观察正文，但不参与每次刷新都会变化的稳定业务指纹。 */
    private Object stableBuckets(List<PlnMpsExecutionDTO.Bucket> buckets) {
        var snapshot = JSONUtil.parseArray(JSONUtil.toJsonStr(buckets));
        for (int index = 0; index < snapshot.size(); index++) {
            var links = snapshot.getJSONObject(index).getJSONArray("links");
            if (links == null) continue;
            for (int source = 0; source < links.size(); source++) {
                links.getJSONObject(source).remove("productionSnapshotStartedAt");
                links.getJSONObject(source).remove("productionObservedAt");
            }
        }
        return snapshot;
    }

    /** 与采集算法相同，供领域读侧核对原不可变观察正文，不采用正文自报的指纹。 */
    String fingerprintOf(PlnMpsExecutionDTO.Evidence evidence) {
        return hash(JSONUtil.toJsonStr(Arrays.asList("MPS_EXECUTION_V1", TenantContextHolder.getTenantId(),
                evidence.getFactoryCode(), evidence.getVersionId(), evidence.getVersionStatus(),
                evidence.getToleranceMinutes(), stableBuckets(evidence.getBuckets()))));
    }

    /** 单桶预览也保留当前逐份事实，不能只用相同累计数掩盖当前事件变化。 */
    String bucketFingerprint(PlnMpsExecutionDTO.Bucket bucket) {
        return hash(JSONUtil.toJsonStr(stableBuckets(List.of(bucket))));
    }

    String actualFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory),
                "MPS执行回评须选择实际租户、工厂，禁止跳过隔离");
        mpsService.currentUser();
        return factory;
    }

    /** 派生业务在正式发布/回退前仍须重核当前实名账号的直接工厂权限。 */
    void requireBusinessFactoryAccess(String level) {
        mpsService.requireFactoryAccess(actualFactory(), level);
    }

    static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("MPS执行证据指纹生成失败", exception);
        }
    }


    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
