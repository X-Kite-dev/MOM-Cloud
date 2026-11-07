package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 封箱时真实原内容的永久成员身份；同内容不能再次首次封箱。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_packaging_seal_member")
@Schema(description = "封箱时真实原内容的永久成员身份；同内容不能再次首次封箱。")
public class MesPackagingSealMemberEntity extends BaseEntity<MesPackagingSealMemberEntity> {

	/** 实际工厂编码。 */
	@Schema(description = "实际工厂编码")
	private String factoryCode;

	/** 永久原封箱命令主键。 */
	@Schema(description = "永久原封箱命令主键")
	private Long sealId;

	/** 首次封箱原STAGED内容主键，组织内唯一。 */
	@Schema(description = "首次封箱原STAGED内容主键，组织内唯一")
	private Long recordId;

	/** 成员来源有效GOOD报告主键。 */
	@Schema(description = "成员来源有效GOOD报告主键")
	private Long reportId;

	/** 成员原规范单件标识或数量模式外箱码。 */
	@Schema(description = "成员原规范单件标识或数量模式外箱码")
	private String transferCode;

	/** 封箱时该成员实际装入数量，单位为原报工基本单位。 */
	@Schema(description = "封箱时该成员实际装入数量，单位为原报工基本单位")
	private BigDecimal quantity;

	/** 按原内容主键升序的一起始成员序号。 */
	@Schema(description = "按原内容主键升序的一起始成员序号")
	private Integer memberOrdinal;

}
