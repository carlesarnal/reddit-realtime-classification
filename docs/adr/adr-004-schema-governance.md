# ADR-004: Schema Governance with Apicurio Registry

## Status

Accepted

## Context

The pipeline uses two Kafka topics as the primary data contract between components:

- `reddit-stream` (Producer -> Spark): `{id, content}`
- `kafka-predictions` (Spark -> Consumer): `{id, title, content, transformer_flair, transformer_confidence, sklearn_flair, sklearn_confidence}`

Without schema governance:
- A change in the producer's output format silently breaks Spark inference
- Adding a field to predictions requires coordinating changes across Spark and Consumer
- There's no documentation of what each field means or what types are expected
- Schema drift can introduce subtle bugs (e.g., confidence as string vs number)

Additionally, the model-metadata module validates ML model metadata against a schema. This creates a natural need for a centralized schema registry.

## Decision

Use Apicurio Registry as the schema registry for the pipeline:

1. **JSON Schemas** are defined for both Kafka topic value formats and stored in `schemas/` directory
2. **Apicurio Registry** is deployed in the `reddit-realtime` namespace as a K8s Deployment
3. **Schema registration** is automated via `schemas/register-schemas.sh` under the `reddit-realtime` group
4. **Model-metadata validation** uses Apicurio's `JsonValidator` to validate model metadata against a registered schema with BACKWARD compatibility rules

We chose JSON Schema over Avro because:
- The pipeline uses JSON serialization throughout (Python producer, Spark, Quarkus consumer)
- JSON Schema is more readable and easier to evolve than Avro for this use case
- The pipeline doesn't need Avro's binary encoding efficiency — messages are small text posts

We chose Apicurio over Confluent Schema Registry because:
- Apicurio is open-source with no vendor lock-in
- It supports JSON Schema natively (Confluent treats it as a second-class citizen)
- It aligns with the Red Hat/Quarkus ecosystem already used in the pipeline
- It supports multiple artifact types (JSON, Avro, Protobuf, OpenAPI) for future flexibility

## Consequences

**Benefits:**
- Schema changes are explicit and versioned — breaking changes are caught before deployment
- The `register-schemas.sh` script makes schema registration part of the deployment process
- Model-metadata validation enforces that ML model descriptions conform to a standard format
- Apicurio's compatibility rules (BACKWARD) prevent accidental breaking changes to model schemas

**Tradeoffs:**
- Schemas are registered manually via script, not enforced at the Kafka broker level. A future improvement would be to use Apicurio's Kafka serializer/deserializer to enforce schemas at produce/consume time.
- Adding Apicurio Registry is another component to deploy and monitor. Mitigated by including health probes and resource limits in the K8s manifest.
- JSON Schema validation is not as strict as Avro (no required field enforcement at serialization). Acceptable for current scale where the team controls all producers.

## Alternatives Considered

- **No schema registry** — Previous state. Schema drift is inevitable as the pipeline evolves.
- **Confluent Schema Registry** — Strong ecosystem but commercial licensing concerns and weaker JSON Schema support.
- **Protobuf with buf** — Better type safety but requires code generation and breaks the current JSON-based pipeline.
- **OpenAPI specs only (no runtime validation)** — Documentation without enforcement. Schemas would drift from reality.
