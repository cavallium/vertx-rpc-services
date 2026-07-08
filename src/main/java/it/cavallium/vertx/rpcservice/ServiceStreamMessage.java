package it.cavallium.vertx.rpcservice;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import it.cavallium.vertx.rpcservice.DataCodec.DataMessageCodec;

record ServiceStreamMessage<T>(Kind kind, T value, int failureCode, String errorMessage) {

	enum Kind {
		NEXT,
		NEXT_BATCH,
		COMPLETE,
		ERROR
	}

	static <T> ServiceStreamMessage<T> next(T value) {
		return new ServiceStreamMessage<>(Kind.NEXT, value, 0, null);
	}

	static ServiceStreamMessage<List<?>> nextBatch(List<?> values) {
		return new ServiceStreamMessage<>(Kind.NEXT_BATCH, values, 0, null);
	}

	static ServiceStreamMessage<Void> complete() {
		return new ServiceStreamMessage<>(Kind.COMPLETE, null, 0, null);
	}

	static ServiceStreamMessage<Void> error(int failureCode, String errorMessage) {
		return new ServiceStreamMessage<>(Kind.ERROR, null, failureCode, errorMessage);
	}

	@SuppressWarnings("rawtypes")
	static class ServiceStreamMessageMessageCodec implements
		MessageCodec<ServiceStreamMessage, ServiceStreamMessage> {

		public static final ServiceStreamMessageMessageCodec INSTANCE = new ServiceStreamMessageMessageCodec();

		private ServiceStreamMessageMessageCodec() {
		}

		@Override
		public void encodeToWire(Buffer buffer, ServiceStreamMessage message) {
			buffer.appendByte((byte) message.kind().ordinal());
			var dataCodec = new DataMessageCodec();
			if (message.kind() == Kind.NEXT) {
				encodeValue(buffer, dataCodec, message.value());
			} else if (message.kind() == Kind.NEXT_BATCH) {
				if (!(message.value() instanceof List<?> values)) {
					throw new IllegalArgumentException("NEXT_BATCH stream message value must be a List");
				}
				buffer.appendInt(values.size());
				for (var value : values) {
					encodeValue(buffer, dataCodec, value);
				}
			} else if (message.kind() == Kind.ERROR) {
				var json = new JsonObject()
					.put("failureCode", message.failureCode())
					.put("errorMessage", message.errorMessage());
				dataCodec.encodeToWire(buffer, json);
			}
		}

		@Override
		public ServiceStreamMessage<?> decodeFromWire(int pos, Buffer buffer) {
			var kind = Kind.values()[buffer.getByte(pos)];
			var valuePos = pos + 1;
			var dataCodec = new DataMessageCodec();
			return switch (kind) {
				case NEXT -> ServiceStreamMessage.next(dataCodec.decodeFromWire(valuePos, buffer));
				case NEXT_BATCH -> {
					var count = buffer.getInt(valuePos);
					if (count < 0) {
						throw new IllegalArgumentException("NEXT_BATCH stream message count is invalid: " + count);
					}
					var values = new ArrayList<>(count);
					var cursor = valuePos + Integer.BYTES;
					for (int i = 0; i < count; i++) {
						values.add(dataCodec.decodeFromWire(cursor, buffer));
						cursor = dataCodec.getPos2();
					}
					yield ServiceStreamMessage.nextBatch(values);
				}
				case COMPLETE -> ServiceStreamMessage.complete();
				case ERROR -> {
					var json = (JsonObject) dataCodec.decodeFromWire(valuePos, buffer);
					yield ServiceStreamMessage.error(
						json.getInteger("failureCode", 500),
						json.getString("errorMessage")
					);
				}
			};
		}

		@Override
		public ServiceStreamMessage transform(ServiceStreamMessage message) {
			return message;
		}

		@Override
		public String name() {
			return "ServiceStreamMessageCodec";
		}

		@Override
		public byte systemCodecID() {
			return -1;
		}

		private static void encodeValue(Buffer buffer, DataMessageCodec dataCodec, Object value) {
			if (value != null && value.getClass() == byte[].class) {
				value = Base64.getEncoder().encodeToString((byte[]) value);
			}
			dataCodec.encodeToWire(buffer, value);
		}
	}
}
