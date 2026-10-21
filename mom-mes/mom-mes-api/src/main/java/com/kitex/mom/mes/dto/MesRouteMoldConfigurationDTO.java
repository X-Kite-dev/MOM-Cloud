package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.mom.mes.entity.MesProcessProcedureMoldEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 当前真实节点的模具维护快照；只包含工程当前态，不替代生产冻结。
 */
@Data
@Schema(description = "路线节点模具配置及原图版本")
public class MesRouteMoldConfigurationDTO {

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "真实路线节点记录ID")
    private Long id;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "真实父路线ID")
    private Long routeId;

    @Schema(description = "当前实际租户")
    private Long tenantId;

    @Schema(description = "当前实际工厂")
    private String factoryCode;

    @Schema(description = "工艺路线编码")
    private String routeCode;

    @Schema(description = "工艺路线名称")
    private String routeName;

    @Schema(description = "稳定路线节点编号")
    private String routeNodeCode;

    @Schema(description = "主工序编码，不用于节点身份匹配")
    private String processCode;

    @Schema(description = "主工序名称")
    private String processName;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "保存必须原样带回的父图版本")
    private Long expectedGraphRevision;

    @Schema(description = "当前节点已绑定模具，失效候选仍保留显示以便明确移除")
    private List<MesProcessProcedureMoldEntity> moldList;
}
