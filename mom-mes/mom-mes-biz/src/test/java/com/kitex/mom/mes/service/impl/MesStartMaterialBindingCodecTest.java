package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MesStartMaterialBindingCodecTest {
	private final MesStartMaterialBindingCodec codec = new MesStartMaterialBindingCodec(new MesPhysicalTransformCodec());

	@Test
	void canonicalCommandSortsExactIdsAndKeepsIdentity() {
		var command = codec.command(body("[\"12\",\"2\"]"));

		assertEquals(java.util.List.of(2L, 12L), codec.ids(command.getFeedIds()));
		assertEquals(codec.requestHash(command), codec.requestHash(codec.command(body("[\"2\",\"12\"]"))));
		assertNotEquals(codec.requestHash(command), codec.requestHash(codec.command(body("[\"2\",\"13\"]"))));
	}

	@Test
	void rejectsUnknownDuplicateMalformedAndEmptySources() {
		assertThrows(CheckedException.class, () -> codec.command(body("[]")));
		assertThrows(CheckedException.class, () -> codec.command(body("[\"2\",\"2\"]")));
		assertThrows(CheckedException.class, () -> codec.command(body("[2]")));
		assertThrows(CheckedException.class, () -> codec.command(body("[\"02\"]")));
		assertThrows(CheckedException.class, () -> codec.command(body("[\"2\"]").replace("\"requestId\":", "\"unknown\":true,\"requestId\":")));
		assertThrows(CheckedException.class, () -> codec.command(body("[\"2\"]").replace("\"requestId\":", "\"requestId\":\"duplicate-0001\",\"requestId\":")));
	}

	private String body(String ids) {
		return "{\"contractVersion\":\"MES_START_MATERIAL_BINDING_COMMAND_V1\",\"requestId\":\"binding-0001\","
				+ "\"arrangeId\":\"1\",\"expectedTenantId\":\"1\",\"expectedFactoryCode\":\"F1\","
				+ "\"expectedActorId\":\"7\",\"feedIds\":" + ids + ",\"expectedPreviewFingerprint\":\"" + "a".repeat(64) + "\"}";
	}
}
