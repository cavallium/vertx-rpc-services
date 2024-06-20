package it.cavallium.vertx.rpcservice.service;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import it.cavallium.vertx.rpcservice.ServiceClass;
import it.cavallium.vertx.rpcservice.ServiceMethod;
import java.util.List;

@ServiceClass
public interface MathService {

	@ServiceMethod
	Single<Boolean> calculateNot(boolean a);

	@ServiceMethod
	Single<Boolean> calculateAnd(boolean a, boolean b);

	@ServiceMethod
	Single<Boolean> calculateOr(boolean a, boolean b);

	@ServiceMethod
	Completable calculateCompletable();

	@ServiceMethod
	Single<Boolean[]> calculateMergeToArray(boolean a, boolean b);

	@ServiceMethod
	Single<List<Boolean>> calculateMergeToList(boolean a, boolean b);

	@ServiceMethod
	Single<Boolean> calculateListOr(List<Boolean> input);

	@ServiceMethod
	Single<Boolean> calculateArrayOr(Boolean[] input);

	@ServiceMethod
	Maybe<Boolean> calculateMaybe(boolean shouldReturn);

	@ServiceMethod
	Single<ComputedBooleanOperation> calculateCustomRecordOr(BooleanOperation op);

	record BooleanOperation(boolean a, Boolean b) {}

	record ComputedBooleanOperation(BooleanOperation input, boolean result) {}

	default String test() {
		return "true";
	}
}
