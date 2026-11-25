#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn clean package
jar=target/spl-0.1.jar
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for phase in tokens parse absyn tables semant vars; do
  java -jar "$jar" "--$phase" examples/smoke/hello.spl > "$work/$phase.txt"
  printf 'PASS: %s\n' "$phase"
done
java -jar "$jar" examples/smoke/hello.spl "$work/hello.s"
test -s "$work/hello.s"
grep -Eq 'main:' "$work/hello.s"
printf 'PASS: assembly generation\n'
for kind in syntax semantics; do
  phase=parse
  if [[ "$kind" == semantics ]]; then phase=semant; fi
  if java -jar "$jar" "--$phase" "examples/smoke/invalid-$kind.spl" > "$work/$kind.txt" 2>&1; then
    printf 'FAIL: accepted invalid %s\n' "$kind" >&2; exit 1
  fi
  grep -q 'An error occurred' "$work/$kind.txt"
  printf 'PASS: rejected invalid %s\n' "$kind"
done
printf 'Smoke checks completed. ECO32 execution not tested.\n'
