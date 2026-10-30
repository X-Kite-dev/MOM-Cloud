package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 可纳入返工执行方案的原动态外码实例。
 *
 * <p>候选必须由原工单、原实际工序的有效合格报工和未被补偿的节点操作共同证明，
 * 不能只按外码或内部SN搜索后让页面猜选。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "返工可引用的原动态实例")
public class MesReworkIdentifierCandidateDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long bindingId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long bindingGroupId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceTaskId;
    private String sourceTaskCode;
    private String sourceProduceTaskCode;
    private String routeNodeCode;
    private String bindingGroupCode;
    private String externalNamespace;
    private String externalCode;
    private String canonicalIdentifier;
    private String bindingStatus;
    private Integer bindingVersion;
    private String inputMode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceOperationId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceReportId;
    private String definitionFingerprint;
    private String graphFingerprint;
    private String sourceRouteFingerprint;
    /**
     * 同一实例在原实际工序上的有效正向操作数量；必须恰为1才可进入候选。
     */
    private Integer sourceOperationCount;
}
