package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * IoT或设备网关提交的生产产量事件。
 */
@Data
@Schema(description = "设备自动报工事件")
public class MesAutoReportEventDTO {
    /**
     * 不传保持旧协议；动态单件节点必须显式使用V2，不升级旧号的含义。
     */
    @Schema(description = "原事件协议版本；动态节点为MES_AUTO_EVENT_V2，旧协议为空")
    private String captureVersion;
    @Schema(description = "来源系统内永久唯一的事件号", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String sourceEventId;
    @Schema(description = "设备编码", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String deviceCode;
    @Schema(description = "事件标识符", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String eventIdentifier;
    @Schema(description = "设备原始数值；按规则解释为增量、累计计数或脉冲数")
    private BigDecimal value;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "设备事件发生时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime eventTime;
    @Schema(description = "指定派工单；为空时按设备、工序/工位规则匹配唯一生产中派工")
    private String arrangeCode;
    @Schema(description = "SN、流转卡或容器码；数量报工可为空")
    private String transferCode;
    /**
     * BIND的第二个原始扫描值，不能藏在rawPayload中。
     */
    @Schema(description = "绑定节点的外码；使用/释放节点为空", maxLength = 200)
    private String externalIdentifier;
    /**
     * 来源在发生事件时捕获，服务端不按处理时当前外码自动补填。
     */
    @Schema(description = "使用/释放节点原采集实例ID十进制文本", maxLength = 19)
    private String expectedBindingId;
    /**
     * ACTIVE为正奇数；释放及复用后旧版本不再构成执行依据。
     */
    @Schema(description = "使用/释放节点原采集生命周期版本")
    private Integer expectedBindingVersion;
    @Schema(description = "来源原始报文，用于审计，不参与业务计算")
    private String rawPayload;
    @Schema(description = "工厂编码")
    private String factoryCode;
}
