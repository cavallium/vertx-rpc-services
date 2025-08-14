package it.cavallium.vertx.rpcservice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ServiceMethod {
    /**
     * Timeout, in seconds
     */
    int timeout() default 30;
}
