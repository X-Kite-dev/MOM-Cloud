package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.mapper.MesExecutionTemplateMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MesExecutionTemplateDraftRegressionTest {
    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void fullDraftSaveMustWriteNullForClearedScopesAndLimits() {
        var configuration = new MybatisConfiguration();
        var info = TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "template-clear-test"),
                MesExecutionTemplateEntity.class);
        String sql = "<script>UPDATE mes_execution_template <set>" + info.getAllSqlSet(false, "et.")
                + "</set> WHERE id=#{et.id}</script>";
        var draft = new MesExecutionTemplateEntity();
        draft.setId(1L);
        var source = new XMLLanguageDriver().createSqlSource(configuration, sql, Map.class);
        String actual = source.getBoundSql(Map.of("et", draft)).getSql();

        for (String column : new String[]{"material_code", "route_code", "process_route_code", "route_node_code",
                "workshop_code", "worksite_code", "effective_from", "effective_to", "max_single_quantity", "remark"}) {
            assertTrue(actual.matches("(?s).*\\b" + column + "\\s*=\\s*\\?.*"),
                    "清空后必须写NULL: " + column + "; actual=" + actual);
        }
    }

    @Test
    void failedSaveMustNotBeReturnedAsSuccessfulDraft() {
        TenantContextHolder.setTenantId(1L);
        var access = mock(MesActualFactoryAccessService.class);
        when(access.require("write")).thenReturn("F1");
        var nodes = mock(MesIdentifierNodePolicyValidator.class);
        when(nodes.normalize(any(), any())).thenReturn(new MesIdentifierNodePolicyDTO());
        when(nodes.configurationJson(any())).thenReturn("{}");
        var physical = mock(MesPhysicalTransformPolicyValidator.class);
        when(physical.normalize(any(), any())).thenReturn(MesPhysicalTransformPolicyValidator.NONE);
        var service = new MesExecutionTemplateServiceImpl(access, null, nodes,
                mock(MesIdentifierBindingGroupService.class),
                null, physical, null) {
            @Override
            public MesExecutionTemplateEntity getOne(Wrapper<MesExecutionTemplateEntity> query) {
                return draft();
            }

            @Override
            public boolean saveOrUpdate(MesExecutionTemplateEntity row) {
                return false;
            }

            @Override
            public MesExecutionTemplateEntity getById(java.io.Serializable id) {
                return draft();
            }
        };
        ReflectionTestUtils.setField(service, "baseMapper", mock(MesExecutionTemplateMapper.class));

        assertThrows(RuntimeException.class, () -> service.saveDraft(draft()));
    }

    private MesExecutionTemplateEntity draft() {
        var d = new MesExecutionTemplateEntity().setTemplateCode("T1").setTemplateName("通用模板")
                .setStatus("DRAFT").setFactoryCode("F1").setRevisionNo(0L).setVersionNo(1).setReportMode("QUANTITY");
        d.setId(1L);
        d.setTenantId(1L);
        return d;
    }
}
