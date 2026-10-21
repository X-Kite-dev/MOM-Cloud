package com.kitex.mom.qms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务来源与QMS CAPA/8D的权威链接回执。
 */
@Data
@Accessors(chain = true)
@Schema(description = "CAPA/8D链接回执")
public class QmsCapaLinkDTO {

    @Schema(description = "CAPA主键")
    private Long capaId;

    @Schema(description = "CAPA编号")
    private String capaNo;

    @Schema(description = "当前CAPA阶段")
    private String status;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "来源主键")
    private Long sourceId;

    @Schema(description = "来源业务编号")
    private String sourceNo;

    @Schema(description = "工厂编码")
    private String factoryCode;

    @Schema(description = "回执实际租户")
    private Long tenantId;

    @Schema(description = "当前CAPA周期")
    private Integer cycleNo;

    @Schema(description = "本周期权威关闭时间，未关闭时为空")
    private LocalDateTime closedTime;
    @Schema(description = "含亚秒精度的权威关闭时刻ISO文本，避免接口日期显示舍入开放过早复测")
    private String closedTimeExact;

    @Schema(description = "原CAPA冻结的有效性判定标准")
    private String effectivenessCriteria;

    @Schema(description = "协调人与本周期措施责任人账号，不能独立复核本周期效果")
    private List<String> excludedReviewerCodes;

    @Schema(description = "状态、周期、关闭时间与原验证证据的SHA-256指纹")
    private String lifecycleFingerprint;

    @Schema(description = "已执行复开请求号，仅复开回执携带")
    private String reopenRequestId;

    @Schema(description = "已执行复测来源主键，仅复开回执携带")
    private Long reopenRetestId;

    @Schema(description = "该永久命令实际开启的周期，仅复开回执携带")
    private Integer reopenedCycleNo;
}
