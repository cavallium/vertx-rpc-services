module vertx.rpc.services.test {
	requires vertx.rpc.services;
	requires org.junit.jupiter.api;
	requires vertx.rx.java3;
	requires io.reactivex.rxjava3;
	exports it.cavallium.vertx.rpcservice.service;
}