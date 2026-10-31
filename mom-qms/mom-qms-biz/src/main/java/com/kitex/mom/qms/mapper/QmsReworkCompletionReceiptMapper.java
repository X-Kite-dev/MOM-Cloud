package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsReworkCompletionReceiptEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QmsReworkCompletionReceiptMapper
        extends BaseMapper<QmsReworkCompletionReceiptEntity> {
    @Select("SELECT mom_qms.mom_rework_completion_receipt_ready_v1()")
    Integer schemaVersion();

    @Select(
            """
SELECT id,tenant_id,factory_code,inspection_type,order_id,child_task_id,request_id,
 CASE WHEN OCTET_LENGTH(payload)<=65536 THEN payload ELSE NULL END AS payload,
 payload_fingerprint,received_by,received_at FROM mom_qms.qms_rework_completion_receipt
 WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND order_id=#{orderId}
 ORDER BY id LIMIT 2
""")
    @Results({
        @Result(column = "id", property = "id"),
                @Result(column = "tenant_id", property = "tenantId"),
        @Result(column = "factory_code", property = "factoryCode"),
                @Result(column = "inspection_type", property = "inspectionType"),
        @Result(column = "order_id", property = "orderId"),
                @Result(column = "child_task_id", property = "childTaskId"),
        @Result(column = "request_id", property = "requestId"),
                @Result(column = "payload", property = "payload"),
        @Result(column = "payload_fingerprint", property = "payloadFingerprint"),
                @Result(column = "received_by", property = "receivedBy"),
        @Result(column = "received_at", property = "receivedAt")
    })
    List<QmsReworkCompletionReceiptEntity> byOrder(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("orderId") Long orderId);
}
