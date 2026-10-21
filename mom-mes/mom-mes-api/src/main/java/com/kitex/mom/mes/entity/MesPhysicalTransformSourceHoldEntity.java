package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 永久转换来源占用主账；只保护原事实，SOURCE_HELD不是PREPARED或库存转换成功。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_transform_source_hold")
@Schema(description = "实物转换永久来源占用")
public class MesPhysicalTransformSourceHoldEntity extends BaseEntity<MesPhysicalTransformSourceHoldEntity> {
    /**
     * 原登记时实际工厂，永久不改变。
     */
    private String factoryCode;
    /**
     * 唯一永久原命令ID，一个原命令不能取消后重新占用。
     */
    private Long commandId;
    /**
     * 原命令自身版本域SHA，不能用当前重新序列化正文代替。
     */
    private String commandFingerprint;
    /**
     * 固定MES_PHYSICAL_SOURCE_HOLD_V1，与来源/预检/原命令版本分开。
     */
    private String holdVersion;
    /**
     * 服务器锁内生成的完整来源包、计算与身份集合原字符串。
     */
    private String frozenSnapshot;
    /**
     * holdVersion加竖线及完整frozenSnapshot的SHA-256。
     */
    private String frozenFingerprint;
    /**
     * 数据库仅从冻结原文产生的不可变typed身份索引，不接客户端值，不交付为业务资格。
     */
    @TableField(select = false, insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String identityManifest;
    /**
     * 原实际占用本人ID，不从正文设置认证身份。
     */
    private Long actorId;
    /**
     * 原实际实名账号，不能改名后接管原占用。
     */
    private String actorCode;
    /**
     * SOURCE_HELD/CANCELLED/APPLIED；APPLIED必须有同事务正式执行主账及完整已消费身份。
     */
    private String status;
    /**
     * 首次真实来源占用时间。
     */
    private LocalDateTime heldTime;
    /**
     * 本人显式取消时间；占用中为空。
     */
    private LocalDateTime cancelledTime;
    /**
     * 原取消原因，重复同原因幂等，不能覆盖首次历史。
     */
    private String cancelReason;
    /**
     * 数据库记录的首次真实事务因果号；子身份只能在此同事务插入，不是权限或session旁路。
     */
    private Long registrationXid;
    /**
     * 数据库记录的取消事务因果号；完整释放只能发生在同次取消事务。
     */
    private Long cancellationXid;
    /**
     * 首次占用由数据库强制推进的实际scope epoch，不用该数值推断执行资格。
     */
    private Long registeredEpoch;
    /**
     * 本人取消时数据库强制推进的scope epoch，保留原占用证据。
     */
    private Long cancelledEpoch;
    /**
     * 单条永久查询同一快照核出的全部身份行数，不持久化。
     */
    @TableField(exist = false)
    private Long identityCount;
    /**
     * 与主状态一致的身份行数；同一SQL快照取得，避免取消并发拼接误判。
     */
    @TableField(exist = false)
    private Long matchingIdentityCount;
}
