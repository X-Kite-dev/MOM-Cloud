package com.kitex.mom.qms.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.entity.QmsCapaEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * CAPA链接及复开共同使用的精确关闭周期指纹。
 */
final class QmsCapaLifecycleEvidence {
    private QmsCapaLifecycleEvidence() {
    }

    static String fingerprint(QmsCapaEntity capa, List<String> excludedReviewers) {
        String facts = JSONUtil.toJsonStr(Arrays.asList(capa.getId(), capa.getTenantId(), capa.getFactoryCode(),
                capa.getSourceType(), capa.getSourceId(), capa.getSourceNo(), capa.getStatus(), capa.getCycleNo(),
                Objects.toString(capa.getClosedTime(), null), capa.getCoordinatorCode(),
                capa.getEffectivenessCriteria(), capa.getEffectivenessResult(), capa.getEffectivenessEvidence(),
                capa.getVerifierCode(), Objects.toString(capa.getVerificationTime(), null),
                excludedReviewers.stream().distinct().sorted().toList()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("CAPA关闭周期证据指纹生成失败", exception);
        }
    }
}
