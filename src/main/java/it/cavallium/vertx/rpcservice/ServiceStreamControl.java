package it.cavallium.vertx.rpcservice;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import java.nio.charset.StandardCharsets;

record ServiceStreamControl(String responseAddress, long request, boolean cancel) {

	static class ServiceStreamControlMessageCodec implements
		MessageCodec<ServiceStreamControl, ServiceStreamControl> {

		public static final ServiceStreamControlMessageCodec INSTANCE = new ServiceStreamControlMessageCodec();
		private static final byte FLAG_CANCEL = 1;
		private static final byte FLAG_RESPONSE_ADDRESS = 1 << 1;
		private static final byte KNOWN_FLAGS = FLAG_CANCEL | FLAG_RESPONSE_ADDRESS;
		private static final int HEADER_SIZE = Byte.BYTES + Long.BYTES;

		private ServiceStreamControlMessageCodec() {
		}

		@Override
		public void encodeToWire(Buffer buffer, ServiceStreamControl control) {
			var flags = (byte) 0;
			var responseAddress = control.responseAddress();
			if (control.cancel()) {
				flags |= FLAG_CANCEL;
			}
			if (responseAddress != null) {
				flags |= FLAG_RESPONSE_ADDRESS;
			}
			buffer.appendByte(flags);
			buffer.appendLong(control.request());
			if (responseAddress != null) {
				var encodedAddress = responseAddress.getBytes(StandardCharsets.UTF_8);
				buffer.appendInt(encodedAddress.length);
				buffer.appendBytes(encodedAddress);
			}
		}

		@Override
		public ServiceStreamControl decodeFromWire(int pos, Buffer buffer) {
			if (buffer.length() - pos < HEADER_SIZE) {
				throw new IllegalArgumentException("ServiceStreamControl frame is too short");
			}
			var flags = buffer.getByte(pos);
			if ((flags & ~KNOWN_FLAGS) != 0) {
				throw new IllegalArgumentException("ServiceStreamControl frame has unknown flags: " + flags);
			}
			var request = buffer.getLong(pos + Byte.BYTES);
			var cursor = pos + HEADER_SIZE;
			String responseAddress = null;
			if ((flags & FLAG_RESPONSE_ADDRESS) != 0) {
				if (buffer.length() - cursor < Integer.BYTES) {
					throw new IllegalArgumentException("ServiceStreamControl response address length is missing");
				}
				var addressLength = buffer.getInt(cursor);
				cursor += Integer.BYTES;
				if (addressLength < 0 || buffer.length() - cursor < addressLength) {
					throw new IllegalArgumentException("ServiceStreamControl response address length is invalid: " + addressLength);
				}
				responseAddress = new String(buffer.getBytes(cursor, cursor + addressLength), StandardCharsets.UTF_8);
			}
			return new ServiceStreamControl(responseAddress, request, (flags & FLAG_CANCEL) != 0);
		}

		@Override
		public ServiceStreamControl transform(ServiceStreamControl control) {
			return control;
		}

		@Override
		public String name() {
			return "ServiceStreamControlCodec";
		}

		@Override
		public byte systemCodecID() {
			return -1;
		}
	}
}
