
# Haifa Super Harness

Independent Java 21 product foundation for the frozen DeerFlow parity program. The repository consumes Haifa Agent only through the published `io.haifa:haifa-agent-bom:0.1.1-SNAPSHOT` and public JARs in Maven repositories; it has no source-path dependency on the Haifa Agent checkout.

## W01 foundation

- `super-harness-app` proves a real public SDK `Run -> typed Java Tool -> Artifact` chain. The deterministic Tool renders content, then the application publishes it through the public Artifact service with real Run/Session provenance and verifies the stored payload hash.
- `super-harness-harness` is a minimal fail-closed evidence verifier. A required case fails when it is missing, explicitly failed, duplicated, or marked PASS without evidence.
- Maven Enforcer rejects Haifa product/demo/test JARs transitively and rejects direct implementation-module dependencies. An ArchUnit test rejects production imports from SDK internal and implementation packages.

## Verify

From a shell with Java 21 and Maven 3.9.15:

```powershell
mvn -o -ntp verify
./build-support/verify-clean-consumer.ps1
```

The first command proves a fully offline rebuild from the configured local Maven repository. The second starts with an empty temporary consumer cache and resolves through the configured Maven repository exposed as a read-only file mirror; this proves the build does not use `D:\workspace\haifa-agent` or any Reactor/source-path dependency.

The smoke test emits a `W01_SDK_EVIDENCE` line containing the real Run ID, Tool alias, Artifact ID, Artifact SHA-256, and byte count. Test reports remain under each module's `target/surefire-reports` directory.
