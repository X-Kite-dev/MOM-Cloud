package com.kitex.mom.qms.dto;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;

import java.util.LinkedHashMap;

/**
 * 与MES永久回执共用的MRB决策证据，不含后续执行推进的可变状态。
 */
public final class QmsMesCaseDecisionSnapshot {
    private QmsMesCaseDecisionSnapshot() {
    }

    /**
     * 旧TRANSFER保留原键顺序和NULL序列化；新NG固定14键，不重算旧履历SHA。
     */
    public static String of(QmsBadInfoEntity ncr, QmsBadInfoDetailEntity detail) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("ncr", ncr.getId());
        evidence.put("detail", detail.getId());
        evidence.put("key", detail.getMesDecisionKey());
        evidence.put("case", ncr.getResourceTaskId());
        evidence.put("quantity", detail.getJudgeNum());
        evidence.put("action", detail.getHandleResult());
        evidence.put("reason", detail.getHandleRemark());
        evidence.put("decider", detail.getHandlePersonId());
        evidence.put("file", detail.getHandleFile());
        evidence.put("expected", detail.getExpectedCompletionTime());
        evidence.put("concessionUnrestricted", detail.getMesConcessionUnrestricted());
        evidence.put("deciderId", detail.getMesDeciderUserId());
        if ("MES_PRODUCTION_NG".equals(ncr.getResourceType())) {
            evidence.put("version", "QMS_PRODUCTION_NG_DECISION_V1");
            evidence.put("origin", "PRODUCTION_NG");
            // 无附件的合法放行/报废也明确file:null，不能因默认配置丢失固定字段。
            return new JSONObject(evidence, JSONConfig.create().setIgnoreNullValue(false)).toString();
        }
        return JSONUtil.toJsonStr(evidence);
    }
}
