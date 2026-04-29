package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesProcessProcedureMoldEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工艺路线-工序-模具
 *
 * @author kitex
 * @date 2025-04-11 11:22:06
 */
@Data
public class MesProcessProcedureMoldDTO {

    /**
     * 工艺路线-工序表主键ID
     */
    @Schema(description = "工艺路线-工序表主键ID")
    private Long id;

    /**
     * 工作中心列表
     */
    @Schema(description = "模具列表")
    private List<MesProcessProcedureMoldEntity> moldList;

}