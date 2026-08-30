# Local unit tests (theme contracts + JDDEFAULT review fixes)

Run from repo root:

```sh
python -m pytest local-tests -q
```

These assert theme independence (Dark / Light / JDDEFAULT), classic jar readiness
(`synthetica.jar` + `syntheticaJDCustom.jar`), and agent/autostart source contracts.
Optional Java/ASM runtime tests (if JDK under `local-tests-out/jdk`):

```sh
bash local-tests/run-java.sh
```
