package com.kitex.mom.tpm.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TpmSparePartsApplicationMapper extends MomBaseMapper<TpmSparePartsApplicationEntity> {

    /** 审批事务锁定原申请，防止重复审批或使用客户端回传头信息覆盖来源。 */
    @Select("SELECT * FROM tpm_spare_parts_application WHERE id=#{id} FOR UPDATE")
    TpmSparePartsApplicationEntity lockById(@Param("id") Long id);
}
