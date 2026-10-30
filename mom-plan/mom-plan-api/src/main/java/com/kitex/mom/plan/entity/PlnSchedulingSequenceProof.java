package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TenantTable
@TableName("pln_scheduling_sequence_proof")
@EqualsAndHashCode(callSuper = true)
public class PlnSchedulingSequenceProof extends BaseEntity<PlnSchedulingSequenceProof> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private String solverCode;
    private String solverVersion;
    private String fingerprint;
    private String evidenceJson;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
