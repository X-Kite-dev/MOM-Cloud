package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.jackson.MomJavaTimeModule;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.mapper.MesInspectionGenRecordMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class MesInspectionRecordQueryTest {
 private final ObjectMapper json=new ObjectMapper().registerModule(new MomJavaTimeModule());
 private RemoteMdmFactoryAccessService factories;
 private MesInspectionGenRecordMapper mapper;
 private List<MesInspectionGenRecordEntity> rows;
 @BeforeEach void setup(){
  TenantContextHolder.setTenantId(1L);FactoryContextHolder.setFactoryCode("F1");
  var user=new MomUser(7L,"operator",1L,null,null,null,"Operator",null,1L,"unused",true,true,"USER",true,true,List.of());
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"unused",List.of()));
  factories=mock(RemoteMdmFactoryAccessService.class);when(factories.check(any())).thenReturn(R.ok(true));
  rows=new ArrayList<>();mapper=mock(MesInspectionGenRecordMapper.class,call->switch(call.getMethod().getName()){
   case "workbenchRows" -> rows;case "workbenchCount" -> (long)rows.size();case "workbenchDetail" -> rows.isEmpty()?null:rows.get(0);default -> null;
  });
 }
 @AfterEach void clear(){TenantContextHolder.clear();FactoryContextHolder.clear();SecurityContextHolder.clearContext();}
 @Test void returnsActualIdentityAndNormalizedQueryWithoutChangingCaller(){
  Object q=query(Map.of("taskCode"," T1 "));
  var data=json.valueToTree(call("page",q));
  assertEquals("MES_INSPECTION_RECORD_WORKBENCH_V1",data.path("contract").asText());assertEquals("1",data.path("tenantId").asText());assertEquals("F1",data.path("factoryCode").asText());assertEquals("7",data.path("actorUserId").asText());assertEquals("T1",data.path("query").path("taskCode").asText());
  assertEquals(" T1 ",json.valueToTree(q).path("taskCode").asText());assertEquals(0,data.path("rows").size());
 }
 @Test void invalidPageAndFiltersAreRejectedBeforeSql(){
  for(var fields:List.of(Map.of("current",0),Map.of("size",101),Map.of("inspectionType","DROP"),Map.of("gateStatus","OTHER"),Map.of("generationStatus","UNKNOWN"),Map.of("taskCode","a\nb")))
   assertThrows(CheckedException.class,()->call("page",query(fields)));
  verifyZeroInteractions(mapper);
 }
 @Test void deniedFactoryAndInvalidAuthenticatedTenantNeverReadRows(){
  when(factories.check(any())).thenReturn(R.ok(false));assertThrows(CheckedException.class,()->call("page",query(Map.of())));
  when(factories.check(any())).thenReturn(R.ok(true));TenantContextHolder.setTenantId(2L);assertThrows(CheckedException.class,()->call("page",query(Map.of())));verifyZeroInteractions(mapper);
 }
 @Test void foreignRowsFailClosedAndMissingDetailDoesNotBecomeAnEmptyHealthyRecord(){
  var row=new MesInspectionGenRecordEntity().setFactoryCode("F2");row.setTenantId(1L);row.setId(101L);rows.add(row);
  assertThrows(CheckedException.class,()->call("page",query(Map.of())));rows.clear();assertThrows(CheckedException.class,()->call("detail",101L));
 }
 private Object query(Map<?,?> fields){return assertDoesNotThrow(()->json.convertValue(fields,Class.forName("com.kitex.mom.mes.dto.MesInspectionRecordWorkbenchDTO$Query")));}
 private Object call(String name,Object input){
  var type=assertDoesNotThrow(()->Class.forName("com.kitex.mom.mes.service.impl.MesInspectionRecordQuery"));
  try{
   Object service=type.getConstructors()[0].newInstance(mapper,new MesActualFactoryAccessService(factories));
   var parameter=name.equals("detail")?Long.class:input.getClass();return type.getMethod(name,parameter).invoke(service,input);
  }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}catch(ReflectiveOperationException e){throw new AssertionError(e);}
 }
}
