package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 已完成换型的精确工序归属协议；长ID、数量和原时刻使用文本，避免终端精度损失。
 */
public final class MesChangeoverAttributionDTO {
    private MesChangeoverAttributionDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Command {
        @Schema(description = "永久命令号，最多100字符")
        private String requestId;
        @Schema(description = "真实已完成换型ID，SUBMIT必填")
        private String executionId;
        @Schema(description = "精确派工ID，SUBMIT必填")
        private String arrangeId;
        @Schema(description = "原案件ID，REVIEW/CANCEL/REVOKE必填")
        private String caseId;
        @Schema(description = "完整预览基线指纹，SUBMIT必填")
        private String expectedFingerprint;
        @Schema(description = "真实现场凭证引用，SUBMIT/REVOKE必填")
        private String evidenceReference;
        @Schema(description = "原因或审核意见，最多500字符")
        private String reason;
        @Schema(description = "审核决定APPROVE/REJECT")
        private String decision;
        @Schema(description = "异人批准所需完整五项检查，不接受客户端伪造源事实")
        private List<String> checks;
    }

    @Data
    @Accessors(chain = true)
    public static class Preview {
        @Schema(description = "实际组织内精确换型ID")
        private String executionId;
        @Schema(description = "精确派工ID")
        private String arrangeId;
        @Schema(description = "源与目标稳定身份SHA-256，不含正常执行推进")
        private String expectedFingerprint;
        @Schema(description = "当前完整源事实是否满足门禁")
        private Boolean eligible;
        @Schema(description = "全部不合格/未知原因")
        private List<String> gaps;
        @Schema(description = "完整原换型及设备操作记录JSON")
        private String sourceSnapshot;
        @Schema(description = "完整目标原状态JSON，与稳定归属基线分开")
        private String targetSnapshot;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        @Schema(description = "永久号")
        private String requestId;
        @Schema(description = "完整命令指纹")
        private String requestFingerprint;
        @Schema(description = "真实操作人ID")
        private String actorUserId;
        @Schema(description = "实际租户ID")
        private String tenantId;
        @Schema(description = "实际工厂")
        private String factoryCode;
        @Schema(description = "原动作SUBMIT/REVIEW/CANCEL/REVOKE")
        private String action;
        @Schema(description = "APPLIED或已证实事务回滚的ROLLBACK_CONFIRMED；未知不伪造确认")
        private String outcome;
        @Schema(description = "原规范载荷JSON，终端严格核对后只清本号")
        private String requestSnapshot;
        @Schema(description = "原结果案件完整JSON，ID/时间精确文本")
        private String caseSnapshot;
        @Schema(description = "业务拒绝原因或源漂移结果")
        private String message;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        @Schema(description = "VERIFIED/UNBOUND/CONTESTED/DATA_GAP，仅工装计时归属")
        private String status;
        @Schema(description = "全部缺口，不补零、不猜产品族")
        private List<String> gaps;
        @Schema(description = "有效已核定工装计时秒数，未知为null")
        private String seconds;
        @Schema(description = "可信源笔数，未知为null")
        private Integer executionCount;
        @Schema(description = "仅一笔绑定第一有效派工、完成于首RUN前时为true")
        private Boolean firstDispatchOnly;
        @Schema(description = "完整归属/原源/操作凭证；含异常及撤销历史")
        private List<Map<String, Object>> records;
    }
}
