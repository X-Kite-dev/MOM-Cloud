package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import lombok.Data;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MdmReceivingLocationMapper {
    @Data
    class PathRow {
        private Long id, lineId, workshopId, factoryId, industryId;
        private String code, name, lineCode, workshopCode;
        private Boolean healthy;
    }

    @Data
    class PointRow {
        private Long id, warehouseId, areaId, shelfId;
        private String code, name, warehouseCode, warehouseName;
        private Boolean healthy;
    }

    @Data
    class Row {
        private Long tenantId, actorId, stationId, locationId, revision, recordedAt;
        private String factoryCode,
                requestId,
                commandJson,
                fingerprint,
                outcome,
                state,
                snapshotJson,
                snapshotHash,
                reason;
    }

    @Select(
            "SELECT count(*) FROM mom_mdm.mdm_factory_model WHERE tenant_id=#{tenant} AND"
                    + " code=#{factory} AND type='FACTORY' AND level=2 AND status=true AND"
                    + " del_flag='0'")
    int factoryCount(@Param("tenant") Long t, @Param("factory") String f);

    @Select(
            "SELECT permission_type FROM mom_mdm.mdm_user_factory_model_bind b JOIN"
                + " mom_mdm.mdm_factory_model f ON f.id=b.factory_model_id AND"
                + " f.tenant_id=b.tenant_id WHERE b.tenant_id=#{tenant} AND b.user_id=#{actor} AND"
                + " b.status=true AND b.del_flag='0' AND f.code=#{factory} AND f.type='FACTORY' AND"
                + " f.level=2 AND f.status=true AND f.del_flag='0'")
    List<String> permissions(
            @Param("tenant") Long t, @Param("factory") String f, @Param("actor") Long a);

    @Select(
            "SELECT CASE WHEN to_regclass('mom_mdm.mdm_station_receiving_journal') IS NULL THEN 0"
                    + " ELSE 1 END")
    int installed();

    @Select("SELECT mom_mdm.mom_station_receiving_ready_v1()")
    Integer ready();

    @Select("SELECT current_setting('transaction_isolation')")
    String isolation();

    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    void lock(String key);

    String PATH =
            "SELECT s.id,s.code,s.name,l.id line_id,l.code line_code,w.id workshop_id,w.code"
                + " workshop_code,f.id factory_id,i.id industry_id,(s.status=true AND l.status=true"
                + " AND w.status=true AND f.status=true AND i.status=true AND s.del_flag='0' AND"
                + " l.del_flag='0' AND w.del_flag='0' AND f.del_flag='0' AND i.del_flag='0')"
                + " healthy FROM mom_mdm.mdm_factory_model s JOIN mom_mdm.mdm_factory_model l ON"
                + " l.id=s.parent_id AND l.tenant_id=s.tenant_id JOIN mom_mdm.mdm_factory_model w"
                + " ON w.id=l.parent_id AND w.tenant_id=s.tenant_id JOIN mom_mdm.mdm_factory_model"
                + " f ON f.id=w.parent_id AND f.tenant_id=s.tenant_id JOIN"
                + " mom_mdm.mdm_factory_model i ON i.id=f.parent_id AND i.tenant_id=s.tenant_id"
                + " WHERE s.tenant_id=#{tenant} AND f.code=#{factory} AND s.type='STATION' AND"
                + " s.level=5 AND l.type='LINE' AND l.level=4 AND w.type='WORKSHOP' AND w.level=3"
                + " AND f.type='FACTORY' AND f.level=2 AND i.type='INDUSTRY' AND i.level=1";

    @Select(PATH + " AND s.code=#{code} LIMIT 2")
    List<PathRow> path(
            @Param("tenant") Long t, @Param("factory") String f, @Param("code") String code);

    @Select(PATH + " AND s.id=#{id} LIMIT 2")
    List<PathRow> pathId(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(PATH + " AND s.id=#{id} LIMIT 2 FOR UPDATE")
    List<PathRow> lockPath(
            @Param("tenant") Long t, @Param("factory") String f, @Param("id") Long id);

    String POINT =
            "SELECT l.id,l.location_code code,l.location_name name,w.id"
                + " warehouse_id,l.warehouse_code,w.warehouse_name,a.id area_id,p.id"
                + " shelf_id,(l.location_level=3 AND l.status=true AND l.del_flag='0' AND"
                + " p.status=true AND p.del_flag='0' AND a.status=true AND a.del_flag='0' AND"
                + " w.status=true AND w.del_flag='0' AND NOT EXISTS(SELECT 1 FROM"
                + " mom_mdm.mdm_warehouse_location ch WHERE ch.tenant_id=l.tenant_id AND"
                + " ch.factory_code=l.factory_code AND ch.parent_id=l.id AND ch.del_flag='0') AND"
                + " NOT EXISTS(SELECT 1 FROM mom_mdm.mdm_warehouse_location du WHERE"
                + " du.tenant_id=l.tenant_id AND du.factory_code=l.factory_code AND"
                + " du.warehouse_code=l.warehouse_code AND du.location_code=l.location_code AND"
                + " du.id!=l.id AND du.location_level=3 AND du.status=true AND du.del_flag='0'))"
                + " healthy FROM mom_mdm.mdm_warehouse_location l LEFT JOIN"
                + " mom_mdm.mdm_warehouse_location p ON p.id=l.parent_id AND"
                + " p.tenant_id=l.tenant_id AND p.factory_code=l.factory_code AND"
                + " p.warehouse_code=l.warehouse_code AND p.location_level=2 LEFT JOIN"
                + " mom_mdm.mdm_warehouse_location a ON a.id=p.parent_id AND"
                + " a.tenant_id=l.tenant_id AND a.factory_code=l.factory_code AND"
                + " a.warehouse_code=l.warehouse_code AND a.location_level=1 LEFT JOIN"
                + " mom_mdm.mdm_warehouse w ON w.tenant_id=l.tenant_id AND"
                + " w.factory_code=l.factory_code AND w.warehouse_code=l.warehouse_code AND"
                + " w.status=true AND w.del_flag='0' WHERE l.tenant_id=#{tenant} AND"
                + " l.factory_code=#{factory}";

    @Select(POINT + " AND l.id=#{id}")
    List<PointRow> point(@Param("tenant") Long t, @Param("factory") String f, @Param("id") Long id);

    @Select(
            "SELECT id FROM mom_mdm.mdm_warehouse_location WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} FOR UPDATE")
    Long lockPoint(@Param("tenant") Long t, @Param("factory") String f, @Param("id") Long id);

    @Select(
            "<script>"
                    + POINT
                    + " AND l.id IN <foreach collection='ids' item='id' open='(' separator=','"
                    + " close=')'>#{id}</foreach></script>")
    List<PointRow> points(
            @Param("tenant") Long t, @Param("factory") String f, @Param("ids") List<Long> ids);

    @Select(
            "<script>SELECT * FROM ("
                    + POINT
                    + " AND l.location_level=3 AND (LOWER(l.location_code) LIKE LOWER(#{pattern})"
                    + " ESCAPE '!' OR LOWER(l.location_name) LIKE LOWER(#{pattern}) ESCAPE '!'))"
                    + " candidates WHERE healthy=true <if test='after != null'>AND id &gt;"
                    + " #{after}</if> ORDER BY id LIMIT 21</script>")
    List<PointRow> candidates(
            @Param("tenant") Long t,
            @Param("factory") String f,
            @Param("pattern") String p,
            @Param("after") Long after);

    String ROWS =
            "SELECT"
                + " tenant_id,factory_code,request_id,actor_id,station_id,location_id,revision,command_json,fingerprint,outcome,state,snapshot_json,snapshot_hash,reason,recorded_at"
                + " FROM mom_mdm.mdm_station_receiving_journal WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory}";

    @Select(ROWS + " AND request_id=#{request}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row request(@Param("tenant") Long t, @Param("factory") String f, @Param("request") String req);

    @Select(
            ROWS
                    + " AND station_id=#{station} AND location_id=#{location} AND outcome='APPLIED'"
                    + " ORDER BY revision DESC LIMIT 1")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Row latest(
            @Param("tenant") Long t,
            @Param("factory") String f,
            @Param("station") Long s,
            @Param("location") Long l);

    @Select(
            "SELECT * FROM (SELECT j.*,ROW_NUMBER() OVER(PARTITION BY location_id ORDER BY revision"
                + " DESC) rn FROM mom_mdm.mdm_station_receiving_journal j WHERE tenant_id=#{tenant}"
                + " AND factory_code=#{factory} AND station_id=#{station} AND outcome='APPLIED')"
                + " current_rows WHERE rn=1 ORDER BY location_id LIMIT 101")
    List<Row> current(
            @Param("tenant") Long t, @Param("factory") String f, @Param("station") Long s);

    @Select(
            "<script>"
                    + ROWS
                    + " AND station_id=#{station} AND location_id=#{location} AND outcome='APPLIED'"
                    + " <if test='before != null'>AND revision &lt; #{before}</if> ORDER BY"
                    + " revision DESC LIMIT 21</script>")
    List<Row> history(
            @Param("tenant") Long t,
            @Param("factory") String f,
            @Param("station") Long s,
            @Param("location") Long l,
            @Param("before") Long before);

    @Insert(
            "INSERT INTO"
                + " mom_mdm.mdm_station_receiving_journal(tenant_id,factory_code,request_id,actor_id,station_id,location_id,revision,command_json,fingerprint,outcome,state,snapshot_json,snapshot_hash,reason,recorded_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{requestId},#{actorId},#{stationId},#{locationId},#{revision},#{commandJson},#{fingerprint},#{outcome},#{state},#{snapshotJson},#{snapshotHash},#{reason},#{recordedAt})")
    int insert(Row r);
}
