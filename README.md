# Task Orchestrator Platform (Kafka + Spring Boot + Postgres + Redis)

A microservices-based job orchestration platform:
- **orchestrator-api**: REST API for submitting and querying jobs (Postgres-backed).
- **scheduler-service**: polls the DB, transitions jobs through states, publishes work to Kafka.
- **worker-service**: consumes jobs from Kafka, executes work, writes results back to Postgres, retries failures, and dead-letters jobs to a DLQ topic.

## Architecture

Job lifecycle:
`SUBMITTED → QUEUED → RUNNING → SUCCEEDED`
On failure:
- retry: `RUNNING → QUEUED` (attempt++)
- DLQ: `DEAD_LETTERED` + publish to `jobs.dlq.v1` after max retries

Kafka topics:
- `jobs.v1` (work queue)
- `jobs.dlq.v1` (dead-letter queue)

## Tech Stack
- Java 17, Spring Boot (API + Scheduler + Worker)
- Kafka (Confluent images), Postgres 16, Redis 7
- Docker Compose for local infra

## Quickstart

### 1) Start infrastructure
```bash
docker compose up -d
docker ps
```

### 2) Run services (3 terminals)
# Terminal 1
```bash
cd services/orchestrator-api
mvn spring-boot:run
```

# Terminal 2
```bash
cd services/scheduler-service
./mvnw spring-boot:run
```

# Terminal 3
```bash
cd services/worker-service
./mvnw spring-boot:run
```

### 3) Create a job
```bash
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"title":"demo job","priority":5,"estimatedRuntimeMs":1500,"maxRetries":3}'
```

### 4) List jobs + status
```bash
curl http://localhost:8080/api/jobs
```

### 5) Read DLQ (if any)
```bash
docker exec -it task-orchestrator-platform-kafka-1 \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic jobs.dlq.v1 --from-beginning --timeout-ms 15000
```
### Ports
orchestrator-api: 8080
scheduler-service: 8081
worker-service: 8082
Postgres: 5432
Kafka: 9092
Redis: 6379