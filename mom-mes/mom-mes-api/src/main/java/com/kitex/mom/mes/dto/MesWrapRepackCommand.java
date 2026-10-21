package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "包装重组命令")
public class MesWrapRepackCommand {

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @Schema(description = "目标箱码；为空时按来源包装规则生成新箱码")
    private String targetWrapCode;

    @NotBlank
    @Size(max = 500)
    private String reason;

    @Valid
    @NotEmpty
    private List<Allocation> allocations;

    @Data
    public static class Allocation {
        @NotNull
        private Long recordId;

        @NotNull
        @DecimalMin(value = "0.000001")
        private BigDecimal quantity;
    }
}
