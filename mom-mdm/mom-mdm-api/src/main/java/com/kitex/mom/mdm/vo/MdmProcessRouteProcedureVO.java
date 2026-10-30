package com.kitex.mom.mdm.vo;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工艺路线-工序VO
 *
 * @author kitex
 * @date 2025-03-26 11:07:27
 */
@Data
public class MdmProcessRouteProcedureVO {

    /**
     * 读取时的路线图并发版本，提交时必须原样带回。
     */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    @Schema(description = "路线图并发版本")
    private Long graphRevision;

    /**
     * 历史为空；编辑投影不等同已持久升级。
     */
    @Schema(description = "当前已持久化的图协议")
    private String graphSchemaVersion;

    /**
     * 工艺路线ID
     */
    @Schema(description = "工艺路线ID")
    private Long id;

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 工艺路线名称
     */
    @Schema(description = "工艺路线名称")
    private String routeName;

    /**
     * 工序快照
     */
    @Schema(description = "工序快照")
    private JsonNode procedureSnapshot;

    /**
     * 工序列表
     */
    @Schema(description = "工序列表")
    private List<MdmProcessRouteWorkingVO> procedureList;

}
