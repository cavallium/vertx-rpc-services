# Vert.x RPC Services

A tiny, zero‑boilerplate RPC library on top of the Vert.x Event Bus. Define a Java interface, annotate its methods, and get a fully asynchronous, type‑aware RPC between verticles (local or clustered) using RxJava 3 types.

With this simple library, you can implement very complex and performant async RPC flows with very little complexity.

## Overview

- Stack: Java 21, Vert.x 5, RxJava 3, Maven
- Module type: Library (JAR)
- Transport: Vert.x Event Bus (local or cluster; `localOnly` supported)
- Programming model: Annotated interfaces + RxJava return types (`Single<T>`, `Maybe<T>`, `Completable`)
- Addressing: `t_service_{InterfaceSimpleName}#methodName`

What you write:
- A service interface annotated with `@ServiceClass` and methods annotated with `@ServiceMethod` returning Rx types
- A service implementation class

What this library generates for you at runtime:
- A server that registers consumers for your service methods and handles invocation, conversion and reply
- A client proxy that maps interface calls to Event Bus requests and deserializes responses to your expected types

## Requirements

- JDK 21+
- Apache Maven 3.9+

## Installation

This project is built with Maven and currently publishes snapshots/releases to custom repositories defined in the POM.

Maven coordinates:

```
<dependency>
  <groupId>it.cavallium</groupId>
  <artifactId>vertx-rpc-services</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

1) Define your service interface

```java
import io.reactivex.rxjava3.core.*;
import it.cavallium.vertx.rpcservice.*;

@ServiceClass
public interface MathService {
  @ServiceMethod
  Single<Integer> sum(int a, int b);

  @ServiceMethod
  Maybe<String> findPrimeName(int n);

  @ServiceMethod
  Completable warmup();
}
```

2) Implement it

```java
public class MathServiceImpl implements MathService {
  public Single<Integer> sum(int a, int b) { return Single.just(a + b); }
  public Maybe<String> findPrimeName(int n) { return Maybe.empty(); }
  public Completable warmup() { return Completable.complete(); }
}
```

3) Expose the service (server side)

```java
import io.vertx.rxjava3.core.Vertx;
import it.cavallium.vertx.rpcservice.ServiceServer;

Vertx vertx = Vertx.vertx();
MathService impl = new MathServiceImpl();
boolean localOnly = false; // true to restrict to local event bus

// Registers one consumer per @ServiceMethod
ServiceServer<MathService> server = new ServiceServer<>(
    vertx,
    impl,
    MathService.class,
    localOnly
);
```

4) Consume the service (client side)

```java
import io.vertx.rxjava3.core.Vertx;
import it.cavallium.vertx.rpcservice.ServiceClient;

Vertx vertx = Vertx.vertx();
boolean localOnly = false;

ServiceClient<MathService> clientFactory = new ServiceClient<>(vertx, MathService.class, localOnly);
MathService client = clientFactory.getInstance();

client.sum(2, 3).subscribe(result -> {
  System.out.println("sum = " + result);
});
```

That’s it. The library registers compact message codecs, converts payloads to your declared types (including enums, `UUID`, `Instant`, lists, `JsonObject` mappers, and base64 for `byte[]` roots), and wires the async flow end‑to‑end.

## Key Annotations and Types

- `@ServiceClass` on the interface type
- `@ServiceMethod` on each RPC method
  - Optional parameter `timeout` (seconds) controls Event Bus send timeout per method (default: 30s)
- Supported return types:
  - `Single<T>`: exactly one value
  - `Maybe<T>`: zero or one value
  - `Completable`: no value, success/failure only

## Configuration and Environment

- Event Bus locality: constructors take `localOnly` to restrict communication to the local event bus if desired.
- Cluster/transport configuration is provided by Vert.x itself; configure Vert.x clustering as usual if you need a distributed bus.

No environment variables are required by this library itself.

## Build, Test, and Run

Common Maven commands:

```
mvn -v                 # show Maven info
mvn clean              # clean build outputs
mvn test               # run unit tests (JUnit 5)
mvn package            # build JAR
mvn install            # install to local repository
```

Tests use JUnit Jupiter (5.x). You can run them from your IDE or the command line.

## Project Structure

```
src/main/java/it/cavallium/vertx/rpcservice/
  ServiceClient.java          # Builds a dynamic client proxy for a service interface
  ServiceServer.java          # Registers event-bus consumers and dispatches to implementation
  ServiceUtils.java           # Addressing, codec registration, type conversions
  ServiceClass.java           # Marker annotation for service interfaces
  ServiceMethod.java          # Annotation for RPC methods (with timeout)

src/test/java/it/cavallium/vertx/rpcservice/service/
  MathService.java            # Example service interface used in tests
  MathServiceImpl.java        # Example implementation used in tests
```

## Scripts and Entry Points

This is a library and does not provide a runnable main entry point. Use it from your Vert.x application by constructing `ServiceServer` and `ServiceClient` as shown above.

Maven lifecycle phases act as scripts:
- `mvn clean` — clean outputs
- `mvn test` — run tests
- `mvn package` — build the JAR

## Serialization and Type Conversion

The library automatically registers default codecs for request/response wrappers and converts values to your declared types:
- Strings to enums, `UUID`
- Numeric epoch seconds to `Instant`
- Root `String` to `byte[]` via base64 when the return type is `byte[]`
- `JsonObject` to POJO (via Vert.x mapping)
- `JsonArray` to `List<E>` with recursive element conversion

If a value cannot be converted to the declared type, the call fails with an error.

## Addressing Scheme

Event Bus addresses are derived from the interface simple name and method name:

```
t_service_{InterfaceSimpleName}#methodName
```

You usually do not need to know this, but it can be helpful for debugging or wiring advanced consumers.
