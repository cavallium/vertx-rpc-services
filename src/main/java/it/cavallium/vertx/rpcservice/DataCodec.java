package it.cavallium.vertx.rpcservice;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.Json;

public record DataCodec<T>(MessageCodec<T, T> codec) {

	public static final class DataMessageCodec implements MessageCodec<Object, Object> {
		private int pos2;

		@Override
		public void encodeToWire(Buffer buffer, Object o) {
			Json.encodeToBuffer(o).writeToBuffer(buffer);
		}

		@Override
		public Object decodeFromWire(int pos, Buffer buffer) {
			int len = buffer.getInt(pos);
			Buffer bufferData = buffer.getBuffer(pos + 4, pos + 4 + len);
			this.pos2 = pos + 4 + len;
			return Json.decodeValue(bufferData);
		}

		public int getPos2() {
			return pos2;
		}

		@Override
		public Object transform(Object o) {
			return o;
		}

		@Override
		public String name() {
			return "JsonObjectCodec";
		}

		@Override
		public byte systemCodecID() {
			return -1;
		}
	}
}
