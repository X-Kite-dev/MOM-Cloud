package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmProcessProcedureSettingEntity;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.math.BigDecimal;
import java.util.List;

/** 节点设置持久化与原DB nullable投影；不让实体初始化值掩盖真实NULL。 */
@Mapper
public interface MdmProcessProcedureSettingMapper extends BaseMapper<MdmProcessProcedureSettingEntity> {
	/** 按真实组织精确读取原可空字段；额外一行用于调用方检测超量，不截断后推断完整。 */
	@Select("SELECT id,tenant_id,factory_code,route_code,route_node_code,process_code,material_consumption_schema,"
			+ "is_back_flush,is_mat_weight_convert,is_automate,time_check,mat_weight_convert,default_report_num,time_val "
			+ "FROM mdm_process_procedure_setting WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND route_code=#{route} AND route_node_code=#{node} AND del_flag='0' ORDER BY id LIMIT 2")
	List<NullableRow> nullableByNode(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("route") String route, @Param("node") String node);

	/** 按真实组织精确读取原可空字段；额外一行用于调用方检测超量，不截断后推断完整。 */
	@Select("SELECT id,tenant_id,factory_code,route_code,route_node_code,process_code,material_consumption_schema,"
			+ "is_back_flush,is_mat_weight_convert,is_automate,time_check,mat_weight_convert,default_report_num,time_val "
			+ "FROM mdm_process_procedure_setting WHERE tenant_id=#{tenant} AND factory_code=#{factory} "
			+ "AND route_code=#{route} AND del_flag='0' ORDER BY id LIMIT 10001")
	List<NullableRow> nullableByRoute(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("route") String route);

	/** 原数据库可空字段，没有实体初始化默认值；NULL不因MyBatis跳过setter而变成false。 */
	@Data
	class NullableRow {
		/** 节点设置原记录ID。 */
		private Long id;
		/** 原记录所属认证租户ID。 */
		private Long tenantId;
		/** 原记录所属实际工厂编码。 */
		private String factoryCode;
		/** 原设置所属路线编码。 */
		private String routeCode;
		/** 原设置所属稳定节点RN-id。 */
		private String routeNodeCode;
		/** 原设置对应的主工序编码，不替代节点身份。 */
		private String processCode;
		/** 原耗料代际：历史null或MATERIAL_CONSUMPTION_V2；不根据实体默认值推断。 */
		private String materialConsumptionSchema;
		/** 旧统一倒冲开关原DB值；V2必须真实NULL，不能强制擦除false/true。 */
		private Boolean isBackFlush;
		/** 原DB重量转换开关，NULL保持未知。 */
		private Boolean isMatWeightConvert;
		/** 原DB自动报工开关，NULL保持未知。 */
		private Boolean isAutomate;
		/** 原DB转序时间检查开关，NULL保持未知。 */
		private Boolean timeCheck;
		/** 原重量转换参数，沿原设置单位和精度，不另设默认值。 */
		private BigDecimal matWeightConvert;
		/** 原默认报工数量，沿原物料单位，NULL不补零。 */
		private BigDecimal defaultReportNum;
		/** 原转序时长参数，沿原时间规则单位，NULL不补零。 */
		private BigDecimal timeVal;
	}
}
