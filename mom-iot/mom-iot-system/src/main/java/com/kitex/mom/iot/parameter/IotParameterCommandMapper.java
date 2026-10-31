package com.kitex.mom.iot.parameter;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 每条SQL均显式限定实际租户和工厂；不依赖来源不明的异步线程上下文。 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface IotParameterCommandMapper {
    IotParameterCommand find(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    IotParameterCommand lock(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    List<IotParameterCommand> list(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("task") String task,
            @Param("before") String before);

    int active(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("device") String device,
            @Param("except") String except);

    int insertCommand(IotParameterCommand command);

    int updateState(IotParameterCommand command);

    List<IotParameterReceipt> receipts(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    int insertReceipt(IotParameterReceipt receipt);

    IotParameterSimExecution journal(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    int insertJournal(IotParameterSimExecution execution);
}
