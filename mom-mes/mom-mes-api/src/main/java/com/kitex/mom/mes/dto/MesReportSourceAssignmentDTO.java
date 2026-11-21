package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 不用物理合批比例猜来源；数量及主键在页面保持十进制文本。
 */
public final class MesReportSourceAssignmentDTO {
    private MesReportSourceAssignmentDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Allocation {
        @Schema(description = "原APS来源分配行ID")
        private String allocationId;
        @Schema(description = "本份有效报工归属于此来源的正数量，最多8位小数")
        private String quantity;
    }

    @Data
    public static class Assign {
        @Schema(description = "原有效报工主键")
        private Long reportId;
        @Schema(description = "永久请求号")
        private String requestId;
        @Schema(description = "页面预览证据指纹；其他报工或核定变化须重采")
        private String expectedFingerprint;
        @Schema(description = "本报工全部来源份额；总和须等于当前有效报工量")
        private List<Allocation> allocations;
        @Schema(description = "真实来源核定凭证引用")
        private String evidenceReference;
        @Schema(description = "核定或纠正后重核原因")
        private String reason;
    }

    /**
     * 单库事务已回滚的明确业务拒绝；通用错误码不具有此语义。
     */
    @Data
    @Accessors(chain = true)
    public static class Rejection {
        @Schema(description = "固定为ROLLBACK_CONFIRMED，仅领域校验拒绝且事务回滚完成后返回")
        private String outcome;
        @Schema(description = "被拒绝的永久请求号；客户端必须与原命令严格匹配")
        private String requestId;
        @Schema(description = "被拒绝的报工ID文本")
        private String reportId;
        @Schema(description = "被拒绝命令的原预览指纹")
        private String expectedFingerprint;
        @Schema(description = "重新核查并修改命令所需的业务说明")
        private String message;
    }

    @Data
    @Accessors(chain = true)
    public static class Source {
        @Schema(description = "APS来源分配行ID文本")
        private String allocationId;
        @Schema(description = "来源生产计划号")
        private String issueNo;
        @Schema(description = "来源计划原分配量")
        private String allocatedQuantity;
        @Schema(description = "按DIRECT/EXPLICIT依据得到的来源量；来源未核清时仅为已核清贡献下界，不是入库或质量净放行")
        private String assignedQuantity;
    }

    @Data
    @Accessors(chain = true)
    public static class Report {
        @Schema(description = "报工ID文本")
        private String reportId;
        @Schema(description = "原报工记录时间ISO文本，设备时刻与服务端登记时刻需另核对通道")
        private String reportTime;
        /** MES 写入报工主记录的审计时刻，供核对集成报工的实际登记时间。 */
        @Schema(description = "MES 服务端建账时刻的 ISO 文本，旧记录可能为空")
        private String registeredAt;
        @Schema(description = "原报工永久请求号，供同一只读快照核对弱网受理账")
        private String requestId;
        @Schema(description = "原报工冻结的MANUAL/AUTO/API通道")
        private String reportType;
        @Schema(description = "原报工实际派工编号，供核对设备原事件引用")
        private String arrangeCode;
        @Schema(description = "原报工冻结模式，不从当前路线补猜")
        private String reportMode;
        @Schema(description = "原报工冻结单位，不从物料名称猜测")
        private String reportUnit;
        @Schema(description = "设备或集成来源系统的原事件号")
        private String sourceEventId;
        @Schema(description = "来源系统记录的事件时刻 ISO 文本，设备与集成通道分别核验")
        private String sourceEventTime;
        @Schema(description = "原来源核定使用的报工事实指纹；保持旧持久化算法兼容")
        private String reportFingerprint;
        @Schema(description = "当前产出事件完整指纹，另含通道/设备时刻/请求号，不修改旧核定账")
        private String eventFingerprint;
        @Schema(description = "原始报工量，保持精确文本")
        private String originalQuantity;
        @Schema(description = "当前有效量")
        private String effectiveQuantity;
        @Schema(description = "当前报工状态")
        private String status;
        @Schema(description = "DIRECT/ASSIGNED/UNASSIGNED/STALE/REVERSED，不自动比例分摊")
        private String assignmentState;
        @Schema(description = "最新核定版本ID")
        private String assignmentId;
        @Schema(description = "最新核定份额")
        private List<Allocation> allocations;
    }

    @Data
    @Accessors(chain = true)
    public static class Preview {
        @Schema(description = "实际认证租户ID文本；用于本地原命令隔离，不由客户端指定执行租户")
        private String tenantId;
        @Schema(description = "实际工厂")
        private String factoryCode;
        @Schema(description = "MES工单ID文本")
        private String taskId;
        @Schema(description = "MES工单编码")
        private String taskCode;
        @Schema(description = "所选报工ID；整工单数量取证为空")
        private String targetReportId;
        @Schema(description = "原工单下达数量")
        private String plannedQuantity;
        @Schema(description = "工单完工结算字段，不能冒充在制末序有效报工")
        private String settledQuantity;
        @Schema(description = "唯一末序的有效报工合计；必须与工序计数守恒，否则未知")
        private String liveReportedQuantity;
        @Schema(description = "当前工单报工可精确汇总到来源；多来源须每份当前有效报工均有有效核定，物理满量不能代替来源凭证")
        private Boolean sourceComplete;
        @Schema(description = "DIRECT/EXPLICIT/UNVERIFIED；多来源未核清时即使物理报满仍保持未知")
        private String quantityBasis;
        @Schema(description = "可核定当前所选报工，不是实物流转/交付授权")
        private Boolean eligible;
        @Schema(description = "所选报工不适用或不可核定的业务说明；不影响整工单只读数量口径")
        private String eligibilityReason;
        @Schema(description = "提交预览的完整稳定指纹")
        private String fingerprint;
        @Schema(description = "原核定来源结构指纹，不包含当前产出量或采集时刻")
        private String sourceFingerprint;
        @Schema(description = "来源、直接计划及唯一末序身份指纹；正常报工推进不改变身份")
        private String sourceIdentityFingerprint;
        @Schema(description = "完整来源分配行，不截断")
        private List<Source> sources = new ArrayList<>();
        @Schema(description = "完整末序报工和最新核定状态，不跨工序相加")
        private List<Report> reports = new ArrayList<>();
        @Schema(description = "来源、数量、映射、报工或未知状态缺口")
        private List<String> gaps = new ArrayList<>();
    }
}
