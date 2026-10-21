package com.kitex.mom.qms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * MES复测失败触发的受控CAPA复开；真正结论由QMS反查MES，不接受自由文本替代。
 */
@Data
@Accessors(chain = true)
public class QmsCapaMesRetestReopenCommand {
    @Schema(description = "CAPA主键")
    private Long capaId;

    @Schema(description = "已独立批准的MES复测主键")
    private Long retestId;

    @Schema(description = "MES复测账中冻结的永久复开请求号")
    private String requestId;
}
