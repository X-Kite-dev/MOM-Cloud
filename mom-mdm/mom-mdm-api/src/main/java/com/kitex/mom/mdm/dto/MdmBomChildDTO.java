package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmBomChildEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * @program: mom-cloud
 * @description: bom物料关联表数据类
 * @author: qinghuan
 * @create: 2024-12-16 15:10
 **/
@Data
@Schema(description = "bom物料关联表数据类")
public class MdmBomChildDTO extends MdmBomChildEntity {

    /**
     * 子bom物料列表
     */
    @Schema(description = "子bom物料列表")
    private List<MdmBomChildDTO> children;

}
