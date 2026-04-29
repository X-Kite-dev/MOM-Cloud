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