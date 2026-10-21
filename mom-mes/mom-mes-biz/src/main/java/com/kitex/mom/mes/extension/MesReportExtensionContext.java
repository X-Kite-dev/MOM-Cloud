package com.kitex.mom.mes.extension;

import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.enums.ReportModeEnum;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 行业报工扩展可读取的不可变上下文。
 *
 * <p>扩展不会取得可修改的派工实体或报工命令，因此不能绕过核心数量、工序、质量和资源门禁。</p>
 */
@Getter
@Builder
public class MesReportExtensionContext {
    private final String requestId;
    private final String factoryCode;
    private final String taskCode;
    private final String arrangeCode;
    private final String produceTaskCode;
    private final String materialCode;
    private final String routeCode;
    private final String worksiteCode;
    private final String deviceCode;
    private final String canonicalIdentifier;
    private final String submittedIdentifier;
    private final String identifierType;
    private final BigDecimal quantity;
    private final ReportModeEnum reportMode;
    private final MesReportChannelEnum channel;
}
