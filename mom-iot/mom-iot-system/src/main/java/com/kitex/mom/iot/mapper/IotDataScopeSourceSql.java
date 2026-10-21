package com.kitex.mom.iot.mapper;
import java.util.Map;
/** 表名只由封闭枚举选择；字段值全部参数绑定，无任意SQL入口。 */
public class IotDataScopeSourceSql {
    private static String table(Map<String,Object> p) {
        if("PROPERTY".equals(p.get("kind")))return "iot_device_property";
        if("EVENT".equals(p.get("kind")))return "iot_device_event";
        throw new IllegalArgumentException("只允许PROPERTY/EVENT来源");
    }
    private static String predicate() {
        return "p.del_flag='0' AND p.scope_verified=false AND p.device_id=#{deviceId} AND p.device_code=#{deviceCode}"
            +" AND p.product_id=#{productId} AND p.product_code=#{productCode}"
            +" AND (p.tenant_id IS NULL OR p.tenant_id=#{tenantId})"
            +" AND (p.factory_code IS NULL OR trim(p.factory_code)='' OR p.factory_code=#{factory})";
    }
    public static String candidates(Map<String,Object> p) {
        String time="PROPERTY".equals(p.get("kind"))?"collect_time":"event_time";
        return "SELECT p.id,p.identifier,p."+time+"::text AS observed_time FROM "+table(p)+" p WHERE "+predicate()
            +" AND NOT EXISTS (SELECT 1 FROM iot_data_scope_item i WHERE i.source_kind=#{kind} AND i.source_id=p.id AND i.binding_state IN ('PENDING','APPROVED'))"
            +(p.get("beforeId")==null?"":" AND p.id < #{beforeId}")+" ORDER BY p.id DESC LIMIT 51";
    }
    public static String sources(Map<String,Object> p) {
        String result="<script>SELECT p.id,to_jsonb(p)::text AS snapshot FROM "+table(p)+" p WHERE "+predicate()
            +" AND p.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY p.id";
        return result+(Boolean.TRUE.equals(p.get("lock"))?" FOR UPDATE":"")+"</script>";
    }
    public static String evidenceBytes(Map<String,Object> p) {
        return "<script>WITH selected AS (SELECT p.* FROM "+table(p)+" p WHERE "+predicate()
            +" AND p.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY p.id"
            +(Boolean.TRUE.equals(p.get("lock"))?" FOR UPDATE":"")+") SELECT CASE WHEN count(*)=#{expectedCount} THEN COALESCE(sum(octet_length(to_jsonb(selected)::text)),0) ELSE -1 END FROM selected</script>";
    }
    public static String approve(Map<String,Object> p) {
        return "UPDATE "+table(p)+" p SET tenant_id=#{tenantId},factory_code=#{factory},scope_verified=true WHERE "+predicate()+" AND p.id=#{sourceId}";
    }
}
