package com.kitex.mom.tpm.delayedConfig;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TpmNoticePerson {

    @Schema(description = "用户")
    private Long userId;

    @Schema(description = "时间")
    private Integer expireTime;

}
