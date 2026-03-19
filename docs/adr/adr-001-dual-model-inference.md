# ADR-001: Dual-Model Inference Architecture

## Status

Accepted

## Context

We need to classify Reddit posts from r/AskEurope into 13 flair categories in real-time. The classification must be accurate enough to be useful, but we also need to understand how confident and reliable our predictions are.

A single model gives us predictions but no way to gauge trustworthiness. If the model is wrong, we have no signal to detect it without ground truth labels, which aren't available in real-time.

## Decision

We run two fundamentally different models in parallel on every post:

1. **Transformer (DistilBERT fine-tuned)** — Deep learning model that captures semantic meaning and context. Higher accuracy but computationally expensive.
2. **scikit-learn (TF-IDF + LSA + classifier)** — Classical ML pipeline using bag-of-words features. Faster, lower resource usage, but less capable with nuanced text.

Both models output a predicted flair and a confidence score. The Quarkus consumer tracks their agreement rate, per-flair confidence gaps, and confusion matrix.

## Consequences

**Benefits:**
- **Model agreement rate** acts as a proxy for prediction reliability without ground truth. When both models agree, confidence is higher.
- **Disagreement signals** highlight ambiguous posts or categories where one model's approach works better (e.g., sklearn may struggle with sarcasm that the transformer handles).
- **Confidence gap analysis** reveals which flairs are genuinely hard to classify vs. which ones only fool one model type.
- **A/B comparison** gives concrete data for deciding whether the Transformer's accuracy gain justifies its resource cost.

**Tradeoffs:**
- **2x inference cost** per message. We accept this because the Spark job runs on dedicated resources and the micro-batch trigger (1 minute) absorbs the overhead.
- **More complex Spark UDF** — the prediction function must handle both models and serialize results. Mitigated by wrapping each model in its own OTel span for independent latency tracking.
- **Schema coupling** — the `kafka-predictions` topic schema includes fields for both models. If we drop one model, consumers must handle missing fields gracefully.

## Alternatives Considered

- **Single model with confidence thresholding** — Simpler, but no way to validate predictions without labels.
- **Ensemble (averaged predictions)** — Loses the ability to compare model behaviors. Agreement rate is more informative than a blended score.
- **Champion/challenger with traffic split** — Adds routing complexity. Our approach gives us comparison data on every single post.
