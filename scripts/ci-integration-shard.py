#!/usr/bin/env python3
"""Print Gradle --tests selectors for one integration-test shard.

Run testClasses first. Every compiled top-level test class is assigned to exactly
one shard; JUnit's integration tag still decides which tests actually execute.
"""

import argparse
import hashlib
from pathlib import Path


# PR #159의 두 실행에서 payment 1/3 shard에 느린 Spring 컨텍스트 테스트가
# 몰렸다. 같은 3개 runner 안에서 시작 비용을 고르게 배정하도록 seed를 조정했다.
SHARD_SEEDS = {"payment-service": "payment:ci-payment-2026-09"}


def selectors(classes_dir: Path, shard: int, shard_count: int, seed: str = "") -> list[str]:
    if shard_count < 1 or not 1 <= shard <= shard_count:
        raise ValueError("shard must be between 1 and shard-count")
    if not classes_dir.is_dir():
        raise ValueError(f"compiled test classes not found: {classes_dir}")

    selected = []
    for path in sorted(classes_dir.rglob("*.class")):
        # JUnit discovers nested tests through their enclosing class. Assigning
        # the nested class separately could run it twice or omit its parent.
        if "$" in path.name or path.name in {"module-info.class", "package-info.class"}:
            continue
        class_name = ".".join(path.relative_to(classes_dir).with_suffix("").parts)
        hash_input = f"{seed}:{class_name}" if seed else class_name
        bucket = int.from_bytes(hashlib.sha256(hash_input.encode()).digest()[:8], "big") % shard_count + 1
        if bucket == shard:
            selected.append(class_name)

    if not selected:
        raise ValueError(f"no compiled test classes assigned to shard {shard}/{shard_count}")
    return selected


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", help="Gradle service module")
    parser.add_argument("shard", type=int)
    parser.add_argument("shard_count", type=int)
    args = parser.parse_args()

    classes_dir = Path(args.service) / "build/classes/java/test"
    seed = SHARD_SEEDS.get(args.service, "")
    for class_name in selectors(classes_dir, args.shard, args.shard_count, seed):
        print("--tests")
        print(class_name)


if __name__ == "__main__":
    main()
