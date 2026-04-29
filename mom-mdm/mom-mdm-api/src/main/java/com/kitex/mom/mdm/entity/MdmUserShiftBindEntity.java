package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户-班次绑定表
 */
@Data
@TenantTable
@TableName("mdm_user_shift_bind")
@Schema(description = "用户-班次绑定表")
public class MdmUserShiftBindEntity {

    @TableId
    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "班次编码")
    private String shiftCode;

    @Schema(description = "租户ID")
    private Long tenantId;

}
