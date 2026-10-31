package com.kitex.mom.qms.agent.dto;

import com.kitex.mom.qms.entity.QmsCapaExperienceReferenceEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 引用后的原CAPA业务进展，不推断经验采纳或效果因果。 */
@Data
@Accessors(chain = true)
public class QmsExperienceFollowup {
    private String contract = "QMS_EXPERIENCE_FOLLOWUP_V1";
    private String tenantId;
    private String factoryCode;
    @Schema(description = "原不可变引用回执，保留原周期与知识版本")
    private QmsCapaExperienceReferenceEntity reference;
    private String capaNo;
    private String currentStatus;
    private Integer currentCycle;
    private Boolean sameCycle;
    @Schema(description = "引用后案件指纹是否变化；不是引用失效或采纳结论")
    private Boolean caseChanged;
    private LocalDateTime observedAt;
    @Schema(description = "仅同周期返回原CAPA有效性字段，不把新周期结果归到旧引用")
    private Map<String, Object> effectiveness;
    private Map<String, Boolean> stageCompletion;
    private List<String> evidenceGaps;
    private List<Map<String, Object>> actions;
    private long current;
    private long size;
    private long total;
}
