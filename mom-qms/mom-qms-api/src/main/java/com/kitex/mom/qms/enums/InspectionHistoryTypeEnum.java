package com.kitex.mom.qms.enums;

/**
 * 质检记录类型
 *
 * @author kitex
 * @date 2022/12/9
 */
public enum InspectionHistoryTypeEnum {

    /** 报检 */
    CREATE,

    /** 追加 */
    APPEND,

    /** 质检结果 */
    RESULT,

    /** 作废 */
    CANCELED,

    /** 免检 */
    EXEMPTION,

    /** 已判不合格后的正式放行/让步处置，不是再次检验结果。 */
    DISPOSITION;
}
