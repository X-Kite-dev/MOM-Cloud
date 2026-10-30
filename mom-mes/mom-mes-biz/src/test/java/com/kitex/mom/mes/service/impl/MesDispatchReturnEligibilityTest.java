package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.entity.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Exercises the existing domain eligibility logic, including the real receive parent's UN_RECEIVE status.
 */
class MesDispatchReturnEligibilityTest {

	@Test
	void receivedArrangeWithUnreceivedParentRemainsEligibleBeforeExecution() throws Exception {
		assertTrue(blockers("RECEIVE", "UN_RECEIVE", "WS1", false).isEmpty());
	}

	@Test
	void unreceivedArrangeDoesNotNeedInventedActualWorksite() throws Exception {
		assertTrue(blockers("UN_RECEIVE", "UN_RECEIVE", null, false).isEmpty());
	}

	@Test
	void receivedMissingActualWorksiteAndExecutionFactsRemainBlocked() throws Exception {
		assertFalse(blockers("RECEIVE", "UN_RECEIVE", null, false).isEmpty());
		assertFalse(blockers("UN_RECEIVE", "UN_RECEIVE", null, true).isEmpty());
	}

	@SuppressWarnings("unchecked")
	private List<String> blockers(String arrangeStatus, String parentStatus, String worksite, boolean started) throws Exception {
		var arrange = new MesTaskArrangeEntity().setStatus(arrangeStatus).setActualWorksiteCode(worksite);
		if (started) {
			arrange.setStartTime(java.time.LocalDateTime.now());
		}
		var task = new MesTaskEntity().setStatus("DISPATCH");
		var operation = new MesProduceTaskEntity().setStatus(parentStatus);
		Method method = MesDispatchReturnServiceImpl.class.getDeclaredMethod("blockers", MesTaskArrangeEntity.class, MesTaskEntity.class, MesProduceTaskEntity.class, long.class, long.class, long.class, List.class, long.class);
		method.setAccessible(true);
		return (List<String>) method.invoke(mock(MesDispatchReturnServiceImpl.class, CALLS_REAL_METHODS), arrange, task, operation, 0L, 0L, 0L, List.of(), 0L);
	}
}
