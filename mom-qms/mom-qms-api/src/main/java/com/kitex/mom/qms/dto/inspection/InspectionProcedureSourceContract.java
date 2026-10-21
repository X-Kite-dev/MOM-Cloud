package com.kitex.mom.qms.dto.inspection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * MES到QMS工序质检创建的固定V2原文算法。
 *
 * <p>两端必须对完全相同的创建参数得到相同摘要；不能仅凭来源请求号返回一条参数不同的旧任务。
 * 长度前缀保证字段值中的换行、分隔符和空值不会产生拼接歧义。</p>
 */
public final class InspectionProcedureSourceContract {
    public static final String VERSION = "QMS_PROCEDURE_SOURCE_V2";
    public static final String REQUEST_PREFIX = "MES_QMS_V2:";

    private InspectionProcedureSourceContract() {
    }

    /** 返回跨服务保存和核对的完整固定原文，不包含其自身摘要字段。 */
    public static String snapshot(InspectionProcedureCreateParam source) {
        if (source == null) {
            throw new IllegalArgumentException("工序质检创建参数不能为空");
        }
        StringBuilder result = new StringBuilder(VERSION).append('\n');
        append(result, "id", source.getId());
        append(result, "taskCode", source.getTaskCode());
        append(result, "sourceTaskCode", source.getSourceTaskCode());
        append(result, "sourceRequestId", source.getSourceRequestId());
        append(result, "labelType", source.getLabelType());
        append(result, "name", source.getName());
        append(result, "matCode", source.getMatCode());
        append(result, "matName", source.getMatName());
        append(result, "matSpec", source.getMatSpec());
        append(result, "processCode", source.getProcessCode());
        append(result, "processName", source.getProcessName());
        append(result, "standardId", source.getStandardId());
        append(result, "batchNo", source.getBatchNo());
        append(result, "num", source.getNum());
        append(result, "inspectionNum", source.getInspectionNum());
        append(result, "isCreate", source.getIsCreate());
        append(result, "deptCode", source.getDeptCode());
        append(result, "orderNo", source.getOrderNo());
        append(result, "orderId", source.getOrderId());
        append(result, "matBarcode", source.getMatBarcode());
        return result.toString();
    }

    /** SHA-256以小写十六进制传输，Java和数据库均按UTF-8原字节核对。 */
    public static String fingerprint(InspectionProcedureCreateParam source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(snapshot(source).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JVM不支持SHA-256", exception);
        }
    }

    private static void append(StringBuilder target, String field, Object value) {
        String text = value == null ? null : String.valueOf(value);
        target.append(field).append(':');
        if (text == null) {
            target.append(-1).append(':');
        } else {
            target.append(text.length()).append(':').append(text);
        }
        target.append('\n');
    }
}
