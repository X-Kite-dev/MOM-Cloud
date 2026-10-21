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
     * 配置读取时的原父图版本；包含节点与模具绑定修改，不可自动取最新版重试。
     */
    @Schema(description = "读取配置时的原路线图版本，必填")
    private Long expectedGraphRevision;

    /**
     * 工作中心列表
     */
    @Schema(description = "模具列表")
    private List<MesProcessProcedureMoldEntity> moldList;

}
