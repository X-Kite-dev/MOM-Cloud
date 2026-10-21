package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.dto.MesProductionOrderDTO;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.vo.MesProductionOrderQueryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 生产订单主表 Mapper 接口
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Mapper
public interface MesProductionOrderMapper extends BaseMapper<MesProductionOrderEntity> {

    /**
     * 串行化同一租户、工厂和生产订单号的接收，防止拆批并发时重复建立订单主记录。
     *
     * @param lockKey 租户、工厂和订单号组成的稳定锁键
     * @return 数据库锁占位值
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    Integer lockReceivedOrder(@Param("lockKey") String lockKey);

    /**
     * 分页查询生产订单（包含统计信息）
     *
     * @param page    分页参数
     * @param queryVO 查询条件
     * @return 生产订单分页数据
     */
    Page<MesProductionOrderDTO> selectProductionOrderPage(Page<MesProductionOrderDTO> page, @Param("query") MesProductionOrderQueryVO queryVO);

    /**
     * 根据订单ID查询生产订单详情
     *
     * @param orderId 订单ID
     * @return 生产订单详情
     */
    MesProductionOrderDTO selectProductionOrderById(@Param("orderId") Long orderId);

    /**
     * 根据订单编号查询生产订单详情
     *
     * @param orderNo 订单编号
     * @return 生产订单详情
     */
    MesProductionOrderDTO selectProductionOrderByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 统计生产订单数量按状态分组
     *
     * @param factoryCode 工厂编码
     * @return 统计结果
     */
    List<Map<String, Object>> selectProductionOrderCountByStatus(@Param("factoryCode") String factoryCode);

    /**
     * 查询待排程的生产订单
     *
     * @param factoryCode 工厂编码
     * @return 待排程订单列表
     */
    List<MesProductionOrderDTO> selectPendingScheduleOrders(@Param("factoryCode") String factoryCode);

    /**
     * 查询超期未完成的生产订单
     *
     * @param factoryCode 工厂编码
     * @return 超期订单列表
     */
    List<MesProductionOrderDTO> selectOverdueOrders(@Param("factoryCode") String factoryCode);

    /**
     * 更新订单完成数量和剩余数量
     *
     * @param orderId          订单ID
     * @param finishedQuantity 完成数量
     * @return 更新结果
     */
    int updateOrderQuantities(@Param("orderId") Long orderId, @Param("finishedQuantity") java.math.BigDecimal finishedQuantity);
}
