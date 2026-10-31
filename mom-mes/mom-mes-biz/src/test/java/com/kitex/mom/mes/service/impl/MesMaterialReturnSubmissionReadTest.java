package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import com.kitex.mom.mes.mapper.MesWarehouseStockFeedJudgeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MesMaterialReturnSubmissionReadTest {
    private MesWarehouseStockFeedJudgeServiceImpl service;
    private MesWarehouseStockFeedJudgeMapper requests;
    private final String requestId = "RETURN-ORIGINAL-0001";
    @BeforeEach void setup() throws Exception {
        TenantContextHolder.setTenantId(1L); FactoryContextHolder.setFactoryCode("F1");
        var user = new MomUser(7L,"operator",1L,null,null,null,"Operator",null,1L,"unused",true,true,"USER",true,true,List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"unused",List.of()));
        var factories=mock(RemoteMdmFactoryAccessService.class);when(factories.check(any())).thenReturn(R.ok(true));
        var dependencies=new HashMap<Class<?>,Object>();dependencies.put(MesActualFactoryAccessService.class,new MesActualFactoryAccessService(factories));
        var constructor=MesWarehouseStockFeedJudgeServiceImpl.class.getConstructors()[0];
        service=(MesWarehouseStockFeedJudgeServiceImpl)constructor.newInstance(Arrays.stream(constructor.getParameterTypes()).map(type->dependencies.computeIfAbsent(type,ignored->mock(type))).toArray());
        requests=mock(MesWarehouseStockFeedJudgeMapper.class);ReflectionTestUtils.setField(service,"baseMapper",requests);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"return-read"),MesWarehouseStockFeedJudgeEntity.class);
    }
    @AfterEach void clear(){TenantContextHolder.clear();FactoryContextHolder.clear();SecurityContextHolder.clearContext();}
    private MesWarehouseStockFeedJudgeEntity row(){var r=new MesWarehouseStockFeedJudgeEntity().setFactoryCode("F1").setApplicantId(7L).setApplicantCode("operator").setRequestId(requestId).setWorkflowVersion("V2");r.setId(101L);r.setTenantId(1L);return r;}
    @Test void actualSubmissionContextIsAuthenticatedWithoutInventoryReads(){
        var c=service.submissionContext();assertNotNull(c);assertEquals("1",c.tenantId());assertEquals("7",c.actorUserId());assertEquals("operator",c.actorCode());assertEquals("F1",c.factoryCode());verifyNoMoreInteractions(requests);
    }
    @Test void sourceLookupUsesExactOwnerAndRequestFilters(){
        when(requests.selectOne(any(Wrapper.class))).thenAnswer(call->{Wrapper<?> w=call.getArgument(0);String sql=w.getSqlSegment();assertTrue(sql.contains("tenant_id ="));assertTrue(sql.contains("factory_code ="));assertTrue(sql.contains("applicant_id ="));assertTrue(sql.contains("applicant_code ="));assertTrue(sql.contains("request_id ="));assertFalse(sql.contains("LIKE"));return row();});
        var receipt=service.submissionReceipt(requestId);assertNotNull(receipt);assertEquals(requestId,receipt.requestId());assertEquals(101L,receipt.request().getId());verify(requests).selectOne(any(Wrapper.class));verifyNoMoreInteractions(requests);
    }
    @Test void missingRequestIsAnObservationAndDoesNotCreateOrCancelAnything(){
        var receipt=service.submissionReceipt(requestId);assertNotNull(receipt);assertNull(receipt.request());assertEquals(requestId,receipt.requestId());verify(requests).selectOne(any(Wrapper.class));verifyNoMoreInteractions(requests);
    }
    @Test void foreignReturnedRowsAreRejectedEvenWithAScopedQuery(){
        for(String kind:List.of("tenant","factory","actor","code","request","legacy")){
            var row=row();switch(kind){case "tenant"->row.setTenantId(2L);case "factory"->row.setFactoryCode("F2");case "actor"->row.setApplicantId(8L);case "code"->row.setApplicantCode("other");case "request"->row.setRequestId("RETURN-OTHER-00001");case "legacy"->row.setWorkflowVersion("LEGACY");}
            when(requests.selectOne(any(Wrapper.class))).thenReturn(row);assertThrows(RuntimeException.class,()->service.submissionReceipt(requestId),kind);
        }
    }
    @Test void invalidRequestAndUntrustedTenantCannotReachQueries(){
        for(String id:List.of("short"," RETURN-ORIGINAL-0001",requestId+"\n","x".repeat(101)))assertThrows(RuntimeException.class,()->service.submissionReceipt(id));
        TenantContextHolder.setTenantId(2L);assertThrows(RuntimeException.class,()->service.submissionContext());verifyNoMoreInteractions(requests);
    }
    @Test void narrowReadCapabilitiesKeepSubmitPermissionWithoutGrantingReviewOrGeneralLists() throws Exception {
        for(var method:List.of(
                com.kitex.mom.mes.controller.MesWarehouseStockFeedJudgeController.class.getMethod("submissionContext"),
                com.kitex.mom.mes.controller.MesWarehouseStockFeedJudgeController.class.getMethod("submissionReceipt",String.class),
                MesWarehouseStockFeedJudgeServiceImpl.class.getMethod("submissionContext"),
                MesWarehouseStockFeedJudgeServiceImpl.class.getMethod("submissionReceipt",String.class))) {
            assertEquals("@pms.hasPermission('stockFeedJudge:submit')",method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        }
    }
}
