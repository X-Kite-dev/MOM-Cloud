package com.kitex.mom.mdm.vo;

import com.kitex.mom.mdm.entity.MdmBomEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.time.LocalDate;

/**
 * BOM 产品版本树节点。
 *
 * <p>除版本主数据外，同时返回工程变更治理状态，供前端准确控制编辑、删除、
 * 提交和查看审批入口，避免仅根据启用状态误判草案是否仍可维护。</p>
 *
 * @author kitex
 */
@Data
@Schema(description = "BOM产品版本树节点")
public class MdmBomVersionVO {

    @Schema(description = "BOM版本ID")
    private Long id;

    @Schema(description = "工厂编码")
    private String factoryCode;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "产品规格型号")
    private String productSpec;

    @Schema(description = "产品单位")
    private String unit;

    @Schema(description = "BOM版本号")
    private String version;

    @Schema(description = "BOM类型")
    private String type;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "是否为当前默认版本")
    private Boolean isDefault;

    @Schema(description = "工程发布状态：LEGACY、DRAFT、RELEASED")
    private String releaseState;

    @Schema(description = "生效日期")
    private LocalDate startDate;

    @Schema(description = "失效日期")
    private LocalDate endDate;

    @Schema(description = "是否被工程变更冻结")
    private Boolean frozen;

    @Schema(description = "当前用户界面是否允许编辑或删除该版本")
    private Boolean editable;

    @Schema(description = "关联工程变更ID；审批中的工程变更优先返回")
    private Long ecnId;

    @Schema(description = "关联工程变更状态：PENDING、PUBLISHED")
    private String ecnStatus;

    /**
     * 从BOM实体复制稳定的版本主数据字段。
     *
     * @param source BOM实体
     * @return 产品版本树节点
     */
    public static MdmBomVersionVO from(MdmBomEntity source) {
        MdmBomVersionVO target = new MdmBomVersionVO();
        BeanUtils.copyProperties(source, target);
        return target;
    }
}
