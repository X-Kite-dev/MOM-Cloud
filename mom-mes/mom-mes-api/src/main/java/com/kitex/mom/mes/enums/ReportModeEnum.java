package com.kitex.mom.mes.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * 生产报工模式。
 *
 * <p>该枚举是工艺路线、派工任务、PAD 报工和后端执行策略之间的统一契约。
 * aliases 仅用于兼容历史数据，新数据必须保存 code。</p>
 */
@Getter
public enum ReportModeEnum {

    ONE_CODE("一物一码报工", true, false, "CODE", "SN_REPORT"),
    QUANTITY("数量报工", false, false, "NUM", "BATCH_REPORT", "UNITE_REPORT"),
    TRANSFER_CARD("流转卡报工", true, false),
    PACKAGE_ONE_CODE("包装一物一码报工", true, true),
    PACKAGE_QUANTITY("包装数量报工", true, true);

    private final String name;
    private final boolean identifierRequired;
    private final boolean packaging;
    private final String[] aliases;

    ReportModeEnum(String name, boolean identifierRequired, boolean packaging, String... aliases) {
        this.name = name;
        this.identifierRequired = identifierRequired;
        this.packaging = packaging;
        this.aliases = aliases;
    }

    public static ReportModeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("报工模式不能为空");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(mode -> mode.name().equals(normalized)
                        || Arrays.stream(mode.aliases).anyMatch(normalized::equals))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的报工模式: " + code));
    }

    /**
     * 优先使用独立报工模式，兼容历史派工单仍以工序类型承载模式的存量数据。
     */
    public static ReportModeEnum fromCodes(String reportMode, String legacyRouteType) {
        return fromCode(reportMode == null || reportMode.isBlank() ? legacyRouteType : reportMode);
    }

    public String getCode() {
        return name();
    }
}
