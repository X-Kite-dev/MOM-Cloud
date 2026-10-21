package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 真实转换来源V2：在完整MES写域保护内读取原工单、全工序图、原派工与正式工程。
 * V1观察指纹保持原义；本协议另设版本，既不登记PREPARED，也不证明库存已占用。
 */
@Data
public class MesPhysicalTransformSourceBundleDTO {
    /**
     * 完整集合协议：MES_PHYSICAL_SOURCE_BUNDLE_V2。
     */
    private String version;
    /**
     * 实际租户主键文本。
     */
    private String tenantId;
    /**
     * 实际认证请求选中的工厂，不从请求正文设置。
     */
    private String factoryCode;
    /**
     * 实际本人主键文本。
     */
    private String actorUserId;
    /**
     * 实际本人账号。
     */
    private String actorCode;
    /**
     * 当前目标库真实库存/流水计量字段类型和容量；无限numeric保留NULL精度/小数位。
     */
    private List<Map<String, String>> quantityColumns;
    /**
     * 按精确库存主键排序的全部来源及原工程证据。
     */
    private List<Entry> sources;
    /**
     * 包含实际owner、全部源及数据库字段容量的完整原字符串。
     */
    private String bundleSnapshot;
    /**
     * MES_PHYSICAL_SOURCE_BUNDLE_V2|bundleSnapshot的SHA-256。
     */
    private String bundleFingerprint;
    /**
     * 固定false；观察的事务锁在返回时释放，不能宣称持久占用或转换成功。
     */
    private Boolean executionAvailable;
    /**
     * 工程/source检查与真正claim、位置和库存执行边界。
     */
    private String message;

    /**
     * 一条真实来源的V2事实，不用原V1指纹悄悄追加字段。
     */
    @Data
    public static class Entry {
        /**
         * 精确原库存主键文本。
         */
        private String stockId;
        /**
         * 新事实算法域：MES_PHYSICAL_SOURCE_V2。
         */
        private String sourceVersion;
        /**
         * 原V1完整观察指纹，保留其旧事实语义。
         */
        private String originFingerprint;
        /**
         * 原来源字段的完整拷贝；fingerprint仅指向本条V2全文。
         */
        private MesPhysicalTransformDTO.SourceFact source;
        /**
         * 含V1原事实、真实工单、全部工序图、原派工、正式工程和计量容量的完整原字符串。
         */
        private String sourceSnapshot;
        /**
         * MES_PHYSICAL_SOURCE_V2|sourceSnapshot的SHA-256。
         */
        private String sourceFingerprint;
        /**
         * 原派工冻结的精确正式工程ID；缺失保持NULL，不查询当前MDM补造。
         */
        private String standardId;
        /**
         * 原正式工程全文SHA，缺失保持NULL。
         */
        private String standardFingerprint;
        /**
         * 原派工的严格政策原字符串，NONE不能解释为转换许可。
         */
        private String policyConfiguration;
        /**
         * 当前已知原工单/来源/工程检查；不包含尚未建立的claim和全部下游资格。
         */
        private Boolean sourceChecksPassed;
        /**
         * 完整阻断原因，尤其历史缺工程、关闭工单或未知实际板位。
         */
        private List<String> blockingReasons;
    }
}
