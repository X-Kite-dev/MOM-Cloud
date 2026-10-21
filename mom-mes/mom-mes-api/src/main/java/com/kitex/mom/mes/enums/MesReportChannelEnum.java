package com.kitex.mom.mes.enums;

/**
 * 报工请求的可信入口。
 *
 * <p>入口由后端控制器或内部事件处理器指定，不接受页面自行伪造。它与报工对象模式相互独立：</n+ * 数量、单件、流转卡和包装描述生产对象，入口描述由谁触发。</p>
 */
public enum MesReportChannelEnum {

    /**
     * PAD 或固定工位终端人工报工。
     */
    STATION("MANUAL"),

    /**
     * PDA 或移动终端人工报工。
     */
    MOBILE("MANUAL"),

    /**
     * 经过独立权限控制的系统集成接口。
     */
    INTEGRATION("API"),

    /**
     * 由受控 IoT 事件处理器触发。
     */
    DEVICE("AUTO_EVENT");

    private final String triggerCode;

    MesReportChannelEnum(String triggerCode) {
        this.triggerCode = triggerCode;
    }

    public String getTriggerCode() {
        return triggerCode;
    }

    public boolean isAutomatic() {
        return this == DEVICE;
    }
}
