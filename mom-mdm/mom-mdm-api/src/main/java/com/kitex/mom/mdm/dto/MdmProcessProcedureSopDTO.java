package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工艺路线-工序-SOP
 *
 * @author kitex
 * @date 2025-03-18 09:25:27
 */
@Data
public class MdmProcessProcedureSopDTO {

    /**
     * 工艺路线-工序表主键ID
     */
    @Schema(description = "工艺路线-工序表主键ID")
    private Long id;

    /**
     * 工序SOP列表
     */
    @Schema(description = "工序SOP列表")
    private List<MdmProcedureSopDTO> sopList;

    /**
     * 读取时的全部附件指纹；空列表表示原来无附件，null仅兼容未转入的旧客户端。
     */
    @Schema(description = "原附件集合指纹，防止并发覆盖")
    private List<String> expectedAttachmentHashes;

}
