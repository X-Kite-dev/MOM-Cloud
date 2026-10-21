package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 来源占用的永久typed identity；历史行不删，取消只释放活动占用而不重开原命令。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_transform_source_claim")
@Schema(description = "实物转换来源占用身份")
public class MesPhysicalTransformSourceClaimEntity extends BaseEntity<MesPhysicalTransformSourceClaimEntity> {
    /**
     * 原实际工厂。
     */
    private String factoryCode;
    /**
     * 同实际scope的永久占用主账ID。
     */
    private Long holdId;
    /**
     * STOCK_ID/REPORT_ID/IDENTIFIER或原任务、工序、派工工程身份类型。
     */
    private String identityType;
    /**
     * 精确文本身份，不用编码猜选库存行，不强转长整数。
     */
    private String identityKey;
    /**
     * HELD/RELEASED，必须与主账状态及提交完整性一致。
     */
    private String status;
}
