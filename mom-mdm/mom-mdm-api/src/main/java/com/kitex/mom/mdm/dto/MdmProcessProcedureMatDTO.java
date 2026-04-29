package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工艺路线-工序-投料
 *
 * @author kitex
 * @date 2025-03-18 10:53:06
 */
@Data
public class MdmProcessProcedureMatDTO {

    /**
     * 工艺路线-工序表主键ID
     */
    @Schema(description = "工艺路线-工序表主键ID")
    private Long id;

    /**
     * 工序投料列表
     */
    @Schema(description = "工序投料列表")
    private List<MdmProcessProcedureMatEntity> matList;

}