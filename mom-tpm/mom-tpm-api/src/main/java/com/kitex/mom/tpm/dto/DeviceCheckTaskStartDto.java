package com.kitex.mom.tpm.dto;

import com.kitex.mom.tpm.entity.TpmCheckTaskDetailsEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class DeviceCheckTaskStartDto {


    @Schema(description = "设备编码")
    private String deviceCode;

    @Schema(description = "设备 - 任务明细")
    private List<TpmCheckTaskDetailsEntity> deviceDetailsList;


    @Schema(description = "模具编码")
    private String moldCode;


    @Schema(description = "模具 - 任务明细")
    private List<TpmCheckTaskDetailsEntity> moldDetailsList;

    @Schema(description = "换模")
    private Boolean changeMold;

    @Schema(description = "MES换型执行账ID；换模必须先开始计时再提交完成")
    private Long changeoverExecutionId;

    @Schema(description = "工作中心")
    private String worksiteCode;

    /**
     * 物理换型必须与只读观察时的组织核对；普通设备开机保持原契约。
     */
    @Schema(description = "换型观察时租户ID文本")
    private String expectedTenantId;

    /**
     * 仅核对，不改变认证工厂。
     */
    @Schema(description = "换型观察时工厂编码")
    private String expectedFactoryCode;

    /**
     * 物理换型当前执行人的真实用户ID。
     */
    @Schema(description = "换型观察时实名用户ID文本")
    private String expectedActorUserId;
}
