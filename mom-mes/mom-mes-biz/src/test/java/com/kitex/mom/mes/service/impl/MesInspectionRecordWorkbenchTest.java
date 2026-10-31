package com.kitex.mom.mes.service.impl;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual XML execution; not a substitute for production PG/interceptor acceptance. */
class MesInspectionRecordWorkbenchTest {
    private JdbcTemplate db;
    private SqlSession session;
    private static final String NS="com.kitex.mom.mes.mapper.MesInspectionGenRecordMapper.";
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:inspection-record-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver"); db=new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA mom_mes");
        db.execute("CREATE TABLE mom_mes.mes_inspection_gen_record(id bigint,tenant_id bigint,factory_code varchar,del_flag varchar,task_code varchar,produce_task_code varchar,rule_code varchar,rule_name varchar,inspection_id bigint,inspection_type varchar,source_request_id varchar,gen_flag varchar,gen_remark varchar,gen_time timestamp,inspection_status varchar,inspection_result varchar,gate_status varchar,qms_total_num int,qms_usable_num int,qms_handle_num int,qms_handle_result varchar,blocked_count int,sync_message varchar,last_sync_time timestamp,last_blocked_time timestamp)");
        insert(9007199254740993L,1,"F1","FIRST","1",11L,"PASSED");
        insert(102,1,"F1","首检","0",null,"ERROR");
        insert(103,1,"F1","first","1",null,"PENDING");
        insert(104,2,"F1","FIRST","1",11L,"PASSED");
        insert(105,1,"F2","FIRST","1",11L,"PASSED");
        insert(106,1,"F1","FIRST","1",11L,"PASSED");
        db.update("UPDATE mom_mes.mes_inspection_gen_record SET del_flag='1' WHERE id=106");
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("records",new JdbcTransactionFactory(),ds));
        try(var xml=getClass().getResourceAsStream("/mapper/MesInspectionGenRecordMapper.xml")) {
            assertNotNull(xml,"workbench must use a scoped read mapper");
            new XMLMapperBuilder(xml,config,"MesInspectionGenRecordMapper.xml",config.getSqlFragments()).parse();
        }
        session=new SqlSessionFactoryBuilder().build(config).openSession(true);
    }
    @AfterEach void close(){if(session!=null)session.close();TenantContextHolder.clear();FactoryContextHolder.clear();}
    @Test void listAndDetailAreExactlyScopedAndKeepLargeIdsAndUnknownQuantities(){
        var p=params();List<MesInspectionGenRecordEntity> rows=session.selectList(NS+"workbenchRows",p);
        assertEquals(3,rows.size());assertEquals(9007199254740993L,rows.get(0).getId());
        assertNull(rows.get(0).getQmsTotalNum());assertEquals(0,rows.get(0).getQmsUsableNum());
        assertEquals(3L,((Number)session.selectOne(NS+"workbenchCount",p)).longValue());
        p.put("id",105L);assertNull(session.selectOne(NS+"workbenchDetail",p));
        p.put("id",104L);assertNull(session.selectOne(NS+"workbenchDetail",p));
        p.put("id",9007199254740993L);assertNotNull(session.selectOne(NS+"workbenchDetail",p));
    }
    @Test void stageFilterIncludesLegacyChineseAndCaseVariantsWithoutCrossingScope(){
        var p=params();query(p).put("inspectionType","FIRST");
        assertEquals(3,session.selectList(NS+"workbenchRows",p).size());
        query(p).put("inspectionType","LAST");assertTrue(session.selectList(NS+"workbenchRows",p).isEmpty());
    }
    @Test void generationRequiresBothConfirmedFlagAndRealInspectionId(){
        var p=params();query(p).put("generationStatus","GENERATED");
        List<MesInspectionGenRecordEntity> rows=session.selectList(NS+"workbenchRows",p);
        assertEquals(List.of(9007199254740993L),rows.stream().map(MesInspectionGenRecordEntity::getId).toList());
        query(p).put("generationStatus","NOT_GENERATED");assertEquals(2,session.selectList(NS+"workbenchRows",p).size());
    }
    @Test void filteringPrecedesPaginationAndUsesDeterministicNewestOrder(){
        var p=params();p.put("offset",1L);p.put("limit",1);
        List<MesInspectionGenRecordEntity> rows=session.selectList(NS+"workbenchRows",p);
        assertEquals(List.of(103L),rows.stream().map(MesInspectionGenRecordEntity::getId).toList());
        query(p).put("gateStatus","ERROR");p.put("offset",0L);
        rows=session.selectList(NS+"workbenchRows",p);assertEquals(102L,rows.get(0).getId());
        query(p).put("taskCode","T-other");assertEquals(0L,((Number)session.selectOne(NS+"workbenchCount",p)).longValue());
    }
    @Test void syncIsAPostCommandAndReadRoutesKeepTheirExistingMenuPermission() throws Exception {
        var method=com.kitex.mom.mes.controller.MesQualityGateController.class.getMethod("sync",String.class);
        assertNotNull(method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class));
        assertNull(method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class));
        var controller=com.kitex.mom.mes.controller.MesInspectionGenRecordController.class;
        for(var m:controller.getMethods())if(m.getName().equals("workbench")||m.getName().equals("workbenchDetail"))
            assertEquals("@pms.hasPermission('mesInspectionGenRecord:show')",m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        assertEquals(2,Arrays.stream(controller.getMethods()).filter(m->Set.of("workbench","workbenchDetail").contains(m.getName())).count());
    }
    private Map<String,Object> params(){var p=new HashMap<String,Object>();p.put("tenant",1L);p.put("factory","F1");p.put("q",new HashMap<String,Object>());p.put("offset",0L);p.put("limit",20);return p;}
    @SuppressWarnings("unchecked") private Map<String,Object> query(Map<String,Object> p){return (Map<String,Object>)p.get("q");}
    private void insert(long id,long tenant,String factory,String phase,String flag,Long inspection,String status){
        db.update("INSERT INTO mom_mes.mes_inspection_gen_record(id,tenant_id,factory_code,del_flag,task_code,produce_task_code,rule_name,inspection_id,inspection_type,gen_flag,gen_time,gate_status,qms_usable_num) VALUES(?,?,?,'0','T1','P1','首检规则',?,?,?,'2026-10-31 08:00:00',?,0)",id,tenant,factory,inspection,phase,flag,status);
    }
}
