package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 终端原号登记使用的真实实名及组织身份，不包含令牌或生产执行许可。
 */
@Data
@Accessors(chain = true)
@Schema(description = "报工原号登记的服务端身份")
public class MesReportOperatorContextDTO {

    /**
     * 实际认证租户的十进制文本，不能由页面猜测。
     */
    @Schema(description = "实际租户ID文本")
    private String tenantId;

    /**
     * 已核对读权限的当前实际工厂。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    /**
     * 实际登录用户ID文本，不使用姓名作为身份主键。
     */
    @Schema(description = "实名账号ID文本")
    private String actorId;

    /**
     * 实际登录账号编码，和API/设备操作工分别留证。
     */
    @Schema(description = "实名账号编码")
    private String actorCode;

    /**
     * 兼容旧终端的部署摘要；新终端必须读取精确派工/渠道决定，不能只依赖此值。
     */
    @Schema(description = "数据库结构核对通过摘要；不是具体报工许可")
    private Boolean nodeExecutionReady = false;

    /**
     * 全量实时部署缺项，不包含令牌，也不写入冻结快照。
     */
    @Schema(description = "动态节点当前部署诊断")
    private MesIdentifierNodeReadinessDTO nodeReadiness;
}
