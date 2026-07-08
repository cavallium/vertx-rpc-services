module vertx.rpc.services.test {
	requires vertx.rpc.services;
	requires io.vertx.core;
	requires org.junit.jupiter.api;
	requires jmh.core;
	requires vertx.rx.java3;
	requires io.reactivex.rxjava3;
	requires org.reactivestreams;
	exports it.cavallium.vertx.rpcservice.service;
}
