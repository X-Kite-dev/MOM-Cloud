package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 只读节点预览，不预占外码、不发弱网许可、不生成报工或操作。最终执行必须重新校验。
 */
@Data
@Accessors(chain = true)
@Schema(description = "动态外码节点只读预览")
public class MesIdentifierBindingPreviewDTO {
    @Schema(description = "原派工单号")
    private String arrangeCode;
    @Schema(description = "服务端冻结动作")
    private String action;
    /**
     * 精确原生策略版本；历史预览为空，不能据此升级旧派工。
     */
    @Schema(description = "原生区段协议NODE_BINDING_V2")
    private String policySchemaVersion;
    /**
     * 当前派工真实父任务，供终端核对预览没有串任务。
     */
    @Schema(description = "原生区段当前工序任务号")
    private String produceTaskCode;
    /**
     * 当前派工冻结节点；同主工序的另一次执行不能替代。
     */
    @Schema(description = "原生区段当前稳定节点")
    private String sourceRouteNodeCode;
    @Schema(description = "永久区段ID文本")
    private String bindingGroupId;
    @Schema(description = "本次核定的内部规范单件SN")
    private String canonicalIdentifier;
    @Schema(description = "本次核定的外码")
    private String externalIdentifier;
    @Schema(description = "USE/RELEASE应原样带回的实例ID文本；BIND为空")
    private String expectedBindingId;
    @Schema(description = "USE/RELEASE应原样带回的生命周期版本；BIND为空")
    private Integer expectedBindingVersion;
    @Schema(description = "预览不是授权或成功回执，最终事务仍须核验")
    private String message;
}
