package it.cavallium.vertx.rpcservice.service;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Arrays;
import java.util.List;

class MathServiceImpl implements MathService {

	@Override
	public Single<Boolean> calculateNot(boolean a) {
		return Single.just(!a);
	}

	@Override
	public Single<Boolean> calculateAnd(boolean a, boolean b) {
		return Single.just(a & b);
	}

	@Override
	public Single<Boolean> calculateOr(boolean a, boolean b) {
		return Single.just(a | b);
	}

	@Override
	public Completable calculateCompletable() {
		return Completable.complete();
	}

	@Override
	public Single<Boolean[]> calculateMergeToArray(boolean a, boolean b) {
		return Single.just(new Boolean[]{a, b});
	}

	@Override
	public Single<List<Boolean>> calculateMergeToList(boolean a, boolean b) {
		return Single.just(List.of(a, b));
	}

	@Override
	public Single<Boolean> calculateListOr(List<Boolean> input) {
		return Single.just(input.stream().reduce(false, (a, b) -> a | b));
	}

	@Override
	public Single<Boolean> calculateArrayOr(Boolean[] input) {
		return Single.just(Arrays.stream(input).reduce(false, (a, b) -> a | b));
	}

	@Override
	public Single<ComputedBooleanOperation> calculateCustomRecordOr(BooleanOperation op) {
		return Single.just(new ComputedBooleanOperation(op, op.a() | op.b()));
	}

	@Override
	public Maybe<Boolean> calculateMaybe(boolean shouldReturn) {
		return shouldReturn ? Maybe.just(true) : Maybe.empty();
	}
}
