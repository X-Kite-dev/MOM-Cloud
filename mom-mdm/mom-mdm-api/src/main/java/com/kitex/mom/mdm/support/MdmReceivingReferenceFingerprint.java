package com.kitex.mom.mdm.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 结构身份协议：不含展示名称，供MDM和消费方按同一原文核对。 */
public final class MdmReceivingReferenceFingerprint {
    private MdmReceivingReferenceFingerprint() {}

    public static String of(String tenant, String factory, Station st, Point p) {
        try {
            var identity =
                    List.of(
                            "MDM_RECEIVE_REF@1",
                            tenant,
                            factory,
                            st.id(),
                            st.code(),
                            st.lineId(),
                            st.lineCode(),
                            st.workshopId(),
                            st.workshopCode(),
                            st.factoryId(),
                            st.industryId(),
                            p.id(),
                            p.code(),
                            p.warehouseId(),
                            p.warehouseCode(),
                            p.areaId(),
                            p.shelfId());
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            new ObjectMapper()
                                                    .writeValueAsString(identity)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("位置结构身份不完整", e);
        }
    }
}
