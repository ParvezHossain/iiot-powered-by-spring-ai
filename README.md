# IIoT Powered by AI

Initial Spring Boot scaffold for an AI-powered Industrial IoT platform. More documentation will follow.

## Requirements

- JDK 21 or newer (Java 21 is the compilation target).
- Internet access on the first build to download Maven and dependencies.

## Run

```sh
./mvnw spring-boot:run
```

The application listens on port 8080. It includes Spring Web MVC, Data JPA,
Validation, Actuator, and an in-memory H2 database. No external database is required;
data is discarded on shutdown.

## Health check

```sh
curl --fail http://localhost:8080/actuator/health
```

Expected: HTTP 200 with a JSON body containing `"status":"UP"`. The health check includes database connectivity.

## Verify

```sh
./mvnw verify
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

## License

[MIT](LICENSE).
