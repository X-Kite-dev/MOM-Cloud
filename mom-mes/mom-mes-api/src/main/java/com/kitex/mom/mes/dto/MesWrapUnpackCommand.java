package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "包装拆箱命令")
public class MesWrapUnpackCommand {

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @NotBlank
    private String wrapCode;

    @NotBlank
    @Size(max = 500)
    private String reason;
}
