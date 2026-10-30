package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 局部重排工作区的只读投影；实际选案、发布及回退仍调用原领域接口。 */
public final class PlnApsLocalReplanDTO {
    private PlnApsLocalReplanDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "智能APS中的原业务局部重排工作区")
    public static class Workbench {
        @Schema(description = "统一案件身份，不以该外层案件状态冒充原业务执行状态")
        private String caseId;
        @Schema(description = "SHORTAGE、RESOURCE、QUALITY或INSERTION")
        private String domain;
        @Schema(description = "原来源类型")
        private String sourceType;
        @Schema(description = "原业务情景主键，文本防止精度丢失")
        private String scenarioId;
        @Schema(description = "原业务情景编号")
        private String scenarioNo;
        @Schema(description = "原业务情景当前状态")
        private String scenarioStatus;
        @Schema(description = "原业务选案时必须复核的来源指纹")
        private String sourceFingerprint;
        @Schema(description = "原领域的实际选案请求号，用于核对未知回执")
        private String selectionRequestId;
        @Schema(description = "原业务选案权限")
        private String selectPermission;
        @Schema(description = "原业务异岗复核发布权限")
        private String publishPermission;
        @Schema(description = "无多候选情景的历史记录只展示")
        private boolean legacy;
        @Schema(description = "当前来源的全部候选投影")
        private List<Candidate> candidates;
        @Schema(description = "效果回评起点的原发布情景及当前效果指纹；普通局部重排为空")
        private String effectScenarioId;
        private String effectFingerprint;
        @Schema(description = "当前效果证据返回的可评估计划，不包含已开工锁定工序")
        private List<EffectPlan> effectPlans;
        @Schema(description = "效果证据缺口，存在时不允许继续生成候选")
        private List<String> gaps;
    }

    @Data
    @Accessors(chain = true)
    public static class EffectPlan {
        @Schema(description = "生产计划主键及编号")
        private String issueId;
        private String issueNo;
        @Schema(description = "本次效果证据中可重排的偏差工序数")
        private Integer eligibleOperationCount;
    }

    @Data
    public static class EffectAssessCommand {
        @Schema(description = "明确选择的一张生产计划，文本防止精度丢失")
        private String issueId;
        @Schema(description = "原永久幂等请求号，不超过48位")
        private String requestId;
        @Schema(description = "工作区当前效果证据指纹")
        private String expectedEffectFingerprint;
        @Schema(description = "真实业务评估依据，不超过500字")
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Candidate {
        @Schema(description = "原领域候选案件主键，非通用排程任务主键")
        private String id;
        @Schema(description = "原领域候选编号")
        private String caseNo;
        @Schema(description = "候选名称")
        private String name;
        @Schema(description = "原业务候选状态")
        private String status;
        @Schema(description = "原业务确定性评分")
        private BigDecimal score;
        @Schema(description = "原业务候选排名")
        private Integer rank;
        @Schema(description = "原业务硬约束可行性，未知按不可选择处理")
        private boolean feasible;
        @Schema(description = "是否为原业务选中候选")
        private boolean selected;
        @Schema(description = "原申请人及原发布人")
        private String applicantCode;
        private String publisherCode;
        @Schema(description = "原生产计划号，插单时为新增计划号")
        private String issueNo;
        @Schema(description = "原冻结边界及候选有效期")
        private String freezeUntil;
        private String expiresTime;
        @Schema(description = "原正式排程及候选完工时刻")
        private String originalCompletionTime;
        private String proposedCompletionTime;
        @Schema(description = "原业务交付风险")
        private String deliveryRisk;
        @Schema(description = "原业务原子发布时刻，不表示已复工")
        private String publishedTime;
        @Schema(description = "原领域实际发布请求号，用于核对未知回执")
        private String publishRequestId;
        @Schema(description = "原方案差异：MOVE移动、UNCHANGED保留、FROZEN冻结及INSERT新增数量")
        private Map<String, Integer> actionCounts;
        @Schema(description = "原业务发布合同中的必要参数；所有主键转文本，前端不得自行补造")
        private Map<String, String> publishParameters;
        @Schema(description = "原方案全部工序差异，不截断，前端分页展示")
        private List<OperationDiff> lines;
    }

    @Data
    @Accessors(chain = true)
    public static class OperationDiff {
        @Schema(description = "原领域差异记录主键")
        private String id;
        @Schema(description = "插单范围中的生产计划号；其他来源为候选原计划号")
        private String issueNo;
        @Schema(description = "冻结路线节点身份，不能只按重复工序编码合并")
        private String routeNodeCode;
        @Schema(description = "工序编码及名称")
        private String processCode;
        private String processName;
        @Schema(description = "原冻结工序序号，用于旧协议唯一对应校验")
        private Integer operationSeq;
        @Schema(description = "原领域动作及保留或阻断原因")
        private String actionType;
        private String blockedReason;
        @Schema(description = "原排程与候选排程时刻")
        private String originalStartTime;
        private String originalEndTime;
        private String proposedStartTime;
        private String proposedEndTime;
        @Schema(description = "原设备与候选设备")
        private String originalDeviceCode;
        private String proposedDeviceCode;
        @Schema(description = "原资源与候选资源")
        private String originalResourceCode;
        private String proposedResourceCode;
        @Schema(description = "原人员与候选人员")
        private String originalEmployeeCode;
        private String proposedEmployeeCode;
        @Schema(description = "原工装集合与候选工装集合，沿用原领域编码内容")
        private String originalToolingCodes;
        private String proposedToolingCodes;
        @Schema(description = "开始时刻移动分钟数")
        private Long shiftMinutes;
    }
}
