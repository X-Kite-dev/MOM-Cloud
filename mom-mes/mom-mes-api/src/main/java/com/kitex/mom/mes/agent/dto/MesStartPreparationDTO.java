package com.kitex.mom.mes.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 开工准备点时取证合同；长主键以文本传输，不能当作正式开工许可。
 */
public final class MesStartPreparationDTO {
    private MesStartPreparationDTO() {
    }

    /**
     * 原号重试必须保持同一派工、工单及调用者；新观察使用新请求号。
     */
    @Data
    public static class Query {
        /**
         * 认证上下文内的实际工厂，不接受跨厂覆写。
         */
        @NotBlank
        @Size(max = 100)
        private String factoryCode;
        /**
         * 精确派工主键。
         */
        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        private String arrangeId;
        /**
         * 派工所属工单，防止错误关联。
         */
        @NotBlank
        @Size(max = 100)
        private String taskCode;
        /**
         * 永久请求号；丢失响应后不能更换原号。
         */
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{8,100}")
        private String requestId;
    }

    /**
     * 模型读取已保存快照，不重新取证或改变原费用确认对象。
     */
    @Data
    public static class SnapshotQuery {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;
        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        private String snapshotId;
    }
}
