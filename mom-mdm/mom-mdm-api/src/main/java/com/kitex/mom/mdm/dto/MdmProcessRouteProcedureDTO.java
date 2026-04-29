package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工艺路线-工序DTO
 *
 * @author kitex
 * @date 2025-03-26 10:23:27
 */
@Data
public class MdmProcessRouteProcedureDTO {

    /**
     * ID
     */
    @Schema(description = "ID")
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
    private List<MdmProcessRouteWorkingEntity> procedureList;

}