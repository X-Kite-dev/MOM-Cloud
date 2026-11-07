package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPackagingSealMemberEntity;
import org.apache.ibatis.annotations.Mapper;

/** 初封成员永久来源账；同真实内容首次封箱只能写一次，不替代拆箱重组履历。 */
@Mapper
public interface MesPackagingSealMemberMapper extends BaseMapper<MesPackagingSealMemberEntity> {
}
