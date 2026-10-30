package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.mom.mdm.mapper.MdmFactoryPlanningPolicyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实SQL验证全历史发布判断，不依赖逻辑删除或组织拦截器。
 */
class MdmPlanningPublicationHistoryTest {

    private JdbcTemplate db;

    private MdmFactoryPlanningPolicyMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:publication-history-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_mdm");
        db.execute("CREATE TABLE mom_mdm.mdm_factory_planning_policy(tenant_id bigint,factory_code varchar(64),status varchar(64),del_flag varchar(1),published_by varchar(64),published_time timestamp,publish_request_id varchar(64),publish_command_fingerprint varchar(64),impact_confirmation_no varchar(64),submitted_by varchar(64),submitted_time timestamp,initial_activation_snapshot text)");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.addMapper(MdmFactoryPlanningPolicyMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MdmFactoryPlanningPolicyMapper.class);
    }

    @AfterEach
    void cleanup() {
        if (db != null)
            db.execute("SHUTDOWN");
    }

    @Test
    void softDeletedRetiredVersionStillCountsWithoutLeakingOtherScopes() {
        db.update("INSERT INTO mom_mdm.mdm_factory_planning_policy(tenant_id,factory_code,status,del_flag,published_by,published_time,publish_request_id) VALUES(1,'F1','RETIRED','1','publisher',CURRENT_TIMESTAMP,'p1'),(2,'F1','ACTIVE','0','publisher',CURRENT_TIMESTAMP,'p2'),(1,'F2','ACTIVE','0','publisher',CURRENT_TIMESTAMP,'p3')");
        assertEquals(1, mapper.countEverPublished(1L, "F1"));
    }

    @Test
    void partialPublicationAuditCannotPretendToBeAnUnpublishedDraft() {
        db.update("INSERT INTO mom_mdm.mdm_factory_planning_policy(tenant_id,factory_code,status,del_flag,publish_command_fingerprint) VALUES(1,'F1','DRAFT','0','partial')");
        db.update("INSERT INTO mom_mdm.mdm_factory_planning_policy(tenant_id,factory_code,status,del_flag,impact_confirmation_no) VALUES(1,'F1','DRAFT','1','partial')");
        assertEquals(2, mapper.countEverPublished(1L, "F1"));
    }

    @Test
    void draftsAloneDoNotCountButPublicationEvidenceAndUnknownStatusDo() {
        db.update("INSERT INTO mom_mdm.mdm_factory_planning_policy(tenant_id,factory_code,status,del_flag,published_by,published_time,publish_request_id) VALUES(1,'F1','DRAFT','0',NULL,NULL,NULL),(1,'F1','DRAFT','0','publisher',NULL,NULL),(1,'F1',NULL,'0',NULL,NULL,NULL)");
        assertEquals(2, mapper.countEverPublished(1L, "F1"));
        assertEquals(0, mapper.countEverPublished(1L, "F2"));
    }
}
