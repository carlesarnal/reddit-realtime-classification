# Verification ledger

Prepared October 23, 2026.

Timestamp analyzer: three unit tests passed October 23. Fixtures are synthetic correctness inputs, not performance results. The fixed-replay Spark load experiment and fresh Kubernetes execution remain pending.

## Reproduce

```sh
python3 -m unittest discover -s talk -p "test_*.py" -v
```

A documentation or model check is not an integration test. New results must record source revision, runtime versions, command, observed outcome and remaining limits. No acceptance score is guaranteed.

## Package checks

Offline deck navigation/layout and documentation links are checked separately from application behavior. No claim of a five-star speaking performance is made by these artifacts.
