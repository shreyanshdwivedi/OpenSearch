# DSL `range` aggregation — implementation & test notes

Branch: `dsl-range-aggregation` (worktree `/Volumes/workplace/os-range`, off `origin/main` @ 29144707aac)
Scope: v1 = single-membership (CASE-ordinal grouping); overlapping ranges rejected at validation
(mirrors PPL `bin`, which is single-membership by design; classic multi-membership deferred).

## STATUS (honest)
- Code written: YES — end-to-end wired (translator + response strategy + computed-grouping plumbing
  + registration + golden + IT case + README).
- Unit tests: **GREEN** — RangeBucketTranslatorTests 11/0/0, RangeResponseStrategyTests 6/0/0.
- End-to-end golden (convert → mock-execute → response): **GREEN** — the auto-discovered
  `range_numeric_aggregation.json` passes both `SearchSourceConverterTests` (23/0/0) and
  `SearchResponseBuilderTests` (17/0/0); `AggregationTreeWalkerTests` 22/0/0 (no regression).
  This is the same end-to-end bar Kamal's bool PR + the hits PR use.
- Live-cluster IT: the range case is added to `DslAggregationIT`, but that whole class is
  `@AwaitsFix` for EVERY DSL feature ("analytics engine pipeline not E2E complete: fragment
  conversion + shard execution + Arrow Flight drain not yet wired") — so no DSL feature has a
  passing live-cluster test yet; the range IT compiles and will run when the engine lands.
- v1 scope: single-membership; overlapping ranges / script / missing rejected with citations.

## Files created (step a)
- `.../dsl/aggregation/ExpressionGrouping.java` — computed-key grouping (synthetic ordinal column + bounds).
- `.../dsl/aggregation/bucket/RangeBucketTranslator.java` — grouping=ordinal; validate() rejects
  script/missing/overlapping ranges (cites RangeAggregator.collect); no bucket order; sub-aggs pass-through.
- `.../dsl/aggregation/bucket/RangeResponseStrategy.java` — builds InternalRange via raw Factory;
  materializes ALL declared ranges in order (empties as doc_count 0); maps ordinal→range.
- `.../test/.../bucket/RangeBucketTranslatorTests.java` — validate + getGrouping + sub-aggs + type.
- `.../test/.../bucket/RangeResponseStrategyTests.java` — response materialization/rendering.

## Test scenario matrix (designed; execution pending JDK 25)
Unit — RangeBucketTranslatorTests:
1. non-overlapping ranges → validate passes
2. touching bounds [0,100)+[100,200) → NOT overlap → passes
3. overlapping [0,100)+[50,150) → ConversionException("overlapping ranges")
4. overlap across unbounded [*,100)+[50,*) → ConversionException
5. script → ConversionException("[script]")
6. missing → ConversionException("[missing]")
7. getGrouping → ExpressionGrouping, synthetic col "_range$price_ranges", field "price", 3 bounds ±inf
8. sub-aggregations pass-through / empty
9. no bucket order; aggregation type
Unit — RangeResponseStrategyTests:
10. all ranges materialized in declaration order; empty ordinal → doc_count 0 (Integer+Long keys)
11. from/to incl ±infinity → open-end *AsString == null; "100.0" rendered
12. empty result → all ranges doc_count 0
13. null-ordinal group ignored (99 unmatched docs leak into no bucket)
14. keyed flag propagated
15. meta echoed when supplied
Integration — DslAggregationIT (STEP B, pending): parity vs classic for basic 3-range, range+avg sub-agg,
empty range present, nesting; overlapping-ranges request rejected.

## Commands run + EXACT output
```
$ export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # Corretto 25.0.4.1
$ ./gradlew :sandbox:plugins:dsl-query-executor:test -Dsandbox.enabled=true \
    --tests "org.opensearch.dsl.aggregation.bucket.RangeBucketTranslatorTests" \
    --tests "org.opensearch.dsl.aggregation.bucket.RangeResponseStrategyTests"
BUILD SUCCESSFUL in 12s
75 actionable tasks: 4 executed, 71 up-to-date

# JUnit result XML (build/test-results/test/):
RangeBucketTranslatorTests   tests=11 skipped=0 failures=0 errors=0 time=1.706s
RangeResponseStrategyTests   tests=6  skipped=0 failures=0 errors=0 time=1.589s
# TOTAL 17/17 PASS

# One real compile error was hit and fixed en route:
#   RangeResponseStrategy.java:86: error: FACTORY is not public in InternalRange
#   -> fixed by using `new InternalRange.Factory().create(...)` instead of InternalRange.FACTORY
```

## To re-run
```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
cd /Volumes/workplace/os-range
./gradlew :sandbox:plugins:dsl-query-executor:test -Dsandbox.enabled=true \
  --tests "org.opensearch.dsl.aggregation.bucket.RangeBucketTranslatorTests" \
  --tests "org.opensearch.dsl.aggregation.bucket.RangeResponseStrategyTests"
```
