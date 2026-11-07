package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.mapper.MesDispatchCompletionMapper;
import com.kitex.mom.mes.mapper.MesDispatchAllocationMapper;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * 原工单事务锁内派工→工序→工单的完成判定；只读事实，不另建状态或数量账。
 */
@Component
@RequiredArgsConstructor
public class MesDispatchCompletionGuard {
	private final MesDispatchCompletionMapper facts;
	private final DataSource dataSource;
	private final MesDispatchAllocationMapper allocations;
	private final MesDispatchAllocationLedger allocationLedger;

	private static void require(boolean condition, String message) {
		if (!condition) throw new CheckedException(message);
	}

	public Decision evaluate(MesTaskArrangeEntity finished) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
						&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory)
						&& finished != null && finished.getId() != null && StrUtil.isNotBlank(finished.getTaskCode())
						&& Objects.equals(tenant, finished.getTenantId()) && Objects.equals(factory, finished.getFactoryCode()),
				"完工汇总必须使用实际租户工厂的原派工");
		requireCurrentTransaction();
		var operations = facts.operations(tenant, factory, finished.getTaskCode());
		var graph = MesFrozenExecutionGraph.of(operations);
		var parent = graph.current(finished);
		require(parent.getStatus() != null && !Set.of("CLOSE", "FINISH").contains(parent.getStatus()),
				"已关闭或完工工序不能由残留派工重新完成");
		var dispatches = facts.dispatches(tenant, factory, finished.getTaskCode());
		require(dispatches != null && !dispatches.isEmpty(), "完工汇总缺少原派工集合");
		Set<Long> ids = new HashSet<>();
		Set<String> codes = new HashSet<>();
		MesTaskArrangeEntity saved = null;
		for (var row : dispatches) {
			require(row != null && row.getId() != null && ids.add(row.getId())
					&& StrUtil.isNotBlank(row.getCode()) && codes.add(row.getCode()), "完工汇总派工身份缺失或重复");
			graph.current(row);
			require(knownState(row.getStatus()), "完工汇总存在未知派工状态");
			if (Objects.equals(row.getId(), finished.getId())) saved = row;
		}
		require(saved != null && Objects.equals(saved.getCode(), finished.getCode())
						&& Set.of("FINISH", "CLOSE").contains(saved.getStatus())
						&& Objects.equals(saved.getStatus(), finished.getStatus()),
				"完工汇总缺少本次已保存的派工终止事实");
		for (var operation : operations) require(knownState(operation.getStatus()), "完工汇总存在未知工序状态");
		var peerStates = dispatches.stream().filter(row -> Objects.equals(row.getProduceTaskCode(), parent.getCode()))
				.map(MesTaskArrangeEntity::getStatus).toList();
		String processStatus = "FINISH";
		// 仅CLOSE表示该派工已终止，不充当完成量；其余任何活动状态均须保留。
		for (String active : List.of("PRODUCT", "PAUSE", "RECEIVE", "UN_RECEIVE", "WAIT")) {
			if (peerStates.contains(active)) {
				processStatus = active;
				break;
			}
		}
		// 部分派工已完成、兄弟尚待接收时，父工序仍在制；不能把已发生生产退回未接收。
		if (Set.of("RECEIVE", "UN_RECEIVE", "WAIT").contains(processStatus)
				&& Set.of("PRODUCT", "PAUSE").contains(parent.getStatus())) {
			processStatus = parent.getStatus();
		} else if (Set.of("UN_RECEIVE", "WAIT").contains(processStatus)
				&& "RECEIVE".equals(parent.getStatus())) {
			processStatus = parent.getStatus();
		}
		// 全部关闭仅表示停止；至少一次真实FINISH才可以驱动父对象完成。
		if ("FINISH".equals(processStatus) && !peerStates.contains("FINISH")) return new Decision(null, null);
		if ("FINISH".equals(processStatus)) {
			// 已完成本次60件并不代表原工序100件已全部分配；未分配/正式释放的余额不制造虚拟派工。
			allocationLedger.requireReady();
			var quota = allocations.allocations(tenant, factory, finished.getTaskCode()).stream()
					.filter(row -> Objects.equals(row.getProduceTaskCode(), parent.getCode())).toList();
			if (!quota.isEmpty()) {
				BigDecimal occupied = BigDecimal.ZERO;
				for (var row : quota) {
					require(row.getOccupiedQuantity() != null && row.getOccupiedQuantity().signum() >= 0,
							"完工汇总配额占用未知，请先核定");
					occupied = occupied.add(row.getOccupiedQuantity());
				}
				require(parent.getNoticeNum() != null && parent.getNoticeNum().signum() > 0
						&& occupied.compareTo(parent.getNoticeNum()) <= 0, "工序配额占用超出原需求或需求未知");
				if (occupied.compareTo(parent.getNoticeNum()) < 0) {
					return new Decision(null, null);
				}
			}
		}
		boolean allOperationsFinished = "FINISH".equals(processStatus) && operations.stream()
				.allMatch(row -> Objects.equals(row.getId(), parent.getId()) || "FINISH".equals(row.getStatus()));
		boolean allDispatchesEnded = dispatches.stream().allMatch(row -> Set.of("FINISH", "CLOSE").contains(row.getStatus()));
		if (!allOperationsFinished || !allDispatchesEnded) return new Decision(processStatus, null);
		var endings = operations.stream().filter(row -> Boolean.TRUE.equals(row.getLastRoute())).toList();
		require(!endings.isEmpty(), "完工汇总缺少冻结终点工序，不得猜用当前工序数量");
		for (var ending : endings) {
			require(ending.getCompleteNum() != null && ending.getCompleteNum().signum() >= 0
							&& ending.getNoticeNum() != null && ending.getNoticeNum().signum() > 0
							&& ending.getCompleteNum().compareTo(ending.getNoticeNum()) <= 0,
					"完工汇总终点实际量缺失或超出原工序数量");
		}
		// 多个历史终点沿原最小量口径；新节点协议本身要求唯一终点。绝不相加工序产量。
		return new Decision(processStatus, endings.stream().map(row -> row.getCompleteNum()).min(BigDecimal::compareTo).orElseThrow());
	}

	private void requireCurrentTransaction() {
		require(TransactionSynchronizationManager.isActualTransactionActive(), "完工汇总必须处于原执行事务内");
		Connection connection = DataSourceUtils.getConnection(dataSource);
		try {
			require(DataSourceUtils.isConnectionTransactional(connection, dataSource)
							&& connection.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED,
					"完工汇总需要同数据源READ_COMMITTED事务，请原号重试");
		} catch (SQLException failure) {
			throw new CheckedException("无法核验完工汇总事务隔离级别");
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	private boolean knownState(String state) {
		return state != null && Arrays.stream(MesProduceTaskStatusEnum.values()).anyMatch(value -> value.name().equals(state));
	}

	public record Decision(String processStatus, BigDecimal completedQuantity) {
	}
}
